package com.example.namufarm.quotemode

import com.example.namufarm.ui.theme.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
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
        latestRequest = { connection.latestRequest(it.requestId) },
        markConfirmedDelivered = connection::markConfirmedQuoteDelivered) }
    val management by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var recordsReady by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf(emptyList<QuoteWorkflowRecord>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<NamuMessage?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // Display-only list filter. Remembered on this phone; never hides, moves or deletes saved quotes.
    val listPrefs = remember(context) { context.getSharedPreferences(CONFIRMED_LIST_PREFS, android.content.Context.MODE_PRIVATE) }
    var confirmedDays by remember { mutableIntStateOf(listPrefs.getInt("confirmedDays", 31)
        .takeIf { saved -> CONFIRMED_PERIODS.any { it.first == saved } } ?: 31) }
    var showClosed by remember { mutableStateOf(listPrefs.getBoolean("showClosed", false)) }
    fun chooseDays(days: Int) { confirmedDays = days; listPrefs.edit().putInt("confirmedDays", days).apply() }
    fun chooseClosed(show: Boolean) { showClosed = show; listPrefs.edit().putBoolean("showClosed", show).apply() }
    var result by remember { mutableStateOf<QuoteWorkflowRecord?>(null) }
    var serverResult by remember { mutableStateOf<WebQuoteRequestEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<QuoteDeleteTarget?>(null) }
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
        if (feedback?.phase == "success") {
            if (editing?.requestId == feedback.requestId) viewModel.dismissManualQuote()
            message = NamuMessage("견적을 삭제했습니다. 다른 휴대폰과 나피에서는 동기화 때 함께 지워집니다.")
            try { records = withContext(Dispatchers.IO) { workflow.records() } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        if (feedback?.phase == "error") message = NamuMessage(feedback.error ?: "삭제하지 못했습니다.", true)
    }
    // Seller deletes from other devices arrive with a sync and remove local confirmed records.
    LaunchedEffect(inbox.requests) {
        if (!recordsReady) return@LaunchedEffect
        try { records = withContext(Dispatchers.IO) { workflow.records() } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    val confirmed = records.filter { it.confirmedAt > 0 || it.revision > 0 }.sortedByDescending { it.confirmedAt }
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
    // One list, newest first: phone-confirmed quotes by confirmation time, server-only quotes by receipt time.
    val latestById = inbox.requests.associateBy { it.requestId }
    val confirmedRows = (confirmed.map { saved ->
        val state = latestById[saved.request.requestId]?.status
        ConfirmedListRow(saved.request.requestId, if (saved.confirmedAt > 0) saved.confirmedAt else requestTime(saved.request),
            closedState = state?.takeIf { it in CLOSED_QUOTE_STATES }, local = saved,
            needsAction = saved.confirmationPending || saved.pendingConfirmedDelivery != null || saved.revisedUnsent() ||
                saved.delivery.smsState in setOf("pending", "unknown", "sending"))
    } + inbox.requests.filter { it.requestId !in confirmedIds &&
        (it.status in CONFIRMED_QUOTE_STATES || it.status in CLOSED_QUOTE_STATES) }.map { saved ->
        ConfirmedListRow("server-${saved.requestId}", requestTime(saved),
            closedState = saved.status.takeIf { it in CLOSED_QUOTE_STATES }, remote = saved)
    }).sortedByDescending { it.at }
    val confirmedCutoff = if (confirmedDays >= CONFIRMED_ALL) Long.MIN_VALUE
        else System.currentTimeMillis() - confirmedDays * 86_400_000L
    val confirmedPool = confirmedRows.filter { showClosed || it.closedState == null }
    // Quotes still waiting for a send/result check are never hidden by the period.
    val shownConfirmed = confirmedPool.filter { it.needsAction || it.at <= 0 || it.at >= confirmedCutoff }
    val hiddenConfirmed = confirmedPool.size - shownConfirmed.size
    Scaffold(containerColor = NamuColors.background, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        NamuTopBar("견적", masterSyncLabel(inbox.lastSyncAt),
            onSync = { if (!busy && !inbox.busy && !deleting) onSync() })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NamuSegmented(listOf(NamuSegment("견적요청", requests.size),
                NamuSegment("확정견적", shownConfirmed.size)), selectedTab,
                onSelect = { selectedTab = it })
            if (selectedTab == 1) ConfirmedPeriodBar(confirmedDays, ::chooseDays, shownConfirmed.size, showClosed, ::chooseClosed)
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
                            enabled = !busy && !inbox.busy && !deleting,
                            onDelete = { deleteTarget = QuoteDeleteTarget(request.requestId, request.customerName,
                                records.any { it.request.requestId == request.requestId && it.confirmationPending }) }) {
                            viewModel.useWebRequestForQuote(request); message = null
                        }
                    }
                } else {
                    if (shownConfirmed.isEmpty()) item {
                        Text(if (confirmedPool.isEmpty()) "확정된 견적이 없습니다."
                            else "${confirmedPeriodLabel(confirmedDays)}에 확정한 견적이 없습니다.",
                            Modifier.padding(12.dp), fontSize = 15.sp, color = NamuColors.muted)
                    }
                    itemsIndexed(shownConfirmed, key = { _, row -> row.key }) { index, row ->
                        val badge = row.closedState ?: "견적확정"
                        // Paid/closed quotes are managed in 매매, so they get no trash button.
                        val canDelete = row.closedState == null && !busy && !inbox.busy && !deleting
                        row.local?.let { saved ->
                            QuoteRequestCard(saved.request, saved.draft.recipient.customerName, saved.draft.phone,
                                status = "견적확정", date = workflowDate(saved.confirmedAt), at = row.at, badge = badge,
                                itemCount = saved.draft.rows.count { it.name.isNotBlank() },
                                onDelete = if (row.closedState == null) ({ deleteTarget = QuoteDeleteTarget(
                                    saved.request.requestId, saved.draft.recipient.customerName, row.needsAction) }) else null,
                                deleteEnabled = canDelete) { result = saved }
                        }
                        row.remote?.let { saved ->
                            QuoteRequestCard(saved, saved.customerName, saved.customerPhone, badge = badge,
                                status = "견적확정", date = "접수 ${inquiryLocalTime(saved.createdAt)}",
                                onDelete = if (row.closedState == null) ({ deleteTarget = QuoteDeleteTarget(
                                    saved.requestId, saved.customerName, false) }) else null,
                                deleteEnabled = canDelete) {
                                serverResult = saved
                            }
                        }
                    }
                    if (hiddenConfirmed > 0) item {
                        OutlinedButton(onClick = { chooseDays(CONFIRMED_ALL) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, NamuColors.primary)) {
                            Text("지난 견적 ${hiddenConfirmed}건 더 보기", color = NamuColors.primary,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    readyRequest?.takeIf { editing?.requestId == it.requestId && recordsReady && it.requestId !in confirmedIds }?.let { request ->
        var openedChannels by remember(request.requestId) { mutableStateOf(emptySet<String>()) }
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
                            openedChannels = openedChannels + if (action == QuoteDelivery.SMS) "sms" else "email"
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
            initialDraft = saved?.draft, draftKey = request.requestId, messageIsError = message?.isError == true, openedChannels = openedChannels,
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
    fun changedConfirmed(saved: QuoteWorkflowRecord) {
        records = records.filterNot { it.request.requestId == saved.request.requestId } + saved
    }
    result?.let { saved -> ConfirmedQuoteSessionDialog(
        records.firstOrNull { it.request.requestId == saved.request.requestId } ?: saved,
        workflow, connection, ::changedConfirmed) { result = null } }
    serverResult?.let { saved ->
        val draft = remember(saved) { runCatching { serverConfirmedDraft(saved) }.getOrNull() }
        val local = records.firstOrNull { it.request.requestId == saved.requestId }
        if (local != null || draft != null) ConfirmedQuoteSessionDialog(local ?: QuoteWorkflowRecord(saved, checkNotNull(draft)),
            workflow, connection, ::changedConfirmed) { serverResult = null }
        else InitialConfirmedQuoteDialog(saved, null, onDismiss = { serverResult = null })
    }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, containerColor = Color.White,
            title = { Text("견적 삭제", fontWeight = FontWeight.Bold) },
            text = { Text("${target.name.ifBlank { "이름 없음" }} 님 견적을 삭제할까요?\n\n" +
                "서버와 휴대폰·PC(나피)에서 모두 지워지며 되돌릴 수 없습니다. 고객에게 문자·메일은 보내지 않습니다." +
                if (target.unresolved) "\n\n보낸 결과를 아직 확인하지 않은 기록도 함께 지워집니다." else "",
                fontSize = 16.sp) },
            confirmButton = { TextButton(enabled = !busy && !inbox.busy && !deleting,
                onClick = { deleteTarget = null; message = null; viewModel.adminDelete(target.id) }) {
                Text("삭제", color = NamuColors.danger, fontWeight = FontWeight.Bold, fontSize = 16.sp) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소", fontSize = 16.sp) } })
    }
}

/** Card trash button target: request ID, customer name and whether an unchecked send result would be lost. */
private data class QuoteDeleteTarget(val id: String, val name: String, val unresolved: Boolean)

private fun requestedSpeciesCount(request: WebQuoteRequestEntity): Int? = runCatching {
    org.json.JSONArray(request.itemsJson).length()
}.getOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuoteRequestCard(request: WebQuoteRequestEntity, name: String, phone: String,
    status: String, date: String, pending: Boolean = false, enabled: Boolean = true,
    badge: String = status, itemCount: Int? = requestedSpeciesCount(request),
    at: Long = runCatching { inquiryTimestamp(request.createdAt) }.getOrDefault(0L),
    onDelete: (() -> Unit)? = null, deleteEnabled: Boolean = enabled, onClick: () -> Unit) {
    val items = remember(request.itemsJson) { runCatching {
        com.google.gson.Gson().fromJson(request.itemsJson, Array<com.example.namufarm.data.WebQuoteItem>::class.java).toList()
    }.getOrDefault(emptyList()) }
    val representative = items.firstOrNull()?.let { "${it.species} ${String.format("%,d", it.quantity)}주" }
        ?: "품목 확인"
    val extra = ((itemCount ?: items.size) - 1).coerceAtLeast(0)
    Surface(color = Color.White, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, NamuColors.border)) {
        Column(Modifier.clickable(enabled = enabled, onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Top: name · date · (admin) trash, like the inquiry cards.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name.ifBlank { "이름 없음" }, Modifier.weight(1f), fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, color = NamuColors.text)
                Text(shortNamuTime(at), fontSize = 13.sp, color = NamuColors.muted)
                if (onDelete != null) IconButton(onClick = onDelete, enabled = deleteEnabled,
                    modifier = Modifier.padding(start = 6.dp).size(36.dp)
                        .background(Color(0xFFFDECEC), RoundedCornerShape(10.dp))) {
                    Icon(Icons.Outlined.Delete, "견적 삭제", tint = NamuColors.danger, modifier = Modifier.size(19.dp))
                }
            }
            Text(representative + if (extra > 0) " 외 ${extra}종" else "", fontSize = 15.sp, color = NamuColors.text)
            // Bottom: status/number on the left, contact buttons lowered to this line on the right.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f)) {
                    if (pending) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = NamuColors.danger)
                        Text("견적완료 확인 필요", fontSize = 13.sp, color = NamuColors.danger)
                    } else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (status == "견적확정") {
                            NamuStatusBadge(badge)
                            // The date is already at the top right; show the quote number instead of repeating it.
                            val receipt = request.receiptNumber
                            Text(if (receipt.isNotBlank()) receipt.substringBefore('-') + "-" + receipt.takeLast(4) else date,
                                fontSize = 13.sp, color = NamuColors.muted, modifier = Modifier.align(Alignment.CenterVertically))
                        } else {
                            if (request.formalQuoteRequested) NamuStatusBadge("공식견적서")
                            val receipt = request.receiptNumber
                            if (receipt.isNotBlank()) Text(receipt.substringBefore('-') + "-" + receipt.takeLast(4),
                                fontSize = 13.sp, color = NamuColors.muted, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                }
                // Slightly smaller contact buttons so the card text keeps its width.
                NamuSmsButton(phone, Modifier.size(42.dp), enabled = enabled, iconSize = 18.dp)
                NamuCallButton(phone, Modifier.size(42.dp), enabled = enabled, iconSize = 18.dp)
            }
        }
    }
}

private const val CONFIRMED_LIST_PREFS = "namufarm_quote_list"
private const val CONFIRMED_ALL = 9999
private val CONFIRMED_PERIODS = listOf(7 to "1주", 31 to "1개월", 92 to "3개월", CONFIRMED_ALL to "전체")

private class ConfirmedListRow(val key: String, val at: Long, val closedState: String?,
    val local: QuoteWorkflowRecord? = null, val remote: WebQuoteRequestEntity? = null, val needsAction: Boolean = false)

private fun requestTime(request: WebQuoteRequestEntity): Long =
    runCatching { inquiryTimestamp(request.createdAt) }.getOrDefault(0L)

private fun confirmedPeriodLabel(days: Int): String = when (days) {
    7 -> "최근 1주"; 31 -> "최근 1개월"; 92 -> "최근 3개월"; else -> "전체 기간"
}

/** Large, evenly spaced period buttons under the tabs, plus an opt-in for paid/closed quotes. */
@Composable
private fun ConfirmedPeriodBar(days: Int, onDays: (Int) -> Unit, shown: Int,
    showClosed: Boolean, onShowClosed: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CONFIRMED_PERIODS.forEach { (value, label) ->
                val active = value == days
                Surface(onClick = { onDays(value) }, modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp), color = if (active) NamuColors.primary else Color.White,
                    border = BorderStroke(1.dp, if (active) NamuColors.primary else NamuColors.border),
                    shadowElevation = if (active) 1.dp else 0.dp) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 16.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) Color.White else NamuColors.muted)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${if (days >= CONFIRMED_ALL) "전체" else confirmedPeriodLabel(days)} · 견적 ${shown}건",
                Modifier.weight(1f).padding(start = 2.dp), fontSize = 14.sp, color = NamuColors.muted)
            Surface(onClick = { onShowClosed(!showClosed) }, shape = RoundedCornerShape(10.dp), color = Color.Transparent) {
                Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(if (showClosed) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null,
                        Modifier.size(22.dp), tint = if (showClosed) NamuColors.primary else NamuColors.muted)
                    Text("입금된 견적도 보기", fontSize = 14.sp, color = NamuColors.text)
                }
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
    InitialConfirmedQuoteDialog(saved.request, saved.draft, saved.confirmedAt,
        saved.delivery, onDismiss)
}

