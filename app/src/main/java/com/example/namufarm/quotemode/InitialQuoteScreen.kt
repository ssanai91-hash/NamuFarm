package com.example.namufarm.quotemode

import com.example.namufarm.ui.theme.*
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
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Phone
import com.example.namufarm.data.remote.inquiryTimestamp
import com.example.namufarm.data.remote.inquiryLocalTime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.quotation.*
import com.example.namufarm.phone.openSmsComposer
import com.example.namufarm.quotemode.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android only answers enquiries. Confirmed results have no selling, payment or delivery actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InitialQuoteScreen(viewModel: InitialQuoteViewModel, connection: InitialQuoteConnection,
    inbox: InitialQuoteUiState, onSync: () -> Unit,
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
    var message by remember { mutableStateOf<NamuMessage?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<QuoteWorkflowRecord?>(null) }
    var serverResult by remember { mutableStateOf<WebQuoteRequestEntity?>(null) }
    val editing = management.editingRequest
    val deleting = inbox.deleteFeedback?.phase == "deleting"
    LaunchedEffect(management.message) {
        management.message?.let { message = it; viewModel.clearMessage() }
    }
    LaunchedEffect(workflow) {
        try { records = withContext(Dispatchers.IO) { workflow.records() } }
        catch (e: Exception) { message = NamuMessage("저장된 견적 기록 확인이 필요합니다: ${e.message}", true) }
        finally { recordsReady = true }
    }
    LaunchedEffect(inbox.deleteFeedback) {
        val feedback = inbox.deleteFeedback
        if (feedback?.phase == "success" && editing?.requestId == feedback.requestId) {
            viewModel.dismissManualQuote(); message = NamuMessage("견적요청을 삭제했습니다.")
        }
        if (feedback?.phase == "error") message = NamuMessage(feedback.error ?: "삭제하지 못했습니다.", true)
    }
    val confirmed = records.filter { it.confirmedAt > 0 }.sortedByDescending { it.confirmedAt }
    val confirmedIds = confirmed.map { it.request.requestId }.toSet()
    // The list already came from Room. Opening a row must never wait for a server snapshot.
    val readyRequest = editing?.takeIf { recordsReady && it.status == "신규" && it.requestId !in confirmedIds }
    LaunchedEffect(editing, recordsReady) {
        if (recordsReady && editing != null && (editing.status != "신규" || editing.requestId in confirmedIds)) {
            result = confirmed.firstOrNull { it.request.requestId == editing.requestId }
            if (result == null) serverResult = editing
            selectedTab = 1
            viewModel.dismissManualQuote()
        }
    }
    LaunchedEffect(readyRequest?.requestId) {
        readyRequest?.let { onRead(it.requestId); message = null }
    }
    val pending = records.filter { it.confirmationPending }.map { it.request }
    val requests = (pending + inbox.requests.filter { it.status == "신규" })
        .distinctBy { it.requestId }.filter { it.requestId !in confirmedIds }.sortedByDescending { it.sequence }
    val remoteConfirmed = inbox.requests.filter { it.status in setOf("견적확정", "견적발송완료") &&
        it.requestId !in confirmedIds }.sortedByDescending { it.sequence }
    Scaffold(containerColor = NamuColors.background, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        NamuTopBar("견적", masterSyncLabel(inbox.lastSyncAt),
            onSync = { if (!busy && !inbox.busy && !deleting) onSync() })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NamuSegmented(listOf(NamuSegment("견적요청", requests.size),
                NamuSegment("확정견적", confirmed.size + remoteConfirmed.size)), selectedTab,
                onSelect = { selectedTab = it })
            message?.let { Text(it.text, color = if (it.isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary) }
            if (inbox.deferredCount > 0) Text("이전 암호화 요청 ${inbox.deferredCount}건은 원본 기기에서 확인해주세요.",
                style = MaterialTheme.typography.bodySmall)
            if (busy || inbox.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)) {
                if (selectedTab == 0) {
                    if (requests.isEmpty()) item {
                        Text("새 견적요청이 없습니다. 동기화로 서버 요청을 확인하세요.", Modifier.padding(12.dp))
                    }
                    itemsIndexed(requests, key = { _, request -> request.requestId }) { index, request ->
                        QuoteRequestCard(request, request.customerName, request.customerPhone,
                            status = "견적 대기", date = inquiryLocalTime(request.createdAt),
                            pending = records.any { it.request.requestId == request.requestId && it.confirmationPending },
                            enabled = !busy && !inbox.busy && !deleting) {
                            viewModel.useWebRequestForQuote(request); message = null
                        }
                    }
                } else {
                    if (confirmed.isEmpty() && remoteConfirmed.isEmpty()) item {
                        Text("확정된 견적이 없습니다.", Modifier.padding(12.dp))
                    }
                    itemsIndexed(confirmed, key = { _, saved -> saved.request.requestId }) { index, saved ->
                        QuoteRequestCard(saved.request, saved.draft.recipient.customerName, saved.draft.phone,
                            status = "견적확정", date = workflowDate(saved.confirmedAt), at = saved.confirmedAt,
                            itemCount = saved.draft.rows.count { it.name.isNotBlank() }) { result = saved }
                    }
                    itemsIndexed(remoteConfirmed, key = { _, saved -> "server-${saved.requestId}" }) { index, saved ->
                        QuoteRequestCard(saved, saved.customerName, saved.customerPhone,
                            status = "견적확정", date = "접수 ${inquiryLocalTime(saved.createdAt)}") {
                            serverResult = saved
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
            message = NamuMessage(if (action == QuoteDelivery.COMPLETE) "서버 견적확정 확인 중…" else "전송 화면 준비 중…")
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
                            message = NamuMessage("문자·메일 앱에서 실제 전송을 마친 뒤 ‘견적완료’를 눌러주세요.")
                        }
                        QuoteDelivery.COMPLETE -> {
                            withContext(Dispatchers.IO) {
                                workflow.completeExternal(request, draft,
                                    sentVia ?: error("실제로 보낸 방법을 선택해주세요."))
                            }
                            viewModel.dismissManualQuote(); selectedTab = 1
                            message = NamuMessage("견적을 확정했습니다.")
                        }
                        else -> error("견적완료로 저장해주세요.")
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: ServerQuoteRequestChanged) {
                    viewModel.dismissManualQuote()
                    message = e.message?.let { NamuMessage(it, true) }
                }
                catch (e: Exception) { message = NamuMessage(e.message ?: "처리 결과를 확인하지 못했습니다. 다시 확인해주세요.", true) }
                finally {
                    try { records = withContext(Dispatchers.IO) { workflow.records() } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = NamuMessage("저장된 견적 기록을 확인해주세요: ${e.message}", true) }
                    finally { busy = false }
                }
            }
        }
        ManualQuoteEditor(request, busy || inbox.busy || deleting, message?.text,
            onDismiss = { if (!busy) { viewModel.dismissManualQuote(); message = null } },
            initialDraft = saved?.draft, draftKey = request.requestId,
            onDeleteRequest = if (saved?.confirmationPending == true) null else ({ onDelete(request.requestId) }),
            verifiedDelivery = saved?.delivery, verifyDelivery = true,
            onSave = { draft, action -> handleAction(draft, action) },
            onConfirmSent = { draft, method -> handleAction(draft, QuoteDelivery.COMPLETE, method) },
            onPrepareDocument = { draft ->
                withContext(Dispatchers.IO) {
                    workflow.prepareExternalDelivery(request, draft, QuoteDelivery.SMS)
                }
                records = withContext(Dispatchers.IO) { workflow.records() }
            })
    }
    result?.let { QuoteWorkflowResultDialog(it) { result = null } }
    serverResult?.let { saved ->
        val draft = remember(saved) { runCatching { serverConfirmedDraft(saved) }.getOrNull() }
        InitialConfirmedQuoteDialog(saved, draft, onDismiss = { serverResult = null })
    }
}

