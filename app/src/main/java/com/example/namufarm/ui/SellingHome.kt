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
    onDeleteRequest: (String) -> Unit = {},
    onDeleteQuote: (com.example.namufarm.data.QuoteEntity) -> Unit = {},
    onHideSale: (OrderEntity) -> Unit = {},
    workbenchSignal: Int = 0
) {
    val listState = rememberLazyListState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var deletion by remember { mutableStateOf<WorkDeleteTarget?>(null) }
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
    val newRequests = activeRequests.filter {
        it.status == "신규" && state.quotesByRequest[it.requestId] == null
    }.sortedByDescending { it.sequence }
    val workRequests = activeRequests.filterNot { request ->
        newRequests.any { it.requestId == request.requestId }
    }.sortedByDescending { it.sequence }
    val linkedNumbers = webRequests.mapNotNull { state.quotesByRequest[it.requestId]?.quoteNumber }.toSet()
    val localQuotes = state.allQuotes.filter { it.quoteNumber !in linkedNumbers && it.quoteNumber !in paidQuoteNumbers }
    val workCount = workRequests.size + localQuotes.size
    fun matches(value: String) = query.isBlank() || value.contains(query.trim(), ignoreCase = true)

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
                            Text("견적 · 판매 관리", style = MaterialTheme.typography.labelSmall)
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
                    TextButton(onClick = onManage,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("설정")
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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "sync") {
                Button(onClick = onSync, enabled = !webBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Outlined.CloudSync, null)
                    Spacer(Modifier.width(9.dp))
                    Text(if (webBusy) "견적 요청 가져오는 중…" else "견적 요청 동기화",
                        style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "tabs") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(
                        Triple("신규 요청", newRequests.size, Icons.Outlined.Inbox),
                        Triple("견적 작업대", workCount, Icons.Outlined.Description)
                    ).forEachIndexed { index, (title, count, icon) ->
                        Surface(
                            onClick = { selectedTab = index; query = "" },
                            modifier = Modifier.weight(1f).heightIn(min = 91.dp),
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
                                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text("${count}건", style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            if (selectedTab != 2) {
                item(key = "quote-search") {
                    OutlinedTextField(query, { query = it }, singleLine = true,
                        placeholder = { Text("고객명 · 전화번호 · 묘목 검색") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier = Modifier.fillMaxWidth())
                }
            }
            if (selectedTab == 0) {
                item(key = "new-header") {
                    NamuSectionTitle("신규 견적 요청", "목록을 누르면 품목별 단가를 입력할 수 있어요.",
                        trailing = { TextButton(onClick = onQuickQuote) { Text("+ 직접 견적") } })
                }
                val visible = newRequests.filter {
                    matches(it.customerName) || matches(it.customerPhone) || matches(it.itemsJson)
                }
                if (visible.isEmpty()) item {
                    NamuEmptyState("새 견적 요청이 없어요", "위 동기화 버튼으로 홈페이지 요청을 가져오세요.")
                }
                items(visible, key = { "new-${it.requestId}" }) { request ->
                    RequestWorkCard(request, "신규", "가격 입력 · 견적확정",
                        onDelete = { deletion = WorkDeleteTarget("request", request.requestId, request.customerName) }
                    ) { onRequest(request) }
                }
            } else if (selectedTab == 1) {
                item(key = "work-header") {
                    NamuSectionTitle("견적 작업대", "확정한 견적 확인 · 판매 및 배송은 나피 PC에서 처리")
                }
                val visibleRequests = workRequests.filter {
                    matches(it.customerName) || matches(it.customerPhone) || matches(it.itemsJson)
                }
                val visibleQuotes = localQuotes.filter {
                    matches(it.customerPhone) || matches(it.itemSummary)
                }.sortedByDescending { it.saveDate }
                if (visibleRequests.isEmpty() && visibleQuotes.isEmpty()) item {
                    NamuEmptyState("작업할 견적이 없어요", "신규 요청에서 단가를 입력하고 견적확정해 주세요.")
                }
                items(visibleRequests, key = { "work-${it.requestId}" }) { request ->
                    val quote = state.quotesByRequest[request.requestId]
                    val status = "견적확정" // Sending is an action, not another work stage.
                    RequestWorkCard(request, status,
                        if (quote == null) "견적 가격 확인" else "견적 확인",
                        onDelete = { deletion = WorkDeleteTarget("request", request.requestId, request.customerName) }
                    ) {
                        if (quote == null) onRequest(request) else onSavedQuote(quote)
                    }
                }
                items(visibleQuotes, key = { "quote-${it.quoteNumber}" }) { quote ->
                    OutlinedCard(onClick = { onSavedQuote(quote) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(quote.customerPhone, modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                NamuBadge("견적확정")
                            }
                            Text("${quote.totalQty}주 · ${money(quote.totalPrice.toLong() + state.quoteTax(quote.quoteNumber))}")
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("견적 확인", color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = {
                                    deletion = WorkDeleteTarget("quote", quote.quoteNumber, quote.customerPhone, quote = quote)
                                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }

            }
        }
    }
    deletion?.let { target ->
        val sale = target.kind == "sale"
        AlertDialog(
            onDismissRequest = { deletion = null },
            title = { Text(if (sale) "판매 목록에서 삭제할까요?" else "견적을 삭제할까요?") },
            text = {
                Text(if (sale)
                    "${target.label} 판매 기록을 목록에서 숨깁니다. 구글시트에 숨김 표시를 동기화하며 매출 원장은 보존합니다."
                else if (target.kind == "request")
                    "${target.label}님의 견적을 삭제 처리합니다. 온라인 요청은 구글시트에도 종료 처리되고 연결된 미주문 견적은 함께 제거됩니다."
                else "${target.label} 견적을 앱에서 삭제합니다. 로컬 수기 견적은 구글시트에 저장되지 않습니다.")
            },
            confirmButton = {
                Button(onClick = {
                    when (target.kind) {
                        "request" -> onDeleteRequest(target.id)
                        "quote" -> target.quote?.let(onDeleteQuote)
                        "sale" -> target.order?.let(onHideSale)
                    }
                    deletion = null
                }) { Text(if (sale) "삭제 · 원장 보존" else "삭제하기") }
            },
            dismissButton = {
                TextButton(onClick = { deletion = null }) { Text("취소") }
            }
        )
    }
}

private data class WorkDeleteTarget(
    val kind: String, val id: String, val label: String,
    val quote: com.example.namufarm.data.QuoteEntity? = null, val order: OrderEntity? = null
)

@Composable
private fun RequestWorkCard(
    request: com.example.namufarm.data.WebQuoteRequestEntity,
    status: String,
    action: String,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(request.customerName, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                NamuBadge(status, manual = status == "신규")
            }
            val items = remember(request.itemsJson) {
                runCatching {
                    com.google.gson.Gson().fromJson(
                        request.itemsJson, Array<com.example.namufarm.data.WebQuoteItem>::class.java
                    ).toList()
                }.getOrDefault(emptyList())
            }
            items.take(2).forEach { row ->
                Text("${row.species} · ${row.specification.ifBlank { "규격 상담" }} · ${row.quantity}주",
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (items.size > 2) Text("외 ${items.size - 2}개 품목",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${request.customerPhone} · ${requestDateLabel(request.createdAt)}",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(action, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
                Text("삭제", color = MaterialTheme.colorScheme.error)
            }
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
