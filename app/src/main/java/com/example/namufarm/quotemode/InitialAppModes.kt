package com.example.namufarm.quotemode

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.ui.ManagementModeScreen
import com.example.namufarm.ui.SellingModeScreen
import com.example.namufarm.ui.theme.*
import com.example.namufarm.viewmodel.ManagementViewModel
import com.example.namufarm.viewmodel.CustomerInquiryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InitialAppModes(management: ManagementViewModel, quote: InitialQuoteViewModel,
                    inquiry: CustomerInquiryViewModel) {
    val pager = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val inbox by quote.uiState.collectAsState()
    val inquiryState by inquiry.state.collectAsState()
    val stock by management.uiState.collectAsState()
    var bottomBarVisible by remember { mutableStateOf(true) }
    var showMoveToManagementConfirm by remember { mutableStateOf(false) }
    val labels = listOf("견적", "문의", "매매", "농원·묘목")
    val icons = listOf(Icons.Outlined.ReceiptLong, Icons.AutoMirrored.Outlined.Chat,
        Icons.Outlined.Handshake, Icons.Outlined.Park)
    fun move(page: Int) { scope.launch { pager.animateScrollToPage(page) } }
    fun requestMove(page: Int) {
        if (page == 3 && stock.estimateCart.isNotEmpty()) showMoveToManagementConfirm = true
        else move(page)
    }
    BackHandler(enabled = pager.currentPage != 0) { move(0) }
    // Swiping to the farm page uses the same unsent-cart confirmation as tapping its tab.
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage == 3 && stock.estimateCart.isNotEmpty()) {
            showMoveToManagementConfirm = true
            pager.scrollToPage(2)
        }
    }
    NamuTheme {
        Scaffold(containerColor = NamuColors.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
                if (bottomBarVisible) Surface(color = Color.White, shadowElevation = 2.dp) {
                    Column(Modifier.navigationBarsPadding()) {
                        HorizontalDivider(color = NamuColors.border)
                        NavigationBar(containerColor = Color.White, tonalElevation = 0.dp,
                            modifier = Modifier.height(if (LocalDensity.current.fontScale > 1.15f) 92.dp else 72.dp), windowInsets = WindowInsets(0, 0, 0, 0)) {
                            labels.forEachIndexed { index, label ->
                                val count = when (index) {
                                    0 -> inbox.requests.count { it.status == "신규" }
                                    1 -> inquiryState.rows.count { it.status == "open" }
                                    else -> 0
                                }
                                NavigationBarItem(selected = pager.currentPage == index,
                                    onClick = { requestMove(index) },
                                    icon = {
                                        BadgedBox(badge = { if (count > 0) Badge(containerColor = NamuColors.danger,
                                            contentColor = Color.White) { Text("$count", fontSize = 13.sp) } }) {
                                            Icon(icons[index], null, Modifier.size(24.dp))
                                        }
                                    }, label = { Text(label, fontSize = 13.sp,
                                        fontWeight = if (pager.currentPage == index) FontWeight.Bold else FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = NamuColors.navPill,
                                        selectedIconColor = NamuColors.primary, selectedTextColor = NamuColors.text,
                                        unselectedIconColor = NamuColors.muted, unselectedTextColor = NamuColors.muted))
                            }
                        }
                    }
                }
            }) { padding ->
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(padding)
                .statusBarsPadding().consumeWindowInsets(WindowInsets.statusBars),
                userScrollEnabled = bottomBarVisible && inbox.editingRequest == null && !inbox.busy && !inquiryState.busy) { page ->
                when (page) {
                    0 -> InitialQuoteScreen(quote, quote.connection, inbox,
                        onSync = quote::refresh, onRead = quote::read, onDelete = quote::close)
                    1 -> CustomerInquiryScreen(inquiry)
                    2 -> SellingModeScreen(management, onNavigateToManagement = { requestMove(3) }, isActive = pager.currentPage == 2)
                    3 -> ManagementModeScreen(management, onBack = { move(2) }, onBottomBarVisibleChange = { bottomBarVisible = it }, isActive = pager.currentPage == 3)
                }
            }
        }
        if (showMoveToManagementConfirm) AlertDialog(
            onDismissRequest = { showMoveToManagementConfirm = false },
            title = { Text("견적 진행 중") },
            text = { Text("현재 장바구니에 담긴 견적 내용이 있습니다.\n견적서를 취소하고 농원·묘목으로 이동할까요?") },
            confirmButton = { TextButton(onClick = {
                management.resetEstimateSession(); showMoveToManagementConfirm = false; move(3)
            }) { Text("취소 후 이동") } },
            dismissButton = { TextButton(onClick = { showMoveToManagementConfirm = false }) { Text("계속 견적") } }
        )
    }
}
