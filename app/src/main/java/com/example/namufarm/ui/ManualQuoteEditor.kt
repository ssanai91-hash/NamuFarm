package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.quotation.*
import com.example.namufarm.ui.components.*
import com.google.gson.Gson

private val draftSaver = Saver<SellerQuoteDraft, String>(
    save = { Gson().toJson(it) }, restore = { Gson().fromJson(it, SellerQuoteDraft::class.java) })

@Composable
internal fun ManualQuoteEditor(request: WebQuoteRequestEntity?, saving: Boolean, message: String?,
    onDismiss: () -> Unit, onSave: (SellerQuoteDraft, QuoteDelivery) -> Unit, initialDraft: SellerQuoteDraft? = null, draftKey: String? = request?.requestId) {
    val context = LocalContext.current
    val firstRequest = request != null && initialDraft == null
    var draft by rememberSaveable(draftKey, stateSaver = draftSaver) {
        mutableStateOf(initialDraft ?: SellerQuoteDraft.from(request, QuoteIssuerSettings(context).load()))
    }
    var contactExpanded by rememberSaveable { mutableStateOf(request == null) }
    var step by rememberSaveable { mutableIntStateOf(1000) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val initial = remember(draftKey) { initialDraft ?: SellerQuoteDraft.from(request, QuoteIssuerSettings(context).load()) }
    fun close() { if (!saving) { if (draft != initial) discard = true else onDismiss() } }
    fun submit(action: QuoteDelivery) {
        try { draft.validate(action); error = null; onSave(draft, action) }
        catch (e: Exception) { error = if (e is ArithmeticException) "견적 수량이나 금액이 너무 큽니다." else e.message }
    }
    val amount = runCatching { com.example.namufarm.domain.order.OrderAmounts.calculate(draft.rows.map { it.line() }, draft.prepaid, draft.shipping()).price.toLong() +
        if (draft.recipient.formalQuoteRequested) draft.options.taxAmount() else 0L }.getOrNull()
    NamuDialog("견적 작성", request?.let { "${it.customerName} · ${it.region}" } ?: "빠른 견적 · 고객과 품목 직접 입력", onDismiss = ::close, actions = {
        (error ?: message?.takeIf { it.startsWith("❌") })?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("최종 견적 금액", style = MaterialTheme.typography.labelMedium)
                Text(amount?.let(::money) ?: "단가를 입력해주세요", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (!firstRequest) TextButton(onClick = { submit(QuoteDelivery.SAVE) }, enabled = !saving) {
                Text("저장만")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(draft.recipient.formalQuoteRequested, { draft = draft.copy(recipient = draft.recipient.copy(formalQuoteRequested = it)) }, enabled = !saving)
            Column {
                Text("공식 견적서 첨부", style = MaterialTheme.typography.labelLarge)
                Text(if (draft.recipient.formalQuoteRequested) "문자: 이미지 · 메일: PDF" else "견적 내용을 텍스트로 보냅니다", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (firstRequest) {
            Button(onClick = { submit(QuoteDelivery.SAVE) }, enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text(if (saving) "견적 저장 중…" else "견적확정 · 작업대로 이동")
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { submit(QuoteDelivery.SMS) }, enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(Icons.Outlined.Sms, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("문자 보내기")
            }
            Button(onClick = { submit(QuoteDelivery.EMAIL) }, enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(Icons.Outlined.MailOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (saving) "저장 중…" else "메일 보내기")
            }
        }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("quote_editor_body").padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            QuoteBox("받는 사람 · 연락처") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(draft.recipient.customerName.ifBlank { "고객 정보 입력" }, style = MaterialTheme.typography.titleMedium)
                        Text(draft.phone.ifBlank { "전화번호를 입력해주세요" }, style = MaterialTheme.typography.bodyMedium)
                        if (draft.recipient.customerEmail.isNotBlank()) Text(draft.recipient.customerEmail, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { contactExpanded = !contactExpanded }, enabled = !saving) { Text(if (contactExpanded) "접기" else "수정") }
                }
                if (contactExpanded) {
                    ManualField("고객 성함", draft.recipient.customerName, { draft = draft.copy(recipient = draft.recipient.copy(customerName = it)) }, saving, limit = 50)
                    ManualField("전화번호 *", draft.phone, { draft = draft.copy(phone = it) }, saving, KeyboardType.Phone)
                    ManualField("견적 수신인·회사명", draft.recipient.recipientName, { draft = draft.copy(recipient = draft.recipient.copy(recipientName = it)) }, saving, limit = 100)
                    ManualField("이메일", draft.recipient.customerEmail, { draft = draft.copy(recipient = draft.recipient.copy(customerEmail = it)) }, saving, KeyboardType.Email)
                }
                request?.let {
                    if (it.message.isNotBlank()) Text("요청사항 · ${it.message}", style = MaterialTheme.typography.bodySmall)
                    Text("희망 회신 · ${when (it.replyChannel) { "email" -> "메일"; "both" -> "문자 + 메일"; else -> "문자" }}${if (it.formalQuoteRequested) " · 공식 견적서" else ""}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column {
                NamuSectionTitle("품목별 판매 단가", "버튼으로 조정하거나 금액을 직접 입력하세요")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 1000, 10000).forEach { value -> FilterChip(step == value, { step = value }, { Text(money(value.toLong())) }, enabled = !saving) }
                }
            }
            draft.rows.forEachIndexed { index, row ->
                key(index) {
                    var details by rememberSaveable { mutableStateOf(row.name.isBlank()) }
                    fun update(value: SellerQuoteRow) { draft = draft.copy(rows = draft.rows.toMutableList().also { it[index] = value }) }
                    QuoteBox("${index + 1}. ${row.name.ifBlank { "새 품목" }}") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${row.spec.ifBlank { "규격 미지정" }} · ${row.quantity.ifBlank { "0" }}주", Modifier.weight(1f))
                            TextButton(onClick = { details = !details }, enabled = !saving) { Text(if (details) "접기" else "품목 수정") }
                        }
                        if (details) {
                            ManualField("묘목명 *", row.name, { update(row.copy(name = it)) }, saving, limit = 200)
                            ManualField("규격", row.spec, { update(row.copy(spec = it)) }, saving, limit = 200)
                            ManualField("수량 (주) *", row.quantity, { update(row.copy(quantity = it)) }, saving, KeyboardType.Number)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalIconButton(onClick = { update(row.copy(price = adjustedQuotePrice(row.price, -step))) }, enabled = !saving, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Remove, "${index + 1}번 단가 내리기") }
                            OutlinedTextField(row.price, { if (it.all(Char::isDigit) && it.length <= 10) update(row.copy(price = it)) }, label = { Text("판매 단가 (원)") }, singleLine = true, enabled = !saving,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("unit_price_$index"))
                            FilledTonalIconButton(onClick = { update(row.copy(price = adjustedQuotePrice(row.price, step))) }, enabled = !saving, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Add, "${index + 1}번 단가 올리기") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val subtotal = (row.quantity.toIntOrNull()?.toLong() ?: 0) * (row.price.toIntOrNull()?.toLong() ?: 0)
                            Text("품목 합계  ${money(subtotal)}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            if (details && draft.rows.size > 1) TextButton(onClick = { draft = draft.copy(rows = draft.rows.filterIndexed { i, _ -> i != index }) }, enabled = !saving) { Text("삭제") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { draft = draft.copy(rows = draft.rows + SellerQuoteRow()) }, enabled = !saving && draft.rows.size < 50, modifier = Modifier.fillMaxWidth()) { Text("+ 품목 추가") }
            QuoteBox("배송 조건") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!draft.prepaid, { draft = draft.copy(prepaid = false) }, { Text("착불") }, enabled = !saving)
                    FilterChip(draft.prepaid, { draft = draft.copy(prepaid = true) }, { Text("선불") }, enabled = !saving)
                }
                if (draft.prepaid) ManualField("선불 배송비 (원)", draft.fee, { draft = draft.copy(fee = it) }, saving, KeyboardType.Number)
                else Text("배송비는 견적 합계에 포함되지 않습니다", style = MaterialTheme.typography.bodySmall)
            }
            if (draft.recipient.formalQuoteRequested) QuoteIssueFields(draft.options, saving) { draft = draft.copy(options = it) }
            Text(if (firstRequest) "견적확정 후 작업대에서 택배비와 공식견적서, 문자·메일을 처리해요."
                else "실제 문자·메일 발송은 해당 앱에서 확인해주세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("작성을 마칠까요?") }, text = { Text("입력한 내용이 아직 저장되지 않았습니다.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("저장하지 않고 닫기") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("계속 작성") } })
}

@Composable
internal fun QuoteIssueFields(options: QuoteIssueOptions, saving: Boolean, onChange: (QuoteIssueOptions) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    QuoteBox("공식 견적서 발행 정보") {
        Text("${options.issuer.businessName} · ${options.issuer.representative}")
        Text("${options.issuer.businessNumber}\n${options.validUntil} · 추가 세액 ${options.tax}원", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { expanded = !expanded }, enabled = !saving) { Text(if (expanded) "발행 정보 접기" else "공급자·세액·계좌 확인 / 수정") }
        if (expanded) {
            val issuer = options.issuer
            ManualField("상호", issuer.businessName, { onChange(options.copy(issuer = issuer.copy(businessName = it))) }, saving, limit = 50)
            ManualField("사업자등록번호", issuer.businessNumber, { onChange(options.copy(issuer = issuer.copy(businessNumber = it))) }, saving, limit = 20)
            ManualField("대표자", issuer.representative, { onChange(options.copy(issuer = issuer.copy(representative = it))) }, saving, limit = 50)
            ManualField("사업장 주소", issuer.address, { onChange(options.copy(issuer = issuer.copy(address = it))) }, saving, limit = 150)
            ManualField("공급자 전화번호", issuer.phone, { onChange(options.copy(issuer = issuer.copy(phone = it))) }, saving, KeyboardType.Phone, 30)
            ManualField("추가 세액 (원)", options.tax, { onChange(options.copy(tax = it)) }, saving, KeyboardType.Number)
            ManualField("견적 유효기간", options.validUntil, { onChange(options.copy(validUntil = it)) }, saving, limit = 100)
            ManualField("입금 계좌 · 선택", options.account, { onChange(options.copy(account = it)) }, saving, limit = 500)
            ManualField("안내 문구", options.notes, { onChange(options.copy(notes = it)) }, saving, limit = 500)
        }
    }
}

@Composable
internal fun QuoteBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ManualField(label: String, value: String, onValue: (String) -> Unit, saving: Boolean, keyboard: KeyboardType = KeyboardType.Text, limit: Int = 254) {
    OutlinedTextField(value, { if (keyboard != KeyboardType.Number || it.all(Char::isDigit)) onValue(it.take(if (keyboard == KeyboardType.Number) 10 else limit)) }, label = { Text(label) },
        singleLine = true, enabled = !saving, keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth())
}
