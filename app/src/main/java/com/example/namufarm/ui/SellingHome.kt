package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.SellingDisplayRow
import com.example.namufarm.domain.order.OrderLineSource
import com.example.namufarm.ui.components.*
import com.example.namufarm.viewmodel.ManagementUiState

/** Presentation only: Room-backed state and business actions stay in the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SellingHome(
    state: ManagementUiState,
    snackbarHostState: SnackbarHostState,
    onSync: () -> Unit,
    onManage: () -> Unit,
    onQuickQuote: () -> Unit,
    onTodayQuotes: () -> Unit,
    onTodayOrders: () -> Unit,
    onCart: () -> Unit,
    onClearCart: () -> Unit,
    onAddToCart: (SellingDisplayRow, Int) -> Unit,
    onCallAssist: () -> Unit = {},
    onWebInbox: () -> Unit = {},
    webUnread: Int = 0,
    webRequests: List<com.example.namufarm.data.WebQuoteRequestEntity> = emptyList(),
    webBusy: Boolean = false,
    onRequest: (com.example.namufarm.data.WebQuoteRequestEntity) -> Unit = {},
    onSavedQuote: (com.example.namufarm.data.QuoteEntity) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val visibleQuoteNumbers = webRequests.mapNotNull { state.quotesByRequest[it.requestId]?.quoteNumber }.toSet()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Park, null, Modifier.size(28.dp))
                        Column {
                            Text("나무팜", style = MaterialTheme.typography.titleLarge)
                            Text("NAMUFARM", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = onCallAssist) { Icon(Icons.Outlined.PhoneInTalk, "전화 고객 연결") }
                    IconButton(onClick = onSync) { Icon(Icons.Outlined.CloudSync, "요청·주문 동기화") }
                    TextButton(onClick = onManage, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("연결 설정")
                    }
                }
            )
        },
        bottomBar = {
            if (state.estimateCart.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("견적 바구니 · ${state.estimateCart.size}품목", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = onClearCart) { Text("비우기", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Button(onClick = onCart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(money(state.estimateCart.sumOf { it.item.retailPrice.toLong() * it.orderQuantity }), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text("견적 확인")
                            Icon(Icons.Outlined.ChevronRight, null)
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "work") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeAction("빠른 견적", "수기 작성", Icons.Outlined.EditNote, Modifier.weight(1f), onQuickQuote)
                    HomeAction("오늘 견적", "${state.todayQuotes.size}건", Icons.Outlined.Description, Modifier.weight(1f), onTodayQuotes)
                    HomeAction("오늘 주문", "${state.todayOrders.size}건", Icons.AutoMirrored.Outlined.ReceiptLong, Modifier.weight(1f), onTodayOrders)
                }
            }
            item(key = "web-inbox") {
                HomeAction("온라인 견적 요청함", if (webBusy) "가져오는 중…" else "${webRequests.size}건 · 눌러서 동기화", Icons.Outlined.MarkEmailUnread, Modifier.fillMaxWidth(), onWebInbox)
            }
            if (webBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (webRequests.isEmpty()) item { NamuEmptyState("온라인 견적 요청을 기다리고 있어요", "위 버튼을 누르면 구글시트에 접수된 요청을 가져옵니다.") }
            items(webRequests, key = { "request-${it.requestId}" }) { request ->
                val quote = state.quotesByRequest[request.requestId]
                val order = state.allOrders.firstOrNull { it.sourceRequestId == request.requestId }
                OutlinedCard(onClick = { if (quote != null) onSavedQuote(quote) else onRequest(request) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("접수번호 ${request.requestId}", style = MaterialTheme.typography.labelMedium)
                        Text("${request.customerName} · ${request.customerPhone}", fontWeight = FontWeight.Bold)
                        val rows = remember(request.itemsJson) { com.google.gson.Gson().fromJson(request.itemsJson, Array<com.example.namufarm.data.WebQuoteItem>::class.java).toList() }
                        rows.forEach { Text("${it.species} · ${it.specification} · ${it.quantity}주") }
                        Text(when { (order?.paidAt ?: 0) > 0 -> "입금 완료 · ${order?.status}"; (quote?.sentAt ?: 0) > 0 -> "견적 발송 완료"; quote != null -> "견적 작성 중 · 이어서 열기"; else -> "새 요청 · 수동 견적 작성" }, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { NamuSectionTitle("저장한 견적", "발송 확인 후 오늘 견적에 집계됩니다") }
            items(state.allQuotes.filter { it.quoteNumber !in visibleQuoteNumbers }, key = { "quote-${it.quoteNumber}" }) { quote ->
                OutlinedCard(onClick = { onSavedQuote(quote) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        state.quotesByRequest.entries.firstOrNull { it.value.quoteNumber == quote.quoteNumber }?.key?.let { Text("접수번호 $it", style = MaterialTheme.typography.labelMedium) }
                        Text(quote.customerPhone, fontWeight = FontWeight.Bold)
                        Text(com.example.namufarm.domain.order.OrderSummaryCodec.parseStrict(quote.itemSummary).joinToString(" · ") { "${it.speciesName} ${it.orderQty}주" })
                        Text("${quote.totalQty}주 · ${money(quote.totalPrice.toLong())} · ${if (quote.sentAt > 0) "발송 완료" else "작성 중"}")
                    }
                }
            }
            item(key = "offline") {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PhonelinkRing, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("인터넷 없이도 견적을 저장할 수 있어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HomeAction(title: String, value: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun filterSellingInventory(items: List<SellingDisplayRow>, query: String, initial: String): List<SellingDisplayRow> {
    val term = query.trim()
    return items.filter {
        it.quantity > 0 &&
            (initial == "전체" || isSellingItemInChosung(it.speciesName, it.chosung, initial)) &&
            (term.isEmpty() || it.speciesName.contains(term, ignoreCase = true) || it.farmName.contains(term, ignoreCase = true))
    }
}

internal fun sellingRowKey(row: SellingDisplayRow): String =
    listOf(row.farmId.toString(), row.speciesId.toString(), row.quality, row.age, row.size, row.plantType)
        .joinToString("") { "${it.length}:$it" }
