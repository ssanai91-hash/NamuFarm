package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.SellingDisplayRow
import com.example.namufarm.data.OrderEntity
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
    onSalesManagement: () -> Unit,
    onSyncOrders: () -> Unit,
    onTodayOrders: () -> Unit,
    onOrder: (OrderEntity) -> Unit,
    onCart: () -> Unit,
    onClearCart: () -> Unit,
    onAddToCart: (SellingDisplayRow, Int) -> Unit,
    onCallAssist: () -> Unit = {},
    onWebInbox: () -> Unit = {},
    webUnread: Int = 0,
    webRequests: List<com.example.namufarm.data.WebQuoteRequestEntity> = emptyList(),
    webBusy: Boolean = false,
    onRequest: (com.example.namufarm.data.WebQuoteRequestEntity) -> Unit = {},
    onSavedQuote: (com.example.namufarm.data.QuoteEntity) -> Unit = {},
    workbenchSignal: Int = 0
) {
    val listState = rememberLazyListState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(workbenchSignal) {
        if (workbenchSignal > 0) selectedTab = 1
    }
    LaunchedEffect(selectedTab) { listState.scrollToItem(0) }

    // Local quotes remain in Room; these are read-only work-list views.
    // Android 1차 버전: 서버 견적만 조회·확정하고 주문/판매장에는 접근하지 않는다.
    val paidRequestIds = state.allOrders.map { it.sourceRequestId }.filter(String::isNotBlank).toSet()
    val paidQuoteNumbers = state.allOrders.map { it.sourceQuoteNumber }.filter(String::isNotBlank).toSet()
    val activeRequests = webRequests.filter {
        it.requestId !in paidRequestIds &&
            it.status !in setOf("입금확인", "구매확정", "배송완료", "판매완료", "구매취소", "주문취소", "보관종료")
    }
    // The badge is the count still marked 신규 in the server's 견적요청 tab,
    // not the number of locally unread items or the number of saved drafts.
    val newRequests = activeRequests.filter { it.status == "신규" }
        .sortedByDescending { it.sequence }
    val workRequests = activeRequests.filterNot { request ->
        newRequests.any { it.requestId == request.requestId }
    }.sortedByDescending { it.sequence }
    val linkedNumbers = webRequests.mapNotNull { state.quotesByRequest[it.requestId]?.quoteNumber }.toSet()
    val localQuotes = state.allQuotes.filter { it.quoteNumber !in linkedNumbers && it.quoteNumber !in paidQuoteNumbers }
    val workCount = workRequests.size + localQuotes.size

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
                            Text("묘목 견적 관리", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    TextButton(onClick = onManage,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        Icon(Icons.Outlined.Settings, "기기 연결", Modifier.size(23.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("기기 연결", style = MaterialTheme.typography.labelLarge)
                    }
                }
            )
        },
        bottomBar = {
            if (state.estimateCart.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("견적 바구니 · ${state.estimateCart.size}품목", Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = onClearCart) { Text("비우기") }
                        }
                        Button(onClick = onCart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Icon(Icons.Outlined.ShoppingBag, null)
                            Spacer(Modifier.width(8.dp))
                            Text("견적 확인", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onSync, enabled = !webBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Outlined.CloudSync, null)
                Spacer(Modifier.width(9.dp))
                Text(if (webBusy) "견적 요청 가져오는 중…" else "견적 요청 가져오기",
                    style = MaterialTheme.typography.titleMedium)
            }
            // Keep both work queues inside a single visual group.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    listOf(
                        Triple("신규 요청", newRequests.size, Icons.Outlined.Inbox),
                        Triple("견적확정", workCount, Icons.Outlined.Description)
                    ).forEachIndexed { index, (title, count, icon) ->
                        Surface(
                            onClick = { selectedTab = index },
                            modifier = Modifier.weight(1f).heightIn(min = 104.dp),
                            shape = MaterialTheme.shapes.large,
                            color = if (selectedTab == index) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(if (selectedTab == index) 2.dp else 1.dp,
                                if (selectedTab == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(Modifier.fillMaxSize().padding(vertical = 11.dp, horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    if (index == 0 && count > 0) Badge(modifier = Modifier.sizeIn(minWidth = 30.dp, minHeight = 30.dp)) {
                                        Text(count.toString(), style = MaterialTheme.typography.titleSmall)
                                    }
                                }
                                Text("${count}건", style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            // Distinct list pane stays visible even when no quotes are available.
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "list-title") {
                        Text("목록", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
            if (selectedTab == 0) {
                val visible = newRequests
                if (visible.isNotEmpty()) item(key = "new-columns") { QuoteRowHeader() }
                if (visible.isEmpty()) item {
                    NamuEmptyState("새 견적 요청이 없어요", "견적 요청 가져오기를 눌러 확인하세요.")
                }
                items(visible, key = { "new-${it.requestId}" }) { request ->
                    RequestWorkCard(request) {
                        state.quotesByRequest[request.requestId]?.let(onSavedQuote)
                            ?: onRequest(request)
                    }
                }
            } else if (selectedTab == 1) {
                val visibleRequests = workRequests
                val visibleQuotes = localQuotes.sortedByDescending { it.saveDate }
                if (visibleRequests.isNotEmpty() || visibleQuotes.isNotEmpty())
                    item(key = "confirmed-columns") { QuoteRowHeader() }
                if (visibleRequests.isEmpty() && visibleQuotes.isEmpty()) item {
                    NamuEmptyState("작업할 견적이 없어요", "신규 요청에서 단가를 입력하고 견적확정해 주세요.")
                }
                items(visibleRequests, key = { "work-${it.requestId}" }) { request ->
                    val quote = state.quotesByRequest[request.requestId]
                    RequestWorkCard(request) {
                        if (quote == null) onRequest(request) else onSavedQuote(quote)
                    }
                }
                items(visibleQuotes, key = { "quote-${it.quoteNumber}" }) { quote ->
                    OutlinedCard(onClick = { onSavedQuote(quote) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(quote.quoteNumber.takeLast(9), modifier = Modifier.weight(0.34f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text("수기 견적", modifier = Modifier.weight(0.24f),
                                maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            Text(quote.customerPhone, modifier = Modifier.weight(0.42f),
                                maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
                }
            }
        }
    }
}
 
@Composable
private fun QuoteRowHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("견적번호", modifier = Modifier.weight(0.34f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("이름", modifier = Modifier.weight(0.24f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("전화번호", modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RequestWorkCard(
    request: com.example.namufarm.data.WebQuoteRequestEntity,
    onClick: () -> Unit
) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(request.receiptNumber.takeLast(9).ifBlank { "번호 확인" },
                modifier = Modifier.weight(0.34f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(request.customerName, modifier = Modifier.weight(0.24f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(request.customerPhone, modifier = Modifier.weight(0.42f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
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

internal fun quoteRequestStatus(request: com.example.namufarm.data.WebQuoteRequestEntity, state: ManagementUiState): String = when {
    state.allOrders.any { it.sourceRequestId == request.requestId } ||
        request.status in setOf("입금확인", "구매확정", "배송완료", "판매완료", "구매취소", "주문취소") -> "주문 등록"
    (state.quotesByRequest[request.requestId]?.sentAt ?: 0) > 0 || request.status == "견적발송완료" -> "발송 완료"
    request.status == "견적확정" -> "견적확정"
    state.quotesByRequest[request.requestId] != null -> "작성 중"
    else -> "신규"
}

internal fun filterQuoteRequests(requests: List<com.example.namufarm.data.WebQuoteRequestEntity>, state: ManagementUiState, query: String, filter: String): List<com.example.namufarm.data.WebQuoteRequestEntity> {
    val term = query.trim()
    return requests.filter { request ->
        (filter == "전체" || quoteRequestStatus(request, state) == filter) &&
            (term.isEmpty() || listOf(request.customerName, request.customerPhone, request.region, request.itemsJson, request.recipientName, request.requestId).any { it.contains(term, true) } ||
                (term.any(Char::isDigit) && term.all { it.isDigit() || it in "- ()" } && request.customerPhone.filter(Char::isDigit).contains(term.filter(Char::isDigit))))
    }.sortedByDescending { it.sequence }
}

internal fun requestDateLabel(value: String): String = runCatching {
    val source = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val date = source.parse(value.take(19)) ?: error("date")
    java.text.SimpleDateFormat("MM.dd HH:mm", java.util.Locale.KOREA).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }.format(date)
}.getOrDefault(value.take(16).replace('T', ' '))

internal fun ManagementUiState.quoteTax(number: String): Long = quoteDocuments.firstOrNull { it.quoteNumber == number }?.let {
    com.example.namufarm.quotation.QuoteDocument.read(it.snapshotJson).taxAmount
} ?: 0L
