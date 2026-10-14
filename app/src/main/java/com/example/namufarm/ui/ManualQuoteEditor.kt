package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.quotation.*
import com.example.namufarm.ui.components.*
import com.google.gson.Gson

private val draftSaver = Saver<SellerQuoteDraft, String>(
    save = { Gson().toJson(it) }, restore = { Gson().fromJson(it, SellerQuoteDraft::class.java) })

private val editorGreen = Color(0xFF2F5D3F)
private val editorMuted = Color(0xFF7A8680)
private val editorBorder = Color(0xFFE3E9E5)


private fun displayEditorPhone(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    return when {
        digits.length == 11 && digits.startsWith("01") ->
            digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7)
        digits.length == 10 && digits.startsWith("01") ->
            digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6)
        else -> raw.ifBlank { "전화번호 필요" }
    }
}

@Composable
private fun EditorInfoRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(84.dp), color = editorMuted, fontSize = 15.sp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun EditorChannelChip(label: String, selectedColor: Color, textColor: Color) {
    Surface(shape = RoundedCornerShape(50), color = selectedColor) {
        Text(label, Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
            color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Fullscreen quote editor with the same layout as the delivery screen. */
@Composable
private fun EditorDialog(
    title: String, subtitle: String, onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().imePadding().testTag("namu_dialog"),
            color = Color(0xFFF5F6F3)) {
            Column {
                Surface(color = Color.White) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.ArrowBack, "견적 작성 닫기")
                        }
                        Column(Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            Text(subtitle, fontSize = 12.sp, color = editorMuted)
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                Surface(color = Color.White,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = actions)
                }
            }
        }
    }
}

