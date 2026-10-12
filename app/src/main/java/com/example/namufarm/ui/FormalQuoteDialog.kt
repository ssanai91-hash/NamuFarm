package com.example.namufarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.namufarm.BuildConfig
import com.example.namufarm.data.QuoteDocumentEntity
import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.quotation.*
import com.example.namufarm.ui.components.NamuDialog
import com.example.namufarm.ui.components.NamuSectionTitle
import com.example.namufarm.viewmodel.ManagementViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun FormalQuoteDialog(viewModel: ManagementViewModel, quoteNumber: String, onDismiss: () -> Unit,
    onQuoteSent: suspend () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { QuoteIssuerSettings(context) }
    var issuer by remember(quoteNumber) { mutableStateOf(settings.load()) }
    var recipient by remember(quoteNumber) { mutableStateOf(QuoteRecipient()) }
    var source by remember(quoteNumber) { mutableStateOf<QuoteEntity?>(null) }
    var document by remember(quoteNumber) { mutableStateOf<QuoteDocument?>(null) }
    var loading by remember { mutableStateOf(true) }; var busy by remember { mutableStateOf(false) }
    var tax by remember { mutableStateOf("0") }
    var validUntil by remember { mutableStateOf("발행일로부터 7일") }
    var notes by remember { mutableStateOf("견적 유효기간 경과 후에는 가격과 묘목 확보 가능 여부를 다시 확인해 주세요.") }
    var account by remember { mutableStateOf(BuildConfig.PAYMENT_ACCOUNT_TEXT) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(quoteNumber) {
        try {
            val saved = viewModel.quoteDocument(quoteNumber)
            if (saved != null) document = QuoteDocument.read(saved.snapshotJson)
            else { recipient = viewModel.quoteRecipient(quoteNumber); source = viewModel.savedQuote(quoteNumber) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "견적서를 불러오지 못했습니다." }
        finally { loading = false }
    }
    fun reply(kind: String) {
        val fixed = document ?: return
        if (busy) return
        busy = true; error = null; notice = null
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) { QuoteDocumentRenderer.export(context, fixed) }
                when (kind) {
                    "email" -> QuoteReply.openEmail(context, fixed, files.pdf)
                    "sms" -> QuoteReply.openMms(context, fixed, files.images)
                    "view" -> QuoteReply.viewPdf(context, files.pdf)
                    else -> QuoteReply.sharePdf(context, files.pdf)
                }
                notice = "파일을 열었어요. 메일·문자는 받는 사람과 첨부파일을 확인한 뒤 해당 앱에서 보내주세요."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "견적서 파일을 열지 못했습니다. 다시 시도해주세요." }
            finally { busy = false }
        }
    }
    NamuDialog("공식 견적서", document?.number ?: "내용 확인 후 발행", onDismiss = { if (!busy) onDismiss() }, actions = {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (document == null) Button(onClick = {
            if (busy || loading) return@Button
            busy = true; error = null
            scope.launch {
                try {
                    val taxAmount = tax.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() } ?: kotlin.error("추가 세액을 확인해주세요.")
                    val checkedIssuer = issuer.checked(); val checkedRecipient = recipient.copy(formalQuoteRequested = true).checked()
                    val saved = viewModel.issueDocument(quoteNumber, checkedIssuer, checkedRecipient, taxAmount, validUntil, notes, account)
                    document = QuoteDocument.read(saved.snapshotJson)
                    withContext(Dispatchers.IO) { settings.save(checkedIssuer) }
                    notice = "발행 내용을 저장했어요. PDF와 이미지는 이 기록으로 다시 생성합니다."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "견적서 저장에 실패했습니다." }
                finally { busy = false }
            }
        }, enabled = !busy && !loading && source != null, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "저장 중…" else "견적서 확정 · 저장") }
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { reply("email") }, enabled = !busy && document!!.recipient.customerEmail.isNotBlank(), modifier = Modifier.weight(1f)) { Text("이메일 PDF") }
                Button(onClick = { reply("sms") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("문자 이미지") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { reply("view") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("PDF 보기") }
                OutlinedButton(onClick = { reply("share") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("PDF 공유") }
            }
            QuoteSentAction(viewModel, quoteNumber, onQuoteSent)
        }
    }) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            val fixed = document
            if (fixed != null) {
                item {
                    NamuSectionTitle("발행한 견적", fixed.number)
                    Text("수신인 · ${fixed.recipient.recipientName.ifBlank { fixed.recipient.customerName }}")
                    Text("전화번호 · ${fixed.customerPhone}")
                    if (fixed.recipient.customerEmail.isNotBlank()) Text("이메일 · ${fixed.recipient.customerEmail}")
                    Text("공급자 · ${fixed.issuer.businessName}")
                    Text("발행한 문서의 내용은 유지됩니다. 가격이나 고객을 수정하려면 새 견적을 저장해 발행해주세요.")
                }
                items(fixed.lines) { line -> Text("${line.name} ${line.specification}\n${line.quantity}${line.unit} × ${String.format(Locale.KOREA, "%,d", line.unitPrice)}원 = ${String.format(Locale.KOREA, "%,d", line.amount)}원") }
                item { Text("합계 ${String.format(Locale.KOREA, "%,d", fixed.totalAmount)}원 · 세액 ${String.format(Locale.KOREA, "%,d", fixed.taxAmount)}원\n유효기간 · ${fixed.validUntil}") }
            } else {
                item {
                    NamuSectionTitle("수신인")
                    QuoteField("고객 성함", recipient.customerName, { recipient = recipient.copy(customerName = it.take(50)) }, busy)
                    QuoteField("수신인·회사명 · 선택", recipient.recipientName, { recipient = recipient.copy(recipientName = it.take(100)) }, busy)
                    QuoteField("이메일 · 선택", recipient.customerEmail, { recipient = recipient.copy(customerEmail = it.take(254)) }, busy, KeyboardType.Email)
                    Text("전화번호 · ${source?.customerPhone ?: "확인 중"}")
                    Text("요청한 수신 방법 · ${when(recipient.replyChannel) { "email" -> "이메일 PDF"; "both" -> "이메일 PDF와 문자 이미지"; else -> "문자 이미지" }}")
                }
                item {
                    NamuSectionTitle("공급자 정보", "처음 입력한 정보는 다음 견적에도 사용합니다")
                    QuoteField("상호 *", issuer.businessName, { issuer = issuer.copy(businessName = it.take(50)) }, busy)
                    QuoteField("사업자등록번호 *", issuer.businessNumber, { issuer = issuer.copy(businessNumber = it.take(20)) }, busy)
                    QuoteField("대표자 *", issuer.representative, { issuer = issuer.copy(representative = it.take(50)) }, busy)
                    QuoteField("사업장 주소 *", issuer.address, { issuer = issuer.copy(address = it.take(150)) }, busy)
                    QuoteField("공급자 연락처 *", issuer.phone, { issuer = issuer.copy(phone = it.take(30)) }, busy, KeyboardType.Phone)
                    QuoteField("업태 · 선택", issuer.businessType, { issuer = issuer.copy(businessType = it.take(50)) }, busy)
                    QuoteField("종목 · 선택", issuer.category, { issuer = issuer.copy(category = it.take(50)) }, busy)
                }
                item {
                    NamuSectionTitle("가격과 조건")
                    Text("저장된 견적금액 · ${String.format(Locale.KOREA, "%,d", source?.totalPrice ?: 0)}원")
                    QuoteField("추가 세액 (원)", tax, { tax = it.take(10) }, busy, KeyboardType.Number)
                    Text("필요한 추가 세액을 직접 확인해 입력해주세요. 세율을 자동 적용하지 않습니다.", style = MaterialTheme.typography.bodyMedium)
                    QuoteField("견적 유효기간 *", validUntil, { validUntil = it.take(100) }, busy)
                    QuoteField("입금 계좌 · 선택", account, { account = it.take(500) }, busy)
                    OutlinedTextField(notes, { notes = it.take(500) }, label = { Text("주요 사항 · 선택") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    Text("견적서 확정 후 저장된 금액·공급자·수신인 정보로 PDF와 이미지를 만듭니다.")
                }
            }
        }
    }
}

