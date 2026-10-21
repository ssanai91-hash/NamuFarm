package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.quotation.*
import com.example.namufarm.phone.openSmsComposer
import com.example.namufarm.ui.components.NamuDialog
import com.example.namufarm.viewmodel.ManagementViewModel
import com.example.namufarm.viewmodel.WebInboxState
import com.example.namufarm.webrequests.WebRequestConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android only answers enquiries. Confirmed results have no selling, payment or delivery actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuoteOnlyScreen(viewModel: ManagementViewModel, connection: WebRequestConnection,
    inbox: WebInboxState, onSync: () -> Unit, onSettings: () -> Unit,
    onRead: (String) -> Unit, onDelete: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AndroidQuoteWorkflowStore(context) }
    val workflow = remember(store, connection) { QuoteWorkflow(store, connection::quoteAction, connection::confirmQuote,
        latestRequest = { connection.latestRequest(it.requestId) }) }
    val management by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var recordsReady by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf(emptyList<QuoteWorkflowRecord>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var quoteSearch by rememberSaveable { mutableStateOf("") }
    var quotePeriod by rememberSaveable { mutableStateOf("month") }
    var quoteFrom by rememberSaveable { mutableStateOf("") }
    var quoteTo by rememberSaveable { mutableStateOf("") }
    var quoteLimit by remember { mutableIntStateOf(50) }
    LaunchedEffect(quoteSearch, quotePeriod, quoteFrom, quoteTo) { quoteLimit = 50 }
    var result by remember { mutableStateOf<QuoteWorkflowRecord?>(null) }
    var legacyResult by remember { mutableStateOf<QuoteEntity?>(null) }
    var readyRequest by remember { mutableStateOf<WebQuoteRequestEntity?>(null) }
    val editing = management.editingRequest
    val deleting = inbox.deleteFeedback?.phase == "deleting"
    LaunchedEffect(management.message) {
        management.message?.let { message = it; viewModel.clearMessage() }
    }
    LaunchedEffect(workflow) {
        try { records = withContext(Dispatchers.IO) { workflow.records() } }
        catch (e: Exception) { message = "저장된 견적 기록 확인이 필요합니다: ${e.message}" }
        finally { recordsReady = true }
    }
    LaunchedEffect(inbox.deleteFeedback) {
        val feedback = inbox.deleteFeedback
        if (feedback?.phase == "success" && editing?.requestId == feedback.requestId) {
            viewModel.dismissManualQuote(); message = "견적요청을 삭제했습니다."
        }
        if (feedback?.phase == "error") message = "❌ ${feedback.error ?: "삭제하지 못했습니다."}"
    }
    val confirmed = records.filter { it.confirmedAt > 0 }.sortedByDescending { it.confirmedAt }
    val confirmedIds = confirmed.map { it.request.requestId }.toSet()
    LaunchedEffect(editing, recordsReady) {
        if (recordsReady && editing != null && (editing.status != "신규" || editing.requestId in confirmedIds)) {
            result = confirmed.firstOrNull { it.request.requestId == editing.requestId }
            if (result == null) legacyResult = management.quotesByRequest[editing.requestId]
            if (result == null && legacyResult == null) message = "이 휴대폰에 확정 결과가 없습니다. 견적을 확정한 기기에서 확인해주세요."
            selectedTab = 1
            viewModel.dismissManualQuote()
        }
    }
    LaunchedEffect(editing?.requestId, recordsReady) {
        readyRequest = null
        if (!recordsReady || editing == null || editing.status != "신규" || editing.requestId in confirmedIds) return@LaunchedEffect
        busy = true
        try {
            val saved = records.firstOrNull { it.request.requestId == editing.requestId }
            val current = if (saved?.confirmationPending == true) editing else
                withContext(Dispatchers.IO) { connection.latestRequest(editing.requestId) }
            onRead(editing.requestId) // Also publish the refreshed server cache to the list.
            if (current == null || current.status != "신규") {
                viewModel.dismissManualQuote(); message = "서버에서 이미 처리되었거나 삭제된 요청입니다."; onSync()
            } else { readyRequest = current; message = null }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            viewModel.dismissManualQuote(); message = "서버 요청을 가져오지 못했습니다: ${e.message}"
        } finally { busy = false }
    }
    val pending = records.filter { it.confirmationPending }.map { it.request }
    val requests = (pending + inbox.requests.filter { it.status == "신규" })
        .distinctBy { it.requestId }.filter { it.requestId !in confirmedIds }.sortedByDescending { it.sequence }
    // Preserve older Room records as historical results; never silently label them server-confirmed.
    val legacy = management.allQuotes.filter { quote ->
        quote.sentAt > 0 &&
        management.quotesByRequest.entries.none { it.value.quoteNumber == quote.quoteNumber && it.key in confirmedIds }
    }
    fun legacyCustomer(quote: QuoteEntity): String {
        val sourceId = management.quotesByRequest.entries
            .firstOrNull { it.value.quoteNumber == quote.quoteNumber }?.key
        return inbox.requests.firstOrNull { it.requestId == sourceId }?.customerName.orEmpty()
    }
    val filteredConfirmed = confirmed.filter { saved ->
        historyMatches(historyDay(saved.confirmedAt), quotePeriod, quoteFrom, quoteTo) &&
            historySearch(quoteSearch, saved.request.receiptNumber, saved.request.requestId,
                saved.draft.recipient.customerName, saved.draft.phone)
    }
    val filteredLegacy = legacy.filter { saved ->
        historyMatches(historyDay(saved.sentAt) ?: historyDate(saved.saveDate), quotePeriod, quoteFrom, quoteTo) &&
            historySearch(quoteSearch, saved.quoteNumber, legacyCustomer(saved), saved.customerPhone)
    }.sortedByDescending { it.sentAt }
    val shownConfirmed = filteredConfirmed.take(quoteLimit)
    val shownLegacy = filteredLegacy.take((quoteLimit - shownConfirmed.size).coerceAtLeast(0))
    val quoteCount = filteredConfirmed.size + filteredLegacy.size
    Scaffold(topBar = {
        TopAppBar(title = { Text("나무팜 · 견적 관리", fontWeight = FontWeight.Bold) }, actions = {
            TextButton(onClick = onSync, enabled = !busy && !inbox.busy && !deleting) { Text("↻ 가져오기") }
            TextButton(onClick = onSettings, enabled = !busy) { Text("연결·설정") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    label = { Text("견적요청 ${requests.size}") })
                FilterChip(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    label = { Text("확정견적 ${confirmed.size + legacy.size}") })
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (busy || inbox.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (selectedTab == 1) HistoryFilters(
                search = quoteSearch, onSearch = { quoteSearch = it }, period = quotePeriod,
                from = quoteFrom, to = quoteTo, count = quoteCount, basis = "확정일(이전 자료는 발송·저장일)",
                onApply = { period, from, to, _ -> quotePeriod = period; quoteFrom = from; quoteTo = to })
            Surface(Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        QuoteTableColumns("순번", "견적번호", "이름", "전화번호", header = true)
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        if (selectedTab == 0) {
                            if (requests.isEmpty()) item {
                                Text("새 견적요청이 없습니다. 가져오기로 서버 요청을 확인하세요.",
                                    Modifier.padding(12.dp))
                            }
                            itemsIndexed(requests, key = { _, request -> request.requestId }) { index, request ->
                                QuoteTableRow(index + 1, request.receiptNumber.ifBlank { request.requestId },
                                    request.customerName, request.customerPhone,
                                    pending = records.any { it.request.requestId == request.requestId && it.confirmationPending },
                                    enabled = !busy && !inbox.busy && !deleting) {
                                    viewModel.useWebRequestForQuote(request); message = null
                                }
                            }
                        } else {
                            if (quoteCount == 0) item {
                                Text("조건에 맞는 확정견적이 없습니다. 검색어나 기간을 바꿔보세요.", Modifier.padding(12.dp))
                            }
                            itemsIndexed(shownConfirmed, key = { _, saved -> saved.request.requestId }) { index, saved ->
                                QuoteTableRow(index + 1, saved.request.receiptNumber.ifBlank { saved.request.requestId },
                                    saved.draft.recipient.customerName, saved.draft.phone,
                                    detail = "견적확정 · ${workflowDate(saved.confirmedAt)}") { result = saved }
                            }
                            itemsIndexed(shownLegacy, key = { _, saved -> "legacy-${saved.quoteNumber}" }) { index, saved ->
                                QuoteTableRow(shownConfirmed.size + index + 1, saved.quoteNumber, legacyCustomer(saved), saved.customerPhone,
                                    detail = "이전 저장 견적 · ${saved.saveDate}") { legacyResult = saved }
                            }
                            if (quoteCount > quoteLimit) item {
                                TextButton(onClick = { quoteLimit += 50 }, modifier = Modifier.fillMaxWidth()) { Text("50건 더 보기") }
                            }
                        }
                    }
                }
            }
        }
    }
    readyRequest?.takeIf { editing?.requestId == it.requestId && recordsReady && it.requestId !in confirmedIds }?.let { request ->
        val saved = records.firstOrNull { it.request.requestId == request.requestId && sameServerQuoteRequest(it.request, request) }
        fun handleAction(draft: SellerQuoteDraft, action: QuoteDelivery, sentVia: String? = null) {
            if (busy) return
            busy = true
            message = if (action == QuoteDelivery.COMPLETE) "서버 견적확정 확인 중…" else "전송 화면 준비 중…"
            scope.launch {
                try {
                    when (action) {
                        QuoteDelivery.SMS, QuoteDelivery.EMAIL -> {
                            withContext(Dispatchers.IO) { workflow.prepareExternalDelivery(request, draft, action) }
                            val body = quoteWorkflowText(request, draft, action)
                            // Launch Android activities on the main thread; the seller sends from that app.
                            if (action == QuoteDelivery.SMS) {
                                openSmsComposer(context, draft.phone, body)?.let { error(it) }
                            } else {
                                QuoteReply.openTextEmail(context, draft.recipient.customerEmail.trim(), body)
                            }
                            message = "문자·메일 앱에서 실제 전송을 마친 뒤 ‘견적완료’를 눌러주세요."
                        }
                        QuoteDelivery.COMPLETE -> {
                            withContext(Dispatchers.IO) {
                                workflow.completeExternal(request, draft,
                                    sentVia ?: error("실제로 보낸 방법을 선택해주세요."))
                            }
                            viewModel.dismissManualQuote(); selectedTab = 1
                            message = "견적을 확정했습니다."; onSync()
                        }
                        else -> error("견적완료로 저장해주세요.")
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: ServerQuoteRequestChanged) {
                    viewModel.dismissManualQuote(); readyRequest = null
                    message = e.message; onSync()
                }
                catch (e: Exception) { message = "❌ ${e.message ?: "처리 결과를 확인하지 못했습니다. 다시 확인해주세요."}" }
                finally {
                    try { records = withContext(Dispatchers.IO) { workflow.records() } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = "❌ 저장된 견적 기록을 확인해주세요: ${e.message}" }
                    finally { busy = false }
                }
            }
        }
        ManualQuoteEditor(request, busy || inbox.busy || deleting, message,
            onDismiss = { if (!busy) { viewModel.dismissManualQuote(); message = null } },
            initialDraft = saved?.draft, draftKey = request.requestId,
            onDeleteRequest = if (saved?.confirmationPending == true) null else ({ onDelete(request.requestId) }),
            verifiedDelivery = saved?.delivery, verifyDelivery = true,
            onSave = { draft, action -> handleAction(draft, action) },
            onConfirmSent = { draft, method -> handleAction(draft, QuoteDelivery.COMPLETE, method) })
    }
    result?.let { QuoteWorkflowResultDialog(it) { result = null } }
    legacyResult?.let { saved ->
        NamuDialog("이전 저장 견적 확인", saved.quoteNumber, { legacyResult = null },
            actions = { Button(onClick = { legacyResult = null }, modifier = Modifier.fillMaxWidth()) { Text("닫기") } }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(saved.customerPhone)
                Text(saved.itemSummary)
                Text("저장 금액 ${"%,d".format(saved.totalPrice)}원")
                Text(if (saved.isPrepaid) "선불 택배비 ${"%,d".format(saved.shippingFee)}원 포함" else "택배비 후불")
                Text("저장 시각 ${saved.saveDate}")
                Text("기존 앱에 저장된 기록입니다. 서버 견적확정 확인 정보는 포함되어 있지 않습니다.")
            }
        }
    }
}

@Composable
private fun QuoteTableColumns(serial: String, number: String, name: String, phone: String,
    header: Boolean = false, pending: Boolean = false) {
    val values = listOf(serial, number, name, phone)
    val widths = listOf(0.10f, 0.43f, 0.16f, 0.31f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val textSize = if (maxWidth < 340.dp) 12.sp else 13.sp
        Row(Modifier.fillMaxWidth().height(if (header) 36.dp else 48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            values.forEachIndexed { index, value ->
                if (index > 0) VerticalDivider(Modifier.fillMaxHeight(), thickness = 0.5.dp)
                Row(Modifier.weight(widths[index]).padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(value.ifBlank { "—" }, Modifier.weight(1f),
                        fontSize = textSize, letterSpacing = 0.sp,
                        fontWeight = if (header || index == 1) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = if (index == 0 || header) TextAlign.Center else TextAlign.Start,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    if (index == 1 && pending) Icon(Icons.Outlined.Info,
                        "견적완료 확인 필요 · 같은 내용으로 다시 확인하세요.", Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun QuoteTableRow(serial: Int, number: String, name: String, phone: String,
    detail: String? = null, pending: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(color = if (serial % 2 == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
            QuoteTableColumns(serial.toString(), number, name, phone, pending = pending)
            detail?.let {
                Text(it, Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 8.dp),
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

private fun workflowDate(value: Long): String = if (value <= 0) "전송 기록 없음" else
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
    }.format(java.util.Date(value))

@Composable
internal fun QuoteWorkflowResultDialog(saved: QuoteWorkflowRecord, onDismiss: () -> Unit) {
    val draft = saved.draft
    val lines = remember(draft.rows) { draft.rows.map { it.line() } }
    val itemAmount = lines.sumOf { it.orderQty.toLong() * it.retailPrice }
    val shipping = draft.shipping().toLong()
    val tax = if (draft.recipient.formalQuoteRequested) draft.options.taxAmount() else 0L
    val total = itemAmount + shipping + tax
    NamuDialog("확정견적 확인", saved.request.receiptNumber.ifBlank { saved.request.requestId }, onDismiss,
        actions = { Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("닫기") } }) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                QuoteBox("고객 정보") {
                    QuoteResultField("고객명", draft.recipient.customerName.ifBlank { saved.request.customerName })
                    QuoteResultField("연락처", draft.phone)
                    QuoteResultField("이메일", draft.recipient.customerEmail)
                    if (draft.recipient.recipientName.isNotBlank()) {
                        QuoteResultField("수신인", draft.recipient.recipientName)
                    }
                    QuoteResultField("상태", "견적확정")
                    QuoteResultField("확정일", workflowDate(saved.confirmedAt))
                }
            }
            item {
                Text("주문 내역 · ${lines.size}개 품목", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            }
            itemsIndexed(lines) { index, line ->
                QuoteBox("${index + 1}. ${line.speciesName}") {
                    QuoteResultField("규격", draft.rows[index].spec)
                    QuoteResultField("수량", "${quoteResultNumber(line.orderQty.toLong())}주")
                    QuoteResultField("단가", "${quoteResultNumber(line.retailPrice.toLong())}원")
                    HorizontalDivider()
                    QuoteResultField("금액", "${quoteResultNumber(line.orderQty.toLong() * line.retailPrice)}원",
                        emphasized = true)
                }
            }
            item {
                QuoteBox("견적 금액") {
                    QuoteResultField("묘목 합계", "${quoteResultNumber(itemAmount)}원")
                    QuoteResultField("택배비", if (draft.prepaid) "선불 ${quoteResultNumber(shipping)}원" else "후불",
                        emphasized = true)
                    if (tax > 0) QuoteResultField("추가 세액", "${quoteResultNumber(tax)}원")
                    HorizontalDivider()
                    QuoteResultField("총금액", "${quoteResultNumber(total)}원", emphasized = true)
                    Text(if (draft.prepaid) "선불 택배비 포함" else "후불 택배비는 총금액에 포함되지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                QuoteBox("고객 요청사항") {
                    Text(saved.request.message.ifBlank { "등록된 요청사항이 없습니다." },
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                QuoteBox("전송 기록") {
                    QuoteResultField("문자", saved.delivery.smsLabel(draft))
                    if (saved.delivery.smsSentAt > 0) QuoteResultField("전송일", workflowDate(saved.delivery.smsSentAt))
                    QuoteResultField("메일", saved.delivery.emailLabel(draft))
                    if (saved.delivery.emailSentAt > 0) QuoteResultField("전송일", workflowDate(saved.delivery.emailSentAt))
                }
            }
        }
    }
}

@Composable
private fun QuoteResultField(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, Modifier.weight(1f), textAlign = TextAlign.End,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun quoteResultNumber(value: Long): String = "%,d".format(java.util.Locale.KOREA, value)