@Composable
internal fun ManualQuoteEditor(request: WebQuoteRequestEntity?, saving: Boolean, message: String?,
    onDismiss: () -> Unit, onSave: (SellerQuoteDraft, QuoteDelivery) -> Unit, initialDraft: SellerQuoteDraft? = null,
    draftKey: String? = request?.requestId, onDeleteRequest: (() -> Unit)? = null) {
    val context = LocalContext.current
    val firstRequest = request != null && initialDraft == null
    val manualEntry = request == null
    var draft by rememberSaveable(draftKey, stateSaver = draftSaver) {
        mutableStateOf(initialDraft ?: SellerQuoteDraft.from(request, QuoteIssuerSettings(context).load()))
    }
    // Homepage requests are immutable except their selling prices and delivery fee.
    // Keep manual-entry contact/plant fields available for quotes created locally.
    var manualContactExpanded by rememberSaveable(draftKey) { mutableStateOf(manualEntry) }
    var expandedRow by rememberSaveable(draftKey) { mutableIntStateOf(-1) }
    var specDetails by remember { mutableStateOf<SellerQuoteRow?>(null) }
    var messageExpanded by remember { mutableStateOf(false) }
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

    // Use the delivery-style action area without changing the existing save callbacks.
    EditorDialog("견적 작성", subtitle, onDismiss = ::close, actions = {
        (error ?: message?.takeIf { it.startsWith("❌") })?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { submit(QuoteDelivery.SMS) }, enabled = !saving,
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = editorGreen)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("● 발송 대기", fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
                    Text("문자 보내기", fontSize = 15.sp)
                }
            }
            Button(onClick = { submit(QuoteDelivery.EMAIL) }, enabled = !saving,
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3563A8))) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("● 발송 대기", fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
                    Text("메일 보내기", fontSize = 15.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onDeleteRequest != null) {
                OutlinedButton(onClick = { deleteConfirm = true }, enabled = !saving,
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFFFFF0F0), contentColor = Color(0xFFB94545))) {
                    Icon(Icons.Outlined.Delete, "견적 요청 삭제")
                }
            }
            Button(onClick = { submit(QuoteDelivery.SAVE) }, enabled = !saving,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC8641F))) {
                Text(if (saving) "저장 중…" else if (firstRequest) "견적확정" else "견적 저장",
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("quote_editor_body")
            .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Surface(shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.5.dp, editorBorder), color = Color.White) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    EditorInfoRow("주문번호") {
                        Text(subtitle, fontSize = 16.sp, color = Color(0xFF1B2A22),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    EditorInfoRow("고객명") {
                        Text(draft.recipient.customerName.ifBlank { "고객" },
                            fontSize = 17.sp, color = Color(0xFF1B2A22),
                            fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    EditorInfoRow("연락처") {
                        Text(displayEditorPhone(draft.phone), fontSize = 17.sp,
                            color = Color(0xFF1B2A22), maxLines = 1)
                    }
                    EditorInfoRow("수신방법") {
                        if (draft.recipient.replyChannel != "email") {
                            EditorChannelChip("문자", Color(0xFFE3EEE6), editorGreen)
                        }
                        if (draft.recipient.replyChannel != "sms") {
                            EditorChannelChip("메일", Color(0xFFE4ECF8), Color(0xFF2B5287))
                        }
                    }
                    if (draft.recipient.formalQuoteRequested) {
                        EditorInfoRow("견적서") {
                            EditorChannelChip("공식견적 요청",
                                Color(0xFFE4ECF8), Color(0xFF2B5287))
                        }
                    }
                    if (manualEntry) {
                        TextButton(onClick = { manualContactExpanded = !manualContactExpanded },
                            modifier = Modifier.align(Alignment.End), enabled = !saving) {
                            Text(if (manualContactExpanded) "연락처 접기" else "연락처 입력")
                        }
                        if (manualContactExpanded) {
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
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("품목별 판매 단가", modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("${draft.rows.size}품목", color = Color(0xFF53695B),
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("단가 증감", style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF456552))
                Spacer(Modifier.width(6.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(500, 1000, 10000).forEach { value ->
                        Surface(onClick = { step = value }, enabled = !saving,
                            shape = RoundedCornerShape(22.dp),
                            color = if (step == value) editorGreen else Color.White,
                            border = BorderStroke(0.5.dp,
                                if (step == value) editorGreen else editorBorder)) {
                            Text(if (value == 10000) "1만원" else money(value.toLong()),
                                Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
                                color = if (step == value) Color.White else editorMuted,
                                fontSize = 12.sp)
                        }
                    }
                }
            }

            draft.rows.forEachIndexed { index, row ->
                key(index) {
                    fun update(value: SellerQuoteRow) {
                        draft = draft.copy(rows = draft.rows.toMutableList().also { it[index] = value })
                    }
                    Surface(shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(0.5.dp, editorBorder),
                        color = Color.White) {
                        Column(Modifier.fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${index+1}. ${row.name.ifBlank { "새 품목" }}",
                                        modifier = Modifier.weight(1f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold)
                                    Surface(shape = RoundedCornerShape(50),
                                        color = Color(0xFFE3EEE6)) {
                                        Text("${row.quantity.ifBlank { "0" }}주",
                                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            color = editorGreen, fontSize = 13.sp)
                                    }
                                    if (manualEntry) {
                                        TextButton(onClick = {
                                            expandedRow = if (expandedRow == index) -1 else index
                                        }, enabled = !saving) { Text("수정") }
                                    }
                                }
                                if (row.spec.isNotBlank()) {
                                    Text("희망규격: ${row.spec}",
                                        modifier = Modifier.clickable { specDetails = row },
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp, color = editorMuted)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("1주 가격", style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF5E7566))
                                FilledTonalIconButton(onClick = {
                                    update(row.copy(price = adjustedQuotePrice(row.price, -step)))
                                }, enabled = !saving, modifier = Modifier.size(42.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = Color(0xFFE6F1E8),
                                        contentColor = Color(0xFF205943))) {
                                    Icon(Icons.Outlined.Remove, "${index+1}번 단가 내리기")
                                }
                                OutlinedTextField(row.price, {
                                    if (it.all(Char::isDigit) && it.length <= 10)
                                        update(row.copy(price = it))
                                }, placeholder = {
                                    Text("0", style = MaterialTheme.typography.bodySmall)
                                }, singleLine = true, enabled = !saving,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = Color.White),
                                    textStyle = MaterialTheme.typography.titleMedium.copy(
                                        textAlign = TextAlign.Center),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f).testTag("unit_price_$index"))
                                FilledTonalIconButton(onClick = {
                                    update(row.copy(price = adjustedQuotePrice(row.price, step)))
                                }, enabled = !saving, modifier = Modifier.size(42.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = Color(0xFFE6F1E8),
                                        contentColor = Color(0xFF205943))) {
                                    Icon(Icons.Outlined.Add, "${index+1}번 단가 올리기")
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("소계", color = Color(0xFF53695B),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall)
                                val subtotal = (row.quantity.toIntOrNull()?.toLong() ?: 0L) *
                                    (row.price.toIntOrNull()?.toLong() ?: 0L)
                                Text(money(subtotal), color = Color(0xFF205943),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall)
                            }
                            if (manualEntry && expandedRow == index) {
                                ManualField("묘목명 *", row.name, { update(row.copy(name = it)) },
                                    saving, limit = 200)
                                ManualField("규격", row.spec, { update(row.copy(spec = it)) },
                                    saving, limit = 200)
                                ManualField("수량 (주) *", row.quantity, { update(row.copy(quantity = it)) },
                                    saving, KeyboardType.Number)
                                if (draft.rows.size > 1) TextButton(onClick = {
                                    draft = draft.copy(rows = draft.rows.filterIndexed { i, _ -> i != index })
                                    expandedRow = -1
                                }, enabled = !saving) { Text("이 품목 삭제") }
                            }
                        }
                    }
                }
            }
            if (manualEntry) {
                TextButton(onClick = { draft = draft.copy(rows = draft.rows + SellerQuoteRow())
                    expandedRow = draft.rows.lastIndex
                }, enabled = !saving && draft.rows.size < 50,
                    modifier = Modifier.align(Alignment.End)) { Text("+ 품목 추가") }
            }

            // Preserve 500-won delivery adjustments independently of the item-price step.
            Surface(shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.5.dp, editorBorder), color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("배송비", color = Color(0xFF1B2A22), fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.background(Color(0xFFEEF1EF), RoundedCornerShape(10.dp))
                        .padding(3.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        listOf(false to "후불", true to "선불").forEach { (value, label) ->
                            Surface(onClick = { draft = draft.copy(prepaid = value) },
                                enabled = !saving, shape = RoundedCornerShape(8.dp),
                                color = if (draft.prepaid == value) editorGreen else Color.Transparent) {
                                Text(label, Modifier.padding(horizontal = 9.dp, vertical = 10.dp),
                                    color = if (draft.prepaid == value) Color.White else editorMuted,
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    if (draft.prepaid) {
                        Surface(onClick = {
                            draft = draft.copy(fee = adjustedQuotePrice(draft.fee, -500))
                        }, enabled = !saving, shape = androidx.compose.foundation.shape.CircleShape,
                            color = Color(0xFFE3EEE6)) {
                            Box(Modifier.size(37.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Remove, "배송비 500원 내리기",
                                    tint = editorGreen, modifier = Modifier.size(22.dp))
                            }
                        }
                        Text(money(draft.shipping().toLong()),
                            color = Color(0xFF1B2A22), fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Surface(onClick = {
                            draft = draft.copy(fee = adjustedQuotePrice(draft.fee, 500))
                        }, enabled = !saving, shape = androidx.compose.foundation.shape.CircleShape,
                            color = Color(0xFFE3EEE6)) {
                            Box(Modifier.size(37.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Add, "배송비 500원 올리기",
                                    tint = editorGreen, modifier = Modifier.size(22.dp))
                            }
                        }
                    } else {
                        Text("착불", color = editorMuted, fontSize = 14.sp)
                    }
                }
            }
            Surface(shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.5.dp, editorBorder),
                color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.SpeakerNotes, null,
                        tint = editorMuted, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("요청사항", color = editorMuted, fontSize = 13.sp)
                        Text(request?.message?.ifBlank { "없음" } ?: "없음",
                            modifier = Modifier.clickable { if (!request?.message.isNullOrBlank()) messageExpanded = true },
                            fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val itemsTotal = draft.rows.sumOf {
                (it.quantity.toLongOrNull() ?: 0L).coerceAtLeast(0) *
                    (it.price.toLongOrNull() ?: 0L).coerceAtLeast(0)
            }
            Surface(shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.5.dp, Color(0xFFECE6C8)),
                color = Color(0xFFFAF8EC)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("최종 견적 금액", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (draft.prepaid)
                        "품목 " + money(itemsTotal) + " + 배송비 " + money(draft.shipping().toLong())
                        else "품목 " + money(itemsTotal) + " · 배송비 후불(착불)",
                        color = editorMuted, fontSize = 12.sp)
                    Text(amount?.let(::money) ?: "단가 입력 필요",
                        color = editorGreen, fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().background(Color(0xFFFFF3D6),
                        RoundedCornerShape(10.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Info, null, tint = Color(0xFF8A5A00),
                            modifier = Modifier.size(18.dp))
                        Text("보낸 뒤 견적발송완료를 눌러주세요.",
                            color = Color(0xFF6B4A0A), fontSize = 12.sp)
                    }
                }
            }
        }
    }
    specDetails?.let { item ->
        AlertDialog(onDismissRequest = { specDetails = null },
            title = { Text("${item.name} · ${item.quantity}주") },
            text = { Text("희망규격: ${item.spec}") },
            confirmButton = { TextButton(onClick = { specDetails = null }) { Text("닫기") } })
    }
    if (messageExpanded) AlertDialog(onDismissRequest = { messageExpanded = false },
        title = { Text("고객 요청사항") },
        text = { Text(request?.message.orEmpty()) },
        confirmButton = { TextButton(onClick = { messageExpanded = false }) { Text("닫기") } })
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