@Composable
private fun QuoteField(label: String, value: String, onValue: (String) -> Unit, busy: Boolean, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, onValue, label = { Text(label) }, enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

@Composable
internal fun QuoteDocumentHistoryDialog(documents: List<QuoteDocumentEntity>, onSelect: (QuoteDocumentEntity) -> Unit, onDismiss: () -> Unit) {
    NamuDialog("발행 견적서 보관함", "총 ${documents.size}건 · 저장한 발행 내용으로 다시 출력", onDismiss) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (documents.isEmpty()) item { Text("아직 발행한 견적서가 없습니다. 저장한 견적의 상세에서 공식 견적서를 작성해주세요.") }
            items(documents, key = { it.documentId }) { row ->
                val doc = runCatching { QuoteDocument.read(row.snapshotJson) }.getOrNull()
                OutlinedCard(onClick = { onSelect(row) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.issuedNumber)
                        Text(doc?.recipient?.let { it.recipientName.ifBlank { it.customerName } } ?: "발행 자료 확인 필요")
                        doc?.let { Text("${it.customerPhone} · ${String.format(Locale.KOREA, "%,d", it.totalAmount)}원") }
                    }
                }
            }
        }
    }
}

/** Opening another app does not prove delivery. The seller records the actual send once. */
@Composable
internal fun QuoteSentAction(viewModel: ManagementViewModel, number: String,
    onQuoteSent: suspend () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val quote = state.allQuotes.firstOrNull { it.quoteNumber == number }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if ((quote?.sentAt ?: 0L) > 0L) Text("견적 발송 완료 · 최초 발송일에 1건으로 집계", color = MaterialTheme.colorScheme.primary)
    else OutlinedButton(onClick = { confirm = true }, enabled = !busy && quote != null, modifier = Modifier.fillMaxWidth()) { Text("발송 완료로 기록") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("고객에게 견적을 보내셨나요?") },
        text = { Text("문자·메일·PDF를 실제로 보낸 뒤 확인해주세요. 오늘 견적이 1건 증가하며 다시 눌러도 중복 집계하지 않습니다.") },
        confirmButton = { TextButton(onClick = {
            confirm = false; busy = true
            scope.launch {
                try {
                    onQuoteSent()
                    viewModel.markQuoteSent(number)
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "발송 기록 저장에 실패했습니다." }
                finally { busy = false }
            }
        }) { Text("발송 완료") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("돌아가기") } })
}
