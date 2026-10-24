package com.example.namufarm.quotemode

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.ui.ManagementModeScreen
import com.example.namufarm.ui.SellingModeScreen
import com.example.namufarm.viewmodel.ManagementViewModel
import com.example.namufarm.viewmodel.CustomerInquiryViewModel
import kotlinx.coroutines.launch

/** Original screen functions stay byte-for-byte unchanged inside the added mode navigation. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InitialAppModes(management: ManagementViewModel, quote: InitialQuoteViewModel,
                    inquiry: CustomerInquiryViewModel) {
    val pager = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val inbox by quote.uiState.collectAsState()
    val inquiryState by inquiry.state.collectAsState()
    val colors = listOf(Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFEF6C00), Color(0xFF2E7D32))
    val labels = listOf("견적", "문의처리", "매매", "농원·묘목")
    val icons = listOf(Icons.Outlined.ReceiptLong, Icons.AutoMirrored.Outlined.Chat,
        Icons.Outlined.Handshake, Icons.Outlined.Park)
    val stackedTabs = LocalDensity.current.fontScale > 1.15f
    fun move(page: Int) { scope.launch { pager.animateScrollToPage(page) } }
    BackHandler(enabled = pager.currentPage != 0) { move(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding().consumeWindowInsets(WindowInsets.statusBars)) {
        Surface(color = Color.White, shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                labels.forEachIndexed { index, label ->
                    val selected = pager.currentPage == index
                    val color = if (selected) colors[index] else Color(0xFF667085)
                    Surface(color = if (selected) color.copy(alpha = 0.06f) else Color.White,
                        shape = RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp),
                        border = BorderStroke(if (selected) 1.5.dp else 0.dp, if (selected) color else Color.Transparent),
                        modifier = Modifier.weight(1f)) {
                        if (stackedTabs) {
                            Column(Modifier.selectable(selected = selected, role = Role.Tab, onClick = { move(index) })
                                .heightIn(min = 56.dp).padding(horizontal = 3.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(icons[index], null, tint = color, modifier = Modifier.size(18.dp))
                                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
                            }
                        } else {
                            Row(Modifier.selectable(selected = selected, role = Role.Tab, onClick = { move(index) })
                                .heightIn(min = 50.dp).padding(horizontal = 3.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(icons[index], null, tint = color, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f),
            userScrollEnabled = inbox.editingRequest == null && !inbox.busy && !inquiryState.busy) { page ->
            when (page) {
                0 -> MaterialTheme(colorScheme = lightColorScheme(primary = colors[0],
                    secondary = colors[3], tertiary = colors[2], background = Color(0xFFF2F4F7),
                    surface = Color.White)) {
                    InitialQuoteScreen(quote, quote.connection, inbox,
                        onSync = quote::refresh, onRead = quote::read, onDelete = quote::close)
                }
                1 -> MaterialTheme(colorScheme = lightColorScheme(primary = InquiryDesign.green,
                    secondary = InquiryDesign.blue, tertiary = InquiryDesign.coral,
                    primaryContainer = InquiryDesign.mint, secondaryContainer = InquiryDesign.sky,
                    onSurface = InquiryDesign.ink, outline = InquiryDesign.line,
                    background = InquiryDesign.canvas, surface = Color.White)) {
                    CustomerInquiryScreen(inquiry)
                }
                2 -> SellingModeScreen(management, onNavigateToManagement = { move(3) })
                3 -> ManagementModeScreen(management, onBack = { move(2) })
            }
        }
    }
}