private fun requestedSpeciesCount(request: WebQuoteRequestEntity): Int? = runCatching {
    org.json.JSONArray(request.itemsJson).length()
}.getOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuoteRequestCard(request: WebQuoteRequestEntity, name: String, phone: String,
    status: String, date: String, pending: Boolean = false, enabled: Boolean = true,
    itemCount: Int? = requestedSpeciesCount(request),
    at: Long = runCatching { inquiryTimestamp(request.createdAt) }.getOrDefault(0L), onClick: () -> Unit) {
    val items = remember(request.itemsJson) { runCatching {
        com.google.gson.Gson().fromJson(request.itemsJson, Array<com.example.namufarm.data.WebQuoteItem>::class.java).toList()
    }.getOrDefault(emptyList()) }
    val representative = items.firstOrNull()?.let { "${it.species} ${String.format("%,d", it.quantity)}주" }
        ?: "품목 확인"
    val extra = ((itemCount ?: items.size) - 1).coerceAtLeast(0)
    Surface(color = Color.White, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, NamuColors.border)) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onClick),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name.ifBlank { "이름 없음" }, Modifier.weight(1f), fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, color = NamuColors.text)
                    Text(shortNamuTime(at), fontSize = 13.sp, color = NamuColors.muted)
                }
                Text(representative + if (extra > 0) " 외 ${extra}종" else "", fontSize = 15.sp, color = NamuColors.text)
                if (pending) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = NamuColors.danger)
                    Text("견적완료 확인 필요", fontSize = 13.sp, color = NamuColors.danger)
                } else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (status == "견적확정") {
                        NamuStatusBadge("견적확정")
                        Text(date, fontSize = 13.sp, color = NamuColors.muted)
                    } else {
                        if (request.formalQuoteRequested) NamuStatusBadge("공식견적서")
                        val receipt = request.receiptNumber
                        if (receipt.isNotBlank()) Text(receipt.substringBefore('-') + "-" + receipt.takeLast(4),
                            fontSize = 13.sp, color = NamuColors.muted)
                    }
                }
            }
            NamuCallButton(phone, enabled = enabled)
        }
    }
}

private fun workflowDate(value: Long): String = if (value <= 0) "전송 기록 없음" else
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
    }.format(java.util.Date(value))

@Composable
internal fun QuoteWorkflowResultDialog(saved: QuoteWorkflowRecord, onDismiss: () -> Unit) {
    InitialConfirmedQuoteDialog(saved.request, saved.draft, saved.confirmedAt,
        saved.delivery, onDismiss)
}

