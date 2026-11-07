package com.example.namufarm.quotemode

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.phone.openSmsComposer
import com.example.namufarm.quotation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Only explicit seller attestation can acknowledge a resend. With shared quotes (server
 * confirmedQuoteVersion:1) revisions are saved on the server first, deliveries are reserved before the
 * phone's app opens, and 입금확인 is claimed on the server before the local 매매 order is created.
 */
@Composable
internal fun ConfirmedQuoteSessionDialog(saved: QuoteWorkflowRecord, workflow: QuoteWorkflow,
    connection: InitialQuoteConnection, onChanged: (QuoteWorkflowRecord) -> Unit,
    newOrderNumber: (() -> String)? = null,
    onPaidOrder: (suspend (String, QuoteWorkflowRecord) -> Unit)? = null,
    onDismiss: () -> Unit) {
    val context = LocalContext.current
    var current by remember(saved.request.requestId) { mutableStateOf(saved) }
    var latest by remember(saved.request.requestId) { mutableStateOf<WebQuoteRequestEntity?>(null) }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSent by remember { mutableStateOf(false) }
    var openedChannels by remember { mutableStateOf(emptySet<String>()) }
    var documentNumber by remember { mutableStateOf("") }
    var choosing by remember(saved.request.requestId) { mutableStateOf(false) }
    var paying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(saved.request.requestId) {
        try { latest = withContext(Dispatchers.IO) { connection.latestRequest(saved.request.requestId) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "최신 서버 상태를 확인하지 못했습니다." }
    }
    // Another device saved a different version: the seller chooses; nothing is overwritten silently.
    LaunchedEffect(current.sharedConflict) { if (current.sharedConflict != null) choosing = true }
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
    val paymentOpen = onPaidOrder != null && newOrderNumber != null &&
        (current.sharedPayment != null || (latest?.status?.let { it in CONFIRMED_QUOTE_STATES } == true && current.sharedConflict == null))
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
                    val verified = workflow.prepareConfirmedExternal(current,
                        if (channel == "email") QuoteDelivery.EMAIL else QuoteDelivery.SMS, payload = "formal:" + doc.number)
                    check(InitialFormalQuoteStore(context).issue(current.request, current.draft).number == doc.number) { "견적 내용이 바뀌었습니다. PDF를 다시 생성해주세요." }
                    verified
                }
                withContext(Dispatchers.IO) { workflow.records().firstOrNull { it.request.requestId == current.request.requestId } }?.let(::changed)
            } finally { busy = false }
        },
        onFormalOpened = { channel, doc ->
            documentNumber = doc.number; openedChannels = openedChannels + channel; confirmSent = true
        },
        onRetryConfirmation = when {
            busy -> null
            current.pendingConfirmedDelivery != null -> ({ operate {
                changed(withContext(Dispatchers.IO) { workflow.retryConfirmedDelivery(current) })
                latest = latest?.copy(status = "견적발송완료")
            } })
            // A reservation left open (app closed before answering): ask again whether it was sent.
            current.sharedDelivery != null -> ({ operate {
                documentNumber = withContext(Dispatchers.IO) { InitialFormalQuoteStore(context).issue(current.request, current.draft).number }
                openedChannels = setOfNotNull(current.sharedDelivery?.channel); confirmSent = true
            } })
            else -> null
        },
        onPayment = if (paymentOpen) ({ paying = true }) else null)
    if (confirmSent) ConfirmQuoteSentDialog(openedChannels = openedChannels,
        phone = current.draft.phone, email = current.draft.recipient.customerEmail,
        onDismiss = { confirmSent = false },
        onNotSent = { confirmSent = false
            if (current.sharedDelivery != null) operate { withContext(Dispatchers.IO) { workflow.cancelShared(current.request.requestId) }?.let(::changed) }
        },
        onConfirm = { method ->
            confirmSent = false
            operate {
                changed(withContext(Dispatchers.IO) { workflow.completeConfirmedExternal(current, method, documentNumber) })
                latest = latest?.copy(status = "견적발송완료")
            }
        })
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, containerColor = Color.White,
        title = { Text("다른 기기에서 수정한 견적", fontWeight = FontWeight.Bold) },
        text = { Text("PC 나피나 다른 휴대폰에서 이 견적을 수정했고, 이 휴대폰의 내용과 다릅니다.\n\n" +
            "사용할 내용을 선택해주세요. 선택하지 않은 내용도 기록으로 남습니다.", fontSize = 16.sp) },
        confirmButton = { TextButton(enabled = !busy, onClick = { choosing = false
            operate { changed(withContext(Dispatchers.IO) { workflow.useSharedServer(current) }) } }) {
            Text("서버 내용 사용", fontWeight = FontWeight.Bold, fontSize = 16.sp) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { choosing = false
            operate { changed(withContext(Dispatchers.IO) { workflow.keepLocalShared(current) }) } }) {
            Text("이 휴대폰 수정본 저장", fontSize = 16.sp) } })
    if (paying) {
        val amount = runCatching { sharedAmount(current.draft) }.getOrNull()
        AlertDialog(onDismissRequest = { paying = false }, containerColor = Color.White,
            title = { Text("입금확인", fontWeight = FontWeight.Bold) },
            text = { Text("고객 입금을 확인했나요?\n" + (amount?.let { "금액 ${String.format("%,d", it)}원\n" } ?: "") +
                "\n서버에 먼저 입금확인을 기록한 뒤 매매 주문을 만듭니다. 다른 기기에서는 같은 견적을 다시 입금확인할 수 없습니다.",
                fontSize = 16.sp) },
            confirmButton = { TextButton(enabled = !busy, onClick = { paying = false
                val numbers = checkNotNull(newOrderNumber); val createOrder = checkNotNull(onPaidOrder)
                operate {
                    changed(withContext(Dispatchers.IO) { workflow.confirmSharedPayment(current, numbers, createOrder) })
                    onDismiss()
                } }) { Text("입금확인", fontWeight = FontWeight.Bold, fontSize = 16.sp) } },
            dismissButton = { TextButton(onClick = { paying = false }) { Text("취소", fontSize = 16.sp) } })
    }
}
