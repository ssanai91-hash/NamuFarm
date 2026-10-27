package com.example.namufarm.quotemode

import com.example.namufarm.ui.theme.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import com.example.namufarm.data.WebQuoteItem
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.quotation.*
import com.example.namufarm.quotemode.components.*
import com.google.gson.Gson

private val draftSaver = Saver<SellerQuoteDraft, String>(
    save = { Gson().toJson(it) }, restore = { Gson().fromJson(it, SellerQuoteDraft::class.java) })

private val editorGreen = NamuColors.primary
private val editorMuted = NamuColors.muted
private val editorBorder = NamuColors.border


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

/** Seller attestation only. Opening an external app never marks a quote as sent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmQuoteSentDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit,
    openedChannels: Set<String> = emptySet(), phone: String = "", email: String = "") {
    val suggested = when {
        openedChannels.containsAll(setOf("sms", "email")) -> "both"
        "sms" in openedChannels -> "sms"
        "email" in openedChannels -> "email"
        else -> "none"
    }
    var selected by remember(suggested) { mutableStateOf(suggested) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("고객에게 보냈나요?", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("문자·메일 앱을 연 것만으로는 발송 완료가 아니에요. 실제로 보냈을 때만 견적완료를 눌러주세요.", fontSize = 15.sp)
            listOf("sms" to "문자로 보냈어요", "email" to "메일로 보냈어요", "both" to "문자와 메일 모두 보냈어요")
                .forEach { (value, label) ->
                    Surface(shape = RoundedCornerShape(12.dp), color = if (selected == value) NamuColors.soft else Color.White,
                        border = BorderStroke(1.dp, if (selected == value) NamuColors.primary else NamuColors.border)) {
                        Column(Modifier.fillMaxWidth().clickable { selected = value }.padding(8.dp)) {
                            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selected == value, onClick = { selected = value })
                                Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            }
                            if (selected == value && openedChannels.isNotEmpty()) {
                                val hints = buildList {
                                    if (value in setOf("sms", "both") && "sms" in openedChannels) add("방금 문자 앱을 열었어요 · $phone")
                                    if (value in setOf("email", "both") && "email" in openedChannels) add("방금 메일 앱을 열었어요 · $email")
                                }
                                hints.forEach { Text(it, Modifier.padding(start = 12.dp, bottom = 6.dp), fontSize = 13.sp, color = NamuColors.muted) }
                            }
                        }
                    }
                }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NamuSecondaryButton("아직 안 보냈어요", onDismiss, Modifier.weight(1f))
                NamuPrimaryButton("견적완료", { onConfirm(selected) }, Modifier.weight(1f), selected != "none")
            }
        }
    }
}

/** Fullscreen quote editor with the same layout as the delivery screen. */
@Composable
private fun EditorDialog(
    title: String, subtitle: String, onDismiss: () -> Unit,
    onDeleteRequest: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().imePadding().testTag("namu_dialog"),
            color = NamuColors.background) {
            Column {
                Surface(color = Color.White) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.ArrowBack, "견적 작성 닫기")
                        }
                        Column(Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(subtitle, fontSize = 13.sp, color = editorMuted)
                        }
                        if (onDeleteRequest != null) Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "더보기") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("견적요청 삭제") }, onClick = { menu = false; onDeleteRequest() },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                Surface(color = Color.White, shadowElevation = 4.dp,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        content = actions)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ManualQuoteEditor(request: WebQuoteRequestEntity?, saving: Boolean, message: String?,
    onDismiss: () -> Unit, onSave: (SellerQuoteDraft, QuoteDelivery) -> Unit, initialDraft: SellerQuoteDraft? = null,
    draftKey: String? = request?.requestId, onDeleteRequest: (() -> Unit)? = null,
    verifiedDelivery: QuoteDeliveryRecord? = null, verifyDelivery: Boolean = false,
    onConfirmSent: ((SellerQuoteDraft, String) -> Unit)? = null,
    onPrepareDocument: suspend (SellerQuoteDraft) -> Unit = {}, messageIsError: Boolean = false,
    openedChannels: Set<String> = emptySet(), confirmedEdit: Boolean = false) {
    val context = LocalContext.current
    val manualEntry = request == null && !confirmedEdit
    val requestedItems = remember(request?.itemsJson) {
        runCatching { Gson().fromJson(request?.itemsJson, Array<WebQuoteItem>::class.java)?.toList().orEmpty() }
            .getOrDefault(emptyList())
    }
    var draft by rememberSaveable(draftKey, stateSaver = draftSaver) {
        mutableStateOf(initialDraft ?: SellerQuoteDraft.from(request, QuoteIssuerSettings(context).load()))
    }
    // Homepage requests are immutable except their selling prices and delivery fee.
    // Keep manual-entry contact/plant fields available for quotes created locally.
    var formalDraft by remember { mutableStateOf<SellerQuoteDraft?>(null) }
    var formalExpanded by rememberSaveable(draftKey) { mutableStateOf(false) }
    var issueDetails by rememberSaveable(draftKey) { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var confirmSent by remember { mutableStateOf(false) }
    var formalOpenedChannels by remember(draftKey) { mutableStateOf(emptySet<String>()) }
    var deliveryRequired by remember { mutableStateOf(false) }
    var initial by remember(draftKey) {
        mutableStateOf(initialDraft ?: SellerQuoteDraft.from(request, QuoteIssuerSettings(context).load()))
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

    EditorDialog(if (confirmedEdit) "확정견적 수정" else "견적 작성", subtitle, onDismiss = ::close,
        onDeleteRequest = onDeleteRequest?.let { { deleteConfirm = true } }, actions = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val quantity = draft.rows.sumOf { it.quantity.toLongOrNull() ?: 0L }
            Text("합계 · ${String.format("%,d", quantity)}주 · " +
                if (draft.prepaid) "택배비 선불 ${money(draft.shipping().toLong())}" else "택배비 착불",
                Modifier.weight(1f), fontSize = 14.sp, color = NamuColors.muted)
            Text(amount?.let(::money) ?: "단가 입력 필요", color = NamuColors.dark,
                fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        (error ?: message?.takeIf { messageIsError })?.let {
            Text(it, color = NamuColors.danger, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (confirmedEdit) {
                NamuSecondaryButton("취소", ::close, Modifier.weight(1f), !saving)
                NamuPrimaryButton("수정 저장", { submit(QuoteDelivery.SAVE) }, Modifier.weight(1.6f), !saving)
            } else {
            NamuSecondaryButton("문자 앱 열기", { submit(QuoteDelivery.SMS) }, Modifier.weight(1f), !saving)
            NamuSecondaryButton("메일 앱 열기", { submit(QuoteDelivery.EMAIL) }, Modifier.weight(1f),
                !saving && draft.recipient.customerEmail.isNotBlank())
            NamuPrimaryButton("견적완료", {
                if (onConfirmSent != null || !verifyDelivery) confirmSent = true
                else if (verifiedDelivery?.canComplete(draft) == true) submit(QuoteDelivery.COMPLETE)
                else deliveryRequired = true
            }, Modifier.weight(1.4f), !saving)
            }
        }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("quote_editor_body")
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NamuCard {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(draft.recipient.customerName.ifBlank { "고객" }, Modifier.weight(1f),
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        NamuCallButton(draft.phone, Modifier.size(44.dp))
                    }
                    Text(listOf(displayEditorPhone(draft.phone), request?.region.orEmpty(),
                        when (draft.recipient.replyChannel) { "email" -> "메일로 받기"; "both" -> "문자·메일로 받기"; else -> "문자로 받기" })
                        .filter { it.isNotBlank() }.joinToString(" · "), fontSize = 15.sp)
                    if (confirmedEdit && draft.recipient.customerEmail.isNotBlank()) Text(draft.recipient.customerEmail, fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (draft.recipient.formalQuoteRequested) NamuStatusBadge("공식견적서 요청")
                        request?.receiptNumber?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = NamuColors.muted) }
                    }
                    Surface(color = NamuColors.background, shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("고객 요청사항", fontSize = 13.sp, color = NamuColors.muted)
                            SelectionContainer { Text(request?.message?.ifBlank { "없음" } ?: "없음", fontSize = 15.sp) }
                        }
                    }
                    if (manualEntry) {
                        ManualField("고객 성함", draft.recipient.customerName, {
                            draft = draft.copy(recipient = draft.recipient.copy(customerName = it))
                        }, saving, limit = 50)
                        ManualField("전화번호 *", draft.phone, { draft = draft.copy(phone = it) }, saving, KeyboardType.Phone)
                        ManualField("견적 수신인·회사명", draft.recipient.recipientName, {
                            draft = draft.copy(recipient = draft.recipient.copy(recipientName = it))
                        }, saving, limit = 100)
                        ManualField("이메일", draft.recipient.customerEmail, {
                            draft = draft.copy(recipient = draft.recipient.copy(customerEmail = it))
                        }, saving, KeyboardType.Email)
                    }
                }
            }
            draft.rows.forEachIndexed { index, row ->
                key(index) {
                    fun update(value: SellerQuoteRow) {
                        draft = draft.copy(rows = draft.rows.toMutableList().also { it[index] = value })
                    }
                    val editable = confirmedEdit || manualEntry || index >= requestedItems.size
                    val specification = if (confirmedEdit) row.spec else requestedItems.getOrNull(index)?.takeIf { it.species == row.name }?.specification ?: row.spec
                    NamuCard {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = NamuColors.dark, modifier = Modifier.size(28.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Text("${index + 1}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(row.name.ifBlank { "새 품목" }, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    SelectionContainer { Text((if (confirmedEdit) "규격 · " else "희망 규격 · ") + specification.ifBlank { "미입력" }, fontSize = 13.sp, color = NamuColors.muted) }
                                }
                                Surface(shape = RoundedCornerShape(10.dp), color = NamuColors.soft,
                                    border = BorderStroke(1.5.dp, NamuColors.primary), modifier = Modifier.widthIn(min = 76.dp).heightIn(min = 40.dp)
                                        .semantics { contentDescription = "주문 수량 ${row.quantity.ifBlank { "0" }}주" }) {
                                    Text("${row.quantity.ifBlank { "0" }}주", Modifier.padding(8.dp), textAlign = TextAlign.End,
                                        color = NamuColors.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (editable) {
                                ManualField("묘목명 *", row.name, { update(row.copy(name = it)) }, saving, limit = 200)
                                ManualField("규격", row.spec, { update(row.copy(spec = it)) }, saving, limit = if (confirmedEdit) 500 else 200)
                                ManualField("수량 (주) *", row.quantity, { update(row.copy(quantity = it)) }, saving, KeyboardType.Number)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("단가", fontSize = 15.sp)
                                FilledTonalIconButton(onClick = { update(row.copy(price = adjustedQuotePrice(row.price, -500))) },
                                    enabled = !saving, modifier = Modifier.size(44.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = NamuColors.soft)) {
                                    Icon(Icons.Outlined.Remove, "단가 500원 내리기")
                                }
                                BasicTextField(row.price, { if (it.all(Char::isDigit) && it.length <= 10) update(row.copy(price = it)) },
                                    singleLine = true, enabled = !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center, color = NamuColors.text),
                                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).border(1.5.dp, NamuColors.input, RoundedCornerShape(10.dp))
                                        .testTag("unit_price_$index"), decorationBox = { inner ->
                                        Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) { inner() }
                                    })
                                FilledTonalIconButton(onClick = { update(row.copy(price = adjustedQuotePrice(row.price, 500))) },
                                    enabled = !saving, modifier = Modifier.size(44.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = NamuColors.soft)) {
                                    Icon(Icons.Outlined.Add, "단가 500원 올리기")
                                }
                            }
                            Spacer(Modifier.fillMaxWidth().height(1.dp).drawBehind {
                                drawLine(NamuColors.border, start = androidx.compose.ui.geometry.Offset.Zero,
                                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                            })
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(row.remarks, { update(row.copy(remarks = it.take(500))) },
                                    label = { Text("묘목 설명", fontSize = 13.sp) }, placeholder = { Text("년생·크기·상태 입력", fontSize = 13.sp) },
                                    enabled = !saving, minLines = 2, shape = RoundedCornerShape(12.dp),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                                    modifier = Modifier.weight(1f).testTag("seedling_description_$index"))
                                val subtotal = (row.quantity.toIntOrNull()?.toLong() ?: 0L) * (row.price.toIntOrNull()?.toLong() ?: 0L)
                                Text(money(subtotal), Modifier.widthIn(max = 130.dp), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                            if (editable && draft.rows.size > 1) TextButton(onClick = {
                                draft = draft.copy(rows = draft.rows.filterIndexed { i, _ -> i != index })
                            }, enabled = !saving) { Text("이 품목 삭제", color = NamuColors.danger) }
                        }
                    }
                }
            }
            if (draft.rows.size < 50) NamuDashedAdd("품목 추가", {
                if (!saving) draft = draft.copy(rows = draft.rows + SellerQuoteRow())
            })
            NamuCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("택배비", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Row(Modifier.width(160.dp)) {
                            NamuSegmented(listOf(NamuSegment("후불"), NamuSegment("선불")), if (draft.prepaid) 1 else 0,
                                onSelect = { if (!saving) draft = draft.copy(prepaid = it == 1) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(onClick = { draft = draft.copy(fee = adjustedQuotePrice(draft.fee, -500)) },
                            enabled = !saving && draft.prepaid, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.Remove, "택배비 내리기") }
                        Text(if (draft.prepaid) money(draft.shipping().toLong()) else "착불", Modifier.weight(1f),
                            textAlign = TextAlign.Center, fontSize = 17.sp, color = if (draft.prepaid) NamuColors.text else NamuColors.muted)
                        FilledTonalIconButton(onClick = { draft = draft.copy(fee = adjustedQuotePrice(draft.fee, 500)) },
                            enabled = !saving && draft.prepaid, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.Add, "택배비 올리기") }
                    }
                }
            }
            NamuSecondaryButton("판매자 요청사항 · 공식견적서", { formalExpanded = !formalExpanded }, Modifier.fillMaxWidth(), !saving,
                Icons.Outlined.Description)
            if (formalExpanded) QuoteBox("공식견적서") {
                OutlinedTextField(draft.options.notes, { value -> draft = draft.copy(options = draft.options.copy(notes = value.take(500))) },
                    label = { Text("요청사항 · 판매자 입력") }, placeholder = { Text("공식견적서 중요사항에 넣을 내용") },
                    enabled = !saving, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (!confirmedEdit) NamuSecondaryButton("공식견적서 생성", {
                    try {
                        val current = draft.copy(recipient = draft.recipient.copy(formalQuoteRequested = true))
                        current.validate(QuoteDelivery.COMPLETE)
                        draft = current; formalDraft = current; error = null
                    } catch (e: Exception) { error = e.message ?: "견적 내용을 확인해주세요." }
                }, Modifier.fillMaxWidth(), !saving && request != null)
                TextButton(onClick = { issueDetails = !issueDetails }, enabled = !saving) {
                    Text(if (issueDetails) "발행정보 접기" else "공급자 · 계좌 · 발행정보")
                }
                if (issueDetails) QuoteIssueFields(draft.options, saving) {
                    draft = draft.copy(options = it, recipient = if (confirmedEdit) draft.recipient.copy(formalQuoteRequested = true) else draft.recipient)
                }
            }
        }
    }
    formalDraft?.let { snapshot ->
        request?.let { source ->
            InitialFormalQuoteDialog(source, snapshot, onDismiss = { formalDraft = null },
                onPrepare = { onPrepareDocument(snapshot); initial = snapshot },
                onOpened = { channel, _ -> formalOpenedChannels = formalOpenedChannels + channel })
        }
    }
    if (deliveryRequired) AlertDialog(onDismissRequest = { deliveryRequired = false },
        title = { Text("고객 전달 확인") },
        text = { Text("문자 또는 메일을 먼저 보내 주세요. 현재 견적의 전송이 완료되어야 견적완료할 수 있습니다. 문자 결과확인 중이면 문자 버튼을 눌러 최종 결과를 확인해주세요.") },
        confirmButton = { TextButton(onClick = { deliveryRequired = false }) { Text("확인") } })
    if (confirmSent) ConfirmQuoteSentDialog(
        openedChannels = openedChannels + formalOpenedChannels, phone = displayEditorPhone(draft.phone), email = draft.recipient.customerEmail,
        onDismiss = { confirmSent = false },
        onConfirm = { method ->
            confirmSent = false
            if (onConfirmSent == null) submit(QuoteDelivery.COMPLETE)
            else try {
                draft.validate(QuoteDelivery.COMPLETE)
                error = null
                onConfirmSent(draft, method)
            } catch (e: Exception) {
                error = e.message ?: "견적 내용을 확인해주세요."
            }
        }
    )
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
    var expanded by rememberSaveable { mutableStateOf(true) }
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
            ManualField("업태", issuer.businessType, { onChange(options.copy(issuer = issuer.copy(businessType = it))) }, saving, limit = 50)
            ManualField("종목", issuer.category, { onChange(options.copy(issuer = issuer.copy(category = it))) }, saving, limit = 50)
            ManualField("추가 세액 (원)", options.tax, { onChange(options.copy(tax = it)) }, saving, KeyboardType.Number)
            ManualField("견적 유효기간", options.validUntil, { onChange(options.copy(validUntil = it)) }, saving, limit = 100)
            ManualField("입금 계좌 · 선택", options.account, { onChange(options.copy(account = it)) }, saving, limit = 500)
        }
    }
}

@Composable
internal fun QuoteBox(title: String, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
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








