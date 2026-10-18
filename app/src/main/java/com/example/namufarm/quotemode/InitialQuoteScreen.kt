package com.example.namufarm.quotemode

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
import com.example.namufarm.quotemode.components.NamuDialog
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
    var message by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<QuoteWorkflowRecord?>(null) }
    var serverResult by remember { mutableStateOf<WebQuoteRequestEntity?>(null) }
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
            if (result == null) serverResult = editing
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
    val remoteConfirmed = inbox.requests.filter { it.status in setOf("견적확정", "견적발송완료") &&
        it.requestId !in confirmedIds }.sortedByDescending { it.sequence }
    Scaffold(containerColor = Color(0xFFF2F4F7), topBar = {
        TopAppBar(title = { Text("나무팜 견적모드", fontWeight = FontWeight.ExtraBold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1976D2),
                titleContentColor = Color.White, actionIconContentColor = Color.White), actions = {
            TextButton(onClick = onSync, enabled = !busy && !inbox.busy && !deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("↻ 가져오기") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    label = { Text("견적요청 ${requests.size}") })
                FilterChip(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    label = { Text("확정견적 ${confirmed.size + remoteConfirmed.size}") })
            }
            message?.let { Text(it, color = if (it.startsWith("❌")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary) }
            if (inbox.deferredCount > 0) Text("이전 암호화 요청 ${inbox.deferredCount}건은 원본 기기에서 확인해주세요.",
                style = MaterialTheme.typography.bodySmall)
            if (busy || inbox.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                            if (confirmed.isEmpty() && remoteConfirmed.isEmpty()) item {
                                Text("확정된 견적이 없습니다.", Modifier.padding(12.dp))
                            }
                            itemsIndexed(confirmed, key = { _, saved -> saved.request.requestId }) { index, saved ->
                                QuoteTableRow(index + 1, saved.request.receiptNumber.ifBlank { saved.request.requestId },
                                    saved.draft.recipient.customerName, saved.draft.phone,
                                    detail = "견적확정 · ${workflowDate(saved.confirmedAt)}") { result = saved }
                            }
                            itemsIndexed(remoteConfirmed, key = { _, saved -> "server-${saved.requestId}" }) { index, saved ->
                                QuoteTableRow(confirmed.size + index + 1, saved.receiptNumber.ifBlank { saved.requestId },
                                    saved.customerName, saved.customerPhone, detail = "견적확정 · 운영 시트 저장") {
                                    serverResult = saved
                                }
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
    serverResult?.let { saved ->
        NamuDialog("확정견적 확인", saved.receiptNumber, { serverResult = null },
            actions = { Button(onClick = { serverResult = null }, modifier = Modifier.fillMaxWidth()) { Text("닫기") } }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("고객명 ${saved.customerName}", fontWeight = FontWeight.Bold)
                Text("${saved.customerPhone} · ${saved.customerEmail}")
                val draft = remember(saved) { runCatching { serverConfirmedDraft(saved) }.getOrNull() }
                if (draft != null) Text(quoteWorkflowText(saved, draft))
                else Text("이전 버전에서 확정한 견적으로 단가 내역이 없습니다. 확정한 기기에서 확인해주세요.")
                Text("운영 시트에 확정된 견적입니다.")
                if (saved.message.isNotBlank()) Text("요청사항\n${saved.message}")
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


