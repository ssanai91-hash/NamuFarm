package com.example.namufarm.quotemode

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
import com.example.namufarm.quotemode.components.NamuConcept

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun InitialAppModes(management: ManagementViewModel, quote: InitialQuoteViewModel,
                    inquiry: CustomerInquiryViewModel, backupController: com.example.namufarm.data.DataBackupController? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
    val concepts = listOf(NamuConcept.QUOTE, NamuConcept.INQUIRY, NamuConcept.SALE, NamuConcept.SPECIES)
    val swipeAllowed = bottomBarVisible && inbox.editingRequest == null && !inbox.busy && !inquiryState.busy
    val stripAllowed = swipeAllowed && LocalWindowInfo.current.isWindowFocused &&
        WindowInsets.ime.getBottom(LocalDensity.current) == 0 && !showMoveToManagementConfirm
    var modeStripVisible by remember { mutableStateOf(false) }
    LaunchedEffect(pager.isScrollInProgress, stripAllowed) {
        when {
            !stripAllowed -> modeStripVisible = false
            pager.isScrollInProgress -> modeStripVisible = true
            else -> { delay(300); modeStripVisible = false }
        }
    }
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
                            modifier = Modifier.height(if (LocalDensity.current.fontScale > 1.15f) 116.dp else 92.dp), windowInsets = WindowInsets(0, 0, 0, 0)) {
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
                                            contentColor = Color.White) { Text("$count", fontSize = 14.sp) } }) {
                                            Icon(icons[index], null, Modifier.size(24.dp))
                                        }
                                    }, label = { Text(if (index == 3) "농원·\n묘목" else label, fontSize = 15.sp, maxLines = 2,
                                        textAlign = TextAlign.Center,
                                        fontWeight = if (pager.currentPage == index) FontWeight.Bold else FontWeight.Normal) },
                                    // Each menu keeps its own colour (견적 살구·문의 파랑·매매 분홍·농원 초록); the open one is strongest.
                                    colors = run {
                                        val visual = concepts[index].visual
                                        NavigationBarItemDefaults.colors(indicatorColor = visual.background,
                                            selectedIconColor = visual.ink, selectedTextColor = visual.ink,
                                            unselectedIconColor = visual.ink.copy(alpha = 0.55f), unselectedTextColor = NamuColors.muted)
                                    })
                            }
                        }
                    }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(padding)
                .statusBarsPadding().consumeWindowInsets(WindowInsets.statusBars),
                userScrollEnabled = bottomBarVisible && inbox.editingRequest == null && !inbox.busy && !inquiryState.busy) { page ->
                when (page) {
                    0 -> InitialQuoteScreen(quote, quote.connection, inbox,
                        onSync = quote::refresh, onRead = quote::read, onDelete = quote::close,
                        newOrderNumber = { management.nextWebOrderNumber(context) },
                        onPaidOrder = management::createPaidWebOrder)
                    1 -> CustomerInquiryScreen(inquiry)
                    2 -> SellingModeScreen(management, onNavigateToManagement = { requestMove(3) }, isActive = pager.currentPage == 2)
                    3 -> ManagementModeScreen(management, onBack = { move(2) }, onBottomBarVisibleChange = { bottomBarVisible = it }, isActive = pager.currentPage == 3, backupController = backupController)
                }
            }
            // Plain layout/text: overlay has no click or pointer handlers and never shifts page content.
            if (stripAllowed) AnimatedVisibility(
                visible = modeStripVisible,
                enter = fadeIn(tween(120)), exit = fadeOut(tween(180)),
                modifier = Modifier.align(Alignment.TopCenter).zIndex(1f).statusBarsPadding()
                    .padding(top = padding.calculateTopPadding(), start = 8.dp, end = 8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    labels.forEachIndexed { index, title ->
                        val active = pager.currentPage == index
                        val visual = concepts[index].visual
                        val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        Column(Modifier.weight(1f).shadow(2.dp, shape)
                            .background(if (active) visual.background else Color.White, shape)
                            .padding(horizontal = 2.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(icons[index], null, Modifier.size(20.dp), tint = if (active) visual.ink else NamuColors.muted)
                            Text(if (index == 3) "농원·\n묘목" else title, fontSize = 14.sp,
                                textAlign = TextAlign.Center, maxLines = 2,
                                color = if (active) visual.ink else NamuColors.muted,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
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
