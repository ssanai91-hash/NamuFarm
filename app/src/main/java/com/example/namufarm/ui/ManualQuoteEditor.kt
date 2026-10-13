package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    onDismiss: () -> Unit, onSave: (SellerQuoteDraft, QuoteDelivery) -> Unit, initialDraft: SellerQuoteDraft? = null,
    draftKey: String? = request?.requestId, onDeleteRequest: (() -> Unit)? = null) {
    val context = LocalContext.current
    val firstRequest = request != null && initialDraft == null
    var draft by rememberSaveable(draftKey, stateSaver = draftSaver) {
        mutableStateOf(initialDraft ?: SellerQuoteDraft.from(request, QuoteIssuerSettings(context).load()))
    }
    var contactExpanded by rememberSaveable(draftKey) { mutableStateOf(request == null) }
    var shippingExpanded by rememberSaveable(draftKey) { mutableStateOf(false) }
    var step by rememberSaveable { mutableIntStateOf(1000) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val initial = remember(draftKey) {
        initialDraft ?: SellerQuoteDraft.from(request, QuoteIssuerSettings(context).load())
    }

    fun close() {
        if (!saving) {
            if (draft != initial) discard = true else onDismiss()
        }
    }
    fun submit(action: QuoteDelivery) {
        try {
            draft.validate(action)
            error = null
            onSave(draft, action)
        } catch (e: Exception) {
            error = if (e is ArithmeticException) "견적 수량이나 금액이 너무 큽니다." else e.message
        }
    }
    val amount = runCatching {
        com.example.namufarm.domain.order.OrderAmounts.calculate(
            draft.rows.map { it.line() }, draft.prepaid, draft.shipping()
        ).price.toLong() + if (draft.recipient.formalQuoteRequested) draft.options.taxAmount() else 0L
    }.getOrNull()
    val subtitle = request?.receiptNumber?.takeIf { it.isNotBlank() }
        ?: request?.let { "${it.customerName} · ${it.region}" }
        ?: "빠른 견적"

    // NamuDialog keeps actions fixed below the scrollable content, including
    // when the numeric keyboard is visible.
    NamuDialog("견적 작성", subtitle, onDismiss = ::close, actions = {
        (error ?: message?.takeIf { it.startsWith("❌") })?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("최종 견적 금액", style = MaterialTheme.typography.titleMedium)
            Text(amount?.let(::money) ?: "단가 입력 필요",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        if (firstRequest) {
            Button(onClick = { submit(QuoteDelivery.SAVE) },
                enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(if (saving) "견적확정 중…" else "견적확정",
                    style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { submit(QuoteDelivery.SMS) }, enabled = !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text("문자 보내기")
                }
                Button(onClick = { submit(QuoteDelivery.EMAIL) }, enabled = !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text("메일 보내기")
                }
            }
            TextButton(onClick = { submit(QuoteDelivery.SAVE) }, enabled = !saving,
                modifier = Modifier.align(Alignment.End)) { Text("저장만") }
        }
        if (onDeleteRequest != null) {
            TextButton(onClick = { deleteConfirm = true }, enabled = !saving,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error)) {
                Text("견적 요청 삭제")
            }
        }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("quote_editor_body")
            .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // Contact and reply preference occupy just two lines normally.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(draft.recipient.customerName.ifBlank { "고객" },
                    modifier = Modifier.weight(0.35f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(draft.phone.ifBlank { "전화번호 필요" },
                    modifier = Modifier.weight(0.51f), maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { contactExpanded = !contactExpanded },
                    modifier = Modifier.size(44.dp), enabled = !saving) {
                    Icon(Icons.Outlined.Edit, "고객 정보 수정")
                }
            }
            Text(buildString {
                append("회신 · ")
                append(when (request?.replyChannel) {
                    "email" -> "메일"
                    "both" -> "문자 + 메일"
                    else -> "문자"
                })
                append(" · ")
                append(if (draft.recipient.customerEmail.isBlank()) "이메일 미등록" else "이메일 등록됨")
                if (request?.formalQuoteRequested == true) append(" · 공식 견적서 요청")
            }, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            if (contactExpanded) {
                Surface(shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ManualField("고객 성함", draft.recipient.customerName, {
                            draft = draft.copy(recipient = draft.recipient.copy(customerName = it))
                        }, saving, limit = 50)
                        ManualField("전화번호 *", draft.phone, {
                            draft = draft.copy(phone = it)
                        }, saving, KeyboardType.Phone)
                        ManualField("견적 수신인·회사명", draft.recipient.recipientName, {
                            draft = draft.copy(recipient = draft.recipient.copy(recipientName = it))
                        }, saving, limit = 100)
                        ManualField("이메일", draft.recipient.customerEmail, {
                            draft = draft.copy(recipient = draft.recipient.copy(customerEmail = it))
                        }, saving, KeyboardType.Email)
                        if (request?.message?.isNotBlank() == true) {
                            Text("요청사항 · ${request.message}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("품목별 판매 단가", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("${draft.rows.size}품목", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("단가 증감", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(100, 1000, 10000).forEach { value ->
                        FilterChip(selected = step == value, onClick = { step = value },
                            label = { Text(money(value.toLong()),
                                style = MaterialTheme.typography.labelMedium) },
                            enabled = !saving)
                    }
                }
            }
            draft.rows.forEachIndexed { index, row ->
                key(index) {
                    var details by rememberSaveable { mutableStateOf(row.name.isBlank()) }
                    fun update(value: SellerQuoteRow) {
                        draft = draft.copy(rows = draft.rows.toMutableList().also { it[index] = value })
                    }
                    Surface(shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}. ${row.name.ifBlank { "새 품목" }}",
                                    modifier = Modifier.weight(0.44f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${row.spec.ifBlank { "규격 미정" }} · ${row.quantity.ifBlank { "0" }}주",
                                    modifier = Modifier.weight(0.56f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton(onClick = { details = !details },
                                    modifier = Modifier.size(44.dp), enabled = !saving) {
                                    Icon(Icons.Outlined.Edit, "${index + 1}번 품목 수정")
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                FilledTonalIconButton(onClick = {
                                    update(row.copy(price = adjustedQuotePrice(row.price, -step)))
                                }, enabled = !saving, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Outlined.Remove, "${index + 1}번 단가 내리기")
                                }
                                OutlinedTextField(row.price,
                                    { if (it.all(Char::isDigit) && it.length <= 10) update(row.copy(price = it)) },
                                    placeholder = { Text("판매 단가 (원)",
                                        style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                                    singleLine = true, enabled = !saving,
                                    textStyle = MaterialTheme.typography.titleMedium.copy(
                                        textAlign = TextAlign.Center),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f).testTag("unit_price_$index"))
                                FilledTonalIconButton(onClick = {
                                    update(row.copy(price = adjustedQuotePrice(row.price, step)))
                                }, enabled = !saving, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Outlined.Add, "${index + 1}번 단가 올리기")
                                }
                            }
                            if (details) {
                                ManualField("묘목명 *", row.name, { update(row.copy(name = it)) },
                                    saving, limit = 200)
                                ManualField("규격", row.spec, { update(row.copy(spec = it)) },
                                    saving, limit = 200)
                                ManualField("수량 (주) *", row.quantity, { update(row.copy(quantity = it)) },
                                    saving, KeyboardType.Number)
                                if (draft.rows.size > 1) {
                                    TextButton(onClick = {
                                        draft = draft.copy(rows = draft.rows.filterIndexed { i, _ -> i != index })
                                    }, enabled = !saving) { Text("이 품목 삭제") }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { draft = draft.copy(rows = draft.rows + SellerQuoteRow()) },
                enabled = !saving && draft.rows.size < 50,
                modifier = Modifier.align(Alignment.End)) { Text("+ 품목 추가") }

            OutlinedCard(onClick = { shippingExpanded = !shippingExpanded },
                enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("배송비", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    Text(if (draft.prepaid) "선불 · ${money(draft.shipping().toLong())}"
                        else "착불 · 합계 제외",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Outlined.ChevronRight, "배송 조건 수정")
                }
            }
            if (shippingExpanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !draft.prepaid,
                        onClick = { draft = draft.copy(prepaid = false) },
                        label = { Text("착불") }, enabled = !saving)
                    FilterChip(selected = draft.prepaid,
                        onClick = { draft = draft.copy(prepaid = true) },
                        label = { Text("선불") }, enabled = !saving)
                }
                if (draft.prepaid) ManualField("선불 배송비 (원)", draft.fee, {
                    draft = draft.copy(fee = it)
                }, saving, KeyboardType.Number)
                else Text("착불 배송비는 견적 합계에 포함되지 않습니다.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (deleteConfirm) AlertDialog(
        onDismissRequest = { deleteConfirm = false },
        title = { Text("견적 요청을 삭제할까요?") },
        text = { Text("선택한 견적 요청을 서버와 휴대폰에서 삭제합니다. 삭제 후 되돌릴 수 없습니다.") },
        confirmButton = {
            Button(onClick = {
                deleteConfirm = false
                onDeleteRequest?.invoke()
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("삭제하기")
            }
        },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("취소") } }
    )
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
