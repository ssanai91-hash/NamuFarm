package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
    onSalesManagement: () -> Unit,
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
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("전체") }
    var section by rememberSaveable { mutableStateOf("home") }
    val paidRequestIds = state.allOrders.map { it.sourceRequestId }.filter(String::isNotBlank).toSet()
    val paidQuoteNumbers = state.allOrders.map { it.sourceQuoteNumber }.filter(String::isNotBlank).toSet()
    // Preserve all saved records; only split what belongs in each operational list.
    val activeRequests = webRequests.filter { it.requestId !in paidRequestIds &&
        it.status !in setOf("입금확인", "구매확정", "배송완료", "판매완료", "구매취소", "주문취소") }
    val visibleQuoteNumbers = webRequests.mapNotNull { state.quotesByRequest[it.requestId]?.quoteNumber }.toSet()
    val standaloneQuotes = state.allQuotes.filter { it.quoteNumber !in visibleQuoteNumbers && it.quoteNumber !in paidQuoteNumbers }
    val requests = filterQuoteRequests(activeRequests, state, query, filter)
    val totalQuotes = activeRequests.size + standaloneQuotes.size
    val newQuotes = activeRequests.count { quoteRequestStatus(it, state) == "신규" }
    val toSend = activeRequests.count { quoteRequestStatus(it, state) in setOf("견적확정", "작성 중") } +
        standaloneQuotes.count { it.sentAt == 0L }
    val awaitingPayment = activeRequests.count { quoteRequestStatus(it, state) == "발송 완료" } +
        standaloneQuotes.count { it.sentAt > 0L }
    val deliveryPending = state.allOrders.count { it.status in setOf("구매확정", "입금확인") }

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
                            Text("판매자 작업실", style = MaterialTheme.typography.labelSmall)
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

                    TextButton(onClick = onManage, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) {
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
            item(key = "management-cards") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    ManagementCard("견적 관리함", totalQuotes, "신규 ${newQuotes} · 발송대기 ${toSend} · 입금대기 ${awaitingPayment}",
                        Icons.Outlined.Description, Modifier.weight(1f), selected = section == "quotes") {
                        section = "quotes"
                    }
                    ManagementCard("판매 관리함", state.allOrders.size, "배송 대기 ${deliveryPending}건",
                        Icons.AutoMirrored.Outlined.ReceiptLong, Modifier.weight(1f)) { onSalesManagement() }
                }
            }
            if (section == "home") {
                item(key = "quick-actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeAction("빠른 견적", "수기 작성", Icons.Outlined.EditNote, Modifier.weight(1f), onQuickQuote)
                        HomeAction("요청 동기화", if (webBusy) "가져오는 중…" else "구글시트", Icons.Outlined.CloudSync,
                            Modifier.weight(1f), onSync)
                    }
                }
                item(key = "home-guide") {
                    Text("견적은 입금확인 전까지 견적 관리함에서, 입금확인된 주문은 판매 관리함에서 처리해요.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                item(key = "quote-actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { section = "home"; filter = "전체"; query = "" }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                            Spacer(Modifier.width(6.dp))
                            Text("메인으로")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HomeAction("빠른 견적", "수기 작성", Icons.Outlined.EditNote, Modifier.weight(1f), onQuickQuote)
                            HomeAction("오늘 견적", "${state.todayQuotes.size}건", Icons.Outlined.Description,
                                Modifier.weight(1f), onTodayQuotes)
                        }
                    }
                }
            item(key = "web-inbox") {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("온라인 견적 요청", style = MaterialTheme.typography.titleMedium)
                                Text("신규 ${newQuotes}건 · 발송 대기 ${toSend}건", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Icon(Icons.Outlined.MarkEmailUnread, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Button(onClick = onSync, enabled = !webBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.CloudSync, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (webBusy) "구글시트에서 가져오는 중…" else "요청 동기화")
                        }
                        Text("견적 요청 ${activeRequests.size}건 · 미열람 ${webUnread}건", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item(key = "request-filter") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("고객명 · 전화번호 · 품목 검색") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "검색어 지우기") } },
                        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("전체", "신규", "작성 중", "견적확정", "발송 완료").forEach { label ->
                            FilterChip(filter == label, { filter = label }, { Text(label) })
                        }
                    }
                    NamuSectionTitle("견적 요청 ${requests.size}건", "견적확정과 발송완료까지 이곳에서 관리해요")
                }
            }
            if (webBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (requests.isEmpty()) item {
                NamuEmptyState(if (webRequests.isEmpty()) "접수된 요청을 가져와주세요" else "조건에 맞는 요청이 없어요",
                    if (webRequests.isEmpty()) "요청 동기화를 누르면 구글시트의 견적 요청이 표시됩니다." else "검색어나 진행 상태를 바꿔주세요.")
            }
            items(requests, key = { "request-${it.requestId}" }) { request ->
                val quote = state.quotesByRequest[request.requestId]
                val status = quoteRequestStatus(request, state)
                OutlinedCard(onClick = { if (quote != null) onSavedQuote(quote) else onRequest(request) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(request.customerName, style = MaterialTheme.typography.titleLarge)
                                Text(request.region, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            NamuBadge(status, manual = status == "신규")
                        }
                        val rows = remember(request.itemsJson) { runCatching { com.google.gson.Gson().fromJson(request.itemsJson, Array<com.example.namufarm.data.WebQuoteItem>::class.java).toList() }.getOrDefault(emptyList()) }
                        rows.take(2).forEach { row ->
                            Row {
                                Text("${row.species} · ${row.specification}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${row.quantity}주", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (rows.size > 2) Text("외 ${rows.size - 2}개 품목", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(request.customerPhone, style = MaterialTheme.typography.bodySmall)
                                Text("${requestDateLabel(request.createdAt)} · ${when (request.replyChannel) { "email" -> "메일 희망"; "both" -> "문자 + 메일 희망"; else -> "문자 희망" }}${if (request.formalQuoteRequested) " · 공식 견적서" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (quote == null) "견적 작성" else money(quote.totalPrice.toLong() + state.quoteTax(quote.quoteNumber)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item { NamuSectionTitle("저장한 견적", "수기 견적과 온라인 요청을 함께 관리해요") }
            items(standaloneQuotes.filter { (query.isBlank() || it.customerPhone.contains(query.trim()) || it.itemSummary.contains(query.trim(), true)) && (filter == "전체" || if (it.sentAt > 0L) filter == "발송 완료" else filter == "작성 중") }, key = { "quote-${it.quoteNumber}" }) { quote ->
                OutlinedCard(onClick = { onSavedQuote(quote) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        state.quotesByRequest.entries.firstOrNull { it.value.quoteNumber == quote.quoteNumber }?.key?.let { Text("접수번호 $it", style = MaterialTheme.typography.labelMedium) }
                        Text(quote.customerPhone, fontWeight = FontWeight.Bold)
                        Text(com.example.namufarm.domain.order.OrderSummaryCodec.parseStrict(quote.itemSummary).joinToString(" · ") { "${it.speciesName} ${it.orderQty}주" })
                        Text("${quote.totalQty}주 · ${money(quote.totalPrice.toLong() + state.quoteTax(quote.quoteNumber))} · ${if (quote.sentAt > 0) "발송 완료" else "작성 중"}")
                    }
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
private fun ManagementCard(
    title: String, count: Int, detail: String, icon: ImageVector, modifier: Modifier,
    selected: Boolean = false, onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${count}건", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
