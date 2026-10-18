package com.example.namufarm.quotemode

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.ui.ManagementModeScreen
import com.example.namufarm.ui.SellingModeScreen
import com.example.namufarm.viewmodel.ManagementViewModel
import kotlinx.coroutines.launch

/** Original screen functions stay byte-for-byte unchanged inside the added mode navigation. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InitialAppModes(management: ManagementViewModel, quote: InitialQuoteViewModel) {
    val pager = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val inbox by quote.uiState.collectAsState()
    val colors = listOf(Color(0xFF1565C0), Color(0xFFEF6C00), Color(0xFF2E7D32))
    val labels = listOf("견적", "매매", "농원·묘목")
    fun move(page: Int) { scope.launch { pager.animateScrollToPage(page) } }
    BackHandler(enabled = pager.currentPage != 0) { move(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding().consumeWindowInsets(WindowInsets.statusBars)) {
        Surface(color = Color.White, shadowElevation = 2.dp) {
            TabRow(selectedTabIndex = pager.currentPage, containerColor = Color.White,
                contentColor = colors[pager.currentPage]) {
                labels.forEachIndexed { index, label ->
                    Tab(selected = pager.currentPage == index, onClick = { move(index) },
                        modifier = Modifier.height(46.dp),
                        selectedContentColor = colors[index], unselectedContentColor = Color(0xFF667085),
                        text = { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) })
                }
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f),
            userScrollEnabled = inbox.editingRequest == null && !inbox.busy) { page ->
            when (page) {
                0 -> MaterialTheme(colorScheme = lightColorScheme(primary = colors[0],
                    secondary = colors[2], tertiary = colors[1], background = Color(0xFFF2F4F7),
                    surface = Color.White)) {
                    InitialQuoteScreen(quote, quote.connection, inbox,
                        onSync = quote::refresh, onRead = quote::read, onDelete = quote::close)
                }
                1 -> SellingModeScreen(management, onNavigateToManagement = { move(2) })
                2 -> ManagementModeScreen(management, onBack = { move(1) })
            }
        }
    }
}
