package com.example.namufarm.quotemode

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.phone.openSmsComposer
import com.example.namufarm.quotation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Revision writes are local. Only explicit seller attestation can acknowledge a resend. */
@Composable
internal fun ConfirmedQuoteSessionDialog(saved: QuoteWorkflowRecord, workflow: QuoteWorkflow,
    connection: InitialQuoteConnection, onChanged: (QuoteWorkflowRecord) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var current by remember(saved.request.requestId) { mutableStateOf(saved) }
    var latest by remember(saved.request.requestId) { mutableStateOf<WebQuoteRequestEntity?>(null) }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSent by remember { mutableStateOf(false) }
    var openedChannels by remember { mutableStateOf(emptySet<String>()) }
    var documentNumber by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(saved.request.requestId) {
        try { latest = withContext(Dispatchers.IO) { connection.latestRequest(saved.request.requestId) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "최신 서버 상태를 확인하지 못했습니다." }
    }
    fun changed(record: QuoteWorkflowRecord) { current = record; onChanged(record) }
    fun operate(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "처리 결과를 확인하지 못했습니다." }
            finally {
                try { withContext(Dispatchers.IO) { workflow.records().firstOrNull { it.request.requestId == current.request.requestId } }?.let(::changed) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "휴대폰 견적 기록을 확인해주세요." }
                finally { busy = false }
            }
        }
    }
    if (editing) ManualQuoteEditor(current.request, busy, error,
        onDismiss = { if (!busy) { editing = false; error = null } }, initialDraft = current.draft,
        draftKey = "confirmed:${current.request.requestId}:${current.revision}", messageIsError = error != null,
        confirmedEdit = true, onSave = { draft, _ -> operate {
            val revised = withContext(Dispatchers.IO) { workflow.reviseConfirmed(current, draft, checkNotNull(latest)) }
            changed(revised); openedChannels = emptySet(); documentNumber = ""; editing = false
        } })
    else InitialConfirmedQuoteDialog(current.request, current.draft, current.confirmedAt, current.delivery,
        onDismiss = { if (!busy) onDismiss() }, record = current,
        editBlock = if (busy) "처리 중입니다." else confirmedQuoteEditBlock(current, latest), feedback = error,
        onEdit = { operate {
            latest = withContext(Dispatchers.IO) { connection.latestRequest(current.request.requestId) }
            check(confirmedQuoteEditBlock(current, latest) == null) { confirmedQuoteEditBlock(current, latest).orEmpty() }
            editing = true
        } },
        sendEnabled = !busy && confirmedQuoteEditBlock(current, latest) == null,
        onSms = { operate {
            val prepared = withContext(Dispatchers.IO) {
                val verified = workflow.prepareConfirmedExternal(current, QuoteDelivery.SMS)
                verified to InitialFormalQuoteStore(context).issue(current.request, current.draft)
            }
            latest = prepared.first
            openSmsComposer(context, current.draft.phone, quoteWorkflowText(current.request, current.draft))?.let { throw IllegalStateException(it) }
            documentNumber = prepared.second.number; openedChannels = openedChannels + "sms"; confirmSent = true
        } },
        onBeforeFormalSend = { channel, doc ->
            check(!busy) { "처리 중입니다." }
            busy = true
            try {
                latest = withContext(Dispatchers.IO) {
                    val verified = workflow.prepareConfirmedExternal(current, if (channel == "email") QuoteDelivery.EMAIL else QuoteDelivery.SMS)
                    check(InitialFormalQuoteStore(context).issue(current.request, current.draft).number == doc.number) { "견적 내용이 바뀌었습니다. PDF를 다시 생성해주세요." }
                    verified
                }
            } finally { busy = false }
        },
        onFormalOpened = { channel, doc ->
            documentNumber = doc.number; openedChannels = openedChannels + channel; confirmSent = true
        },
        onRetryConfirmation = if (current.pendingConfirmedDelivery != null && !busy) ({ operate {
            changed(withContext(Dispatchers.IO) { workflow.retryConfirmedDelivery(current) })
            latest = latest?.copy(status = "견적발송완료")
        } }) else null)
    if (confirmSent) ConfirmQuoteSentDialog(openedChannels = openedChannels,
        phone = current.draft.phone, email = current.draft.recipient.customerEmail,
        onDismiss = { confirmSent = false }, onConfirm = { method ->
            confirmSent = false
            operate {
                changed(withContext(Dispatchers.IO) { workflow.completeConfirmedExternal(current, method, documentNumber) })
                latest = latest?.copy(status = "견적발송완료")
            }
        })
}
