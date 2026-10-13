package com.example.namufarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.phone.openSmsComposer
import com.example.namufarm.quotation.*
import com.example.namufarm.ui.components.*
import com.example.namufarm.viewmodel.ManagementViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One saved quote can be retried or sent through both channels, without saving another quote. */
@Composable
internal fun QuoteDeliveryDialog(viewModel: ManagementViewModel, number: String, initialAction: QuoteDelivery,
    onDocument: () -> Unit, onDetails: () -> Unit, onDismiss: () -> Unit,
    onQuoteSent: suspend () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var quote by remember(number) { mutableStateOf<QuoteEntity?>(null) }
    var recipient by remember(number) { mutableStateOf(QuoteRecipient()) }
    var document by remember(number) { mutableStateOf<QuoteDocument?>(null) }
    var email by rememberSaveable(number) { mutableStateOf<String?>(null) }
    var initialHandled by rememberSaveable(number) { mutableStateOf(false) }
    var loading by remember(number) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by rememberSaveable(number) { mutableStateOf<String?>(null) }
    suspend fun send(action: QuoteDelivery) {
        val saved = quote ?: return
        if (busy || action == QuoteDelivery.SAVE) return
        busy = true; error = null
        try {
            val fixed = document
            if (fixed != null) {
                val files = withContext(Dispatchers.IO) { QuoteDocumentRenderer.export(context, fixed) }
                if (action == QuoteDelivery.EMAIL) QuoteReply.openEmail(context, fixed, files.pdf)
                else QuoteReply.openMms(context, fixed, files.images)
            } else {
                val body = viewModel.generateQuoteTextFromEntity(saved)
                check(body.isNotBlank()) { "견적 내용을 확인해주세요." }
                if (action == QuoteDelivery.EMAIL) QuoteReply.openTextEmail(context, email.orEmpty().trim(), body)
                else openSmsComposer(context, saved.customerPhone, body)?.let { kotlin.error(it) }
            }
            notice = "문자·메일 앱에서 전송을 마친 뒤 아래 ‘발송 완료로 기록’을 눌러주세요."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "전송 화면을 열지 못했습니다. 다시 시도해주세요." }
        finally { busy = false }
    }
    LaunchedEffect(number) {
        try {
            quote = viewModel.savedQuote(number) ?: kotlin.error("저장된 견적을 찾을 수 없습니다.")
            recipient = viewModel.quoteRecipient(number)
            document = viewModel.quoteDocument(number)?.let { QuoteDocument.read(it.snapshotJson) }
            if (email == null) email = document?.recipient?.customerEmail ?: recipient.customerEmail
            loading = false
            // Set before handing off. Rotation or a return from the other app must not send again.
            if (!initialHandled) { initialHandled = true; send(initialAction) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "견적을 불러오지 못했습니다." }
        finally { loading = false }
    }
    NamuDialog("견적 전달", if (document != null) "공식 견적서 · 저장 완료" else "견적 저장 완료 · 다시 보내기 가능", onDismiss = { if (!busy) onDismiss() }, actions = {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { send(QuoteDelivery.SMS) } }, enabled = !busy && !loading && quote != null, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("문자 보내기") }
            Button(onClick = { scope.launch { send(QuoteDelivery.EMAIL) } }, enabled = !busy && !loading && quote != null, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(if (busy) "준비 중…" else "메일 보내기") }
        }
        if (!loading && quote != null) QuoteSentAction(viewModel, number, onQuoteSent)
        TextButton(onClick = onDetails, enabled = !busy && !loading && quote != null, modifier = Modifier.fillMaxWidth()) { Text("견적 상세 보기") }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            quote?.let { saved ->
                QuoteBox("받는 사람") {
                    Text(recipient.recipientName.ifBlank { recipient.customerName }.ifBlank { "고객" }, style = MaterialTheme.typography.titleLarge)
                    Text(saved.customerPhone)
                    if (document == null) OutlinedTextField(email.orEmpty(), { email = it.take(254) }, label = { Text("이메일") }, enabled = !busy,
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    else Text(document!!.recipient.customerEmail.ifBlank { "이메일 미등록 · 공식 견적서의 수신 정보는 발행 당시 내용으로 유지됩니다." })
                }
                QuoteBox("저장한 견적") {
                    com.example.namufarm.domain.order.OrderSummaryCodec.parseStrict(saved.itemSummary).forEach {
                        Text("${it.speciesName} ${it.quality} · ${it.orderQty}주 × ${money(it.retailPrice.toLong())}")
                    }
                    HorizontalDivider()
                    Text(money(document?.totalAmount ?: saved.totalPrice.toLong()), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text(shippingLabel(saved.isPrepaid, saved.shippingFee))
                    document?.let { Text("세액 ${money(it.taxAmount)} 포함 · ${it.number}", style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedButton(onClick = onDocument, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (document == null) "공식 견적서 추가 발행" else "공식 견적서 보기 · 공유") }
                Text("문자와 메일을 모두 보내려면 각각의 버튼을 눌러주세요. 같은 견적 기록을 사용합니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
