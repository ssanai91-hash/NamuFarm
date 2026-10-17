package com.example.namufarm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    Scaffold(topBar = {
        TopAppBar(title = { Text("나무팜 · 견적 관리", fontWeight = FontWeight.Bold) }, actions = {
            TextButton(onClick = onSync, enabled = !busy && !inbox.busy && !deleting) { Text("↻ 가져오기") }
            TextButton(onClick = onSettings, enabled = !busy) { Text("연결·설정") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    label = { Text("견적요청 ${requests.size}") })
                FilterChip(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    label = { Text("확정견적 ${confirmed.size + legacy.size}") })
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (busy || inbox.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (selectedTab == 0) {
                if (requests.isEmpty()) Text("새 견적요청이 없습니다. 가져오기로 서버 요청을 확인하세요.")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(requests, key = { it.requestId }) { request ->
                        OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !busy && !inbox.busy && !deleting) {
                            viewModel.useWebRequestForQuote(request); message = null
                        }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(request.receiptNumber.ifBlank { request.requestId }, fontWeight = FontWeight.Bold)
                                Text("${request.customerName} · ${request.customerPhone}")
                                if (records.any { it.request.requestId == request.requestId && it.confirmationPending })
                                    Text("견적완료 확인 필요 · 같은 내용으로 다시 확인하세요.")
                            }
                        }
                    }
                }
            } else {
                if (confirmed.isEmpty() && legacy.isEmpty()) Text("이 휴대폰에서 확정한 견적이 없습니다.")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(confirmed, key = { it.request.requestId }) { saved ->
                        OutlinedCard(Modifier.fillMaxWidth().clickable { result = saved }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(saved.request.receiptNumber.ifBlank { saved.request.requestId }, fontWeight = FontWeight.Bold)
                                Text("${saved.draft.recipient.customerName} · ${saved.draft.phone}")
                                Text("견적확정 · ${workflowDate(saved.confirmedAt)}")
                            }
                        }
                    }
                    items(legacy, key = { "legacy-${it.quoteNumber}" }) { saved ->
                        OutlinedCard(Modifier.fillMaxWidth().clickable { legacyResult = saved }) {
                            Column(Modifier.padding(16.dp)) {
                                Text(saved.quoteNumber, fontWeight = FontWeight.Bold)
                                Text("${saved.customerPhone} · 이전 저장 견적")
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
                            val body = quoteWorkflowText(request, draft)
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

private fun workflowDate(value: Long): String = if (value <= 0) "전송 기록 없음" else
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
    }.format(java.util.Date(value))

@Composable
internal fun QuoteWorkflowResultDialog(saved: QuoteWorkflowRecord, onDismiss: () -> Unit) {
        NamuDialog("확정견적 확인", saved.request.receiptNumber, onDismiss,
            actions = { Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("닫기") } }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("고객명 ${saved.draft.recipient.customerName}", fontWeight = FontWeight.Bold)
                Text("${saved.draft.phone}\n${saved.draft.recipient.customerEmail}")
                HorizontalDivider()
                Text(quoteWorkflowText(saved.request, saved.draft))
                HorizontalDivider()
                Text("문자 ${saved.delivery.smsLabel(saved.draft)} · ${workflowDate(saved.delivery.smsSentAt)}")
                Text("메일 ${saved.delivery.emailLabel(saved.draft)} · ${workflowDate(saved.delivery.emailSentAt)}")
                Text("확정 시각 ${workflowDate(saved.confirmedAt)}")
                if (saved.request.message.isNotBlank()) Text("요청사항\n${saved.request.message}")
            }
        }
}

