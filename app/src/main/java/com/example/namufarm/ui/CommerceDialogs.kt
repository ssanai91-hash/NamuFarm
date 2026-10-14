package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.OrderEntity
import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.domain.order.OrderLineSource
import com.example.namufarm.domain.order.OrderSummaryCodec
import com.example.namufarm.quotation.QuoteRecipient
import com.example.namufarm.viewmodel.ManagementViewModel
import com.example.namufarm.ui.components.*
import com.example.namufarm.viewmodel.CartItem

@Composable
internal fun QuoteCartDialog(
    cart: List<CartItem>,
    phone: String,
    isPrepaid: Boolean,
    shippingFee: Int,
    saving: Boolean,
    snackbarHostState: SnackbarHostState,
    onPhone: (String) -> Unit,
    onShipping: (Boolean, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onRecentCalls: () -> Unit,
    onSendSms: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    recipient: com.example.namufarm.quotation.QuoteRecipient = com.example.namufarm.quotation.QuoteRecipient(),
    onRecipient: (com.example.namufarm.quotation.QuoteRecipient) -> Unit = {}
) {
    NamuDialog(
        "견적 작성", "${cart.size}개 품목 · 견적 저장 시 재고는 그대로예요",
        onDismiss, snackbarHostState,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSendSms, enabled = cart.isNotEmpty() && phone.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("문자 보내기") }
                Button(onClick = onSave, enabled = !saving && cart.isNotEmpty() && phone.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text(if (saving) "저장 중…" else "견적 저장")
                }
            }
        }
    ) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (cart.isEmpty()) item { NamuEmptyState("바구니가 비어 있어요", "판매 재고 또는 빠른 견적에서 품목을 담아주세요.", Icons.Outlined.ShoppingBag) }
            itemsIndexed(cart) { index, entry ->
                val item = entry.item
                CommerceItemCard(
                    item.speciesName,
                    buildSpecText(item.quality, item.age, item.size, item.plantType, " · "),
                    entry.orderQuantity, item.retailPrice, item.wholesalePrice, item.farmName, entry.source,
                    trailing = {
                        IconButton(onClick = { onRemove(index) }, enabled = !saving) {
                            Icon(Icons.Outlined.Close, "${item.speciesName} 바구니에서 빼기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
            }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        NamuSectionTitle("배송비")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(isPrepaid, { onShipping(true, shippingFee) }, { Text("선불") }, modifier = Modifier.heightIn(min = 48.dp))
                            FilterChip(!isPrepaid, { onShipping(false, 0) }, { Text("착불") }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                        if (isPrepaid) {
                            OutlinedTextField(
                                value = shippingFee.toString(),
                                onValueChange = { value ->
                                    if (value.isEmpty()) onShipping(true, 0)
                                    else value.toIntOrNull()?.takeIf { it >= 0 }?.let { onShipping(true, it) }
                                },
                                label = { Text("선불 배송비 (원)") },
                                supportingText = { Text("0원도 선불로 저장됩니다") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onShipping(true, 0) }, modifier = Modifier.weight(1f)) { Text("초기화") }
                                FilledTonalButton(onClick = { onShipping(true, (shippingFee.toLong() + 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }, modifier = Modifier.weight(1f)) { Text("+1,000원") }
                            }
                        }
                    }
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NamuSectionTitle("고객 정보", trailing = { TextButton(onClick = onRecentCalls) { Text("최근 통화") } })
                        OutlinedTextField(
                            value = phone, onValueChange = onPhone, modifier = Modifier.fillMaxWidth(),
                            label = { Text("고객 전화번호") }, placeholder = { Text("010-0000-0000") },
                            leadingIcon = { Icon(Icons.Outlined.Phone, null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true, shape = MaterialTheme.shapes.medium
                        )
                        OutlinedTextField(recipient.customerName, { onRecipient(recipient.copy(customerName = it)) }, label = { Text("고객 성함 · 선택") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(recipient.customerEmail, { onRecipient(recipient.copy(customerEmail = it)) }, label = { Text("이메일 · 선택") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                        if (recipient.formalQuoteRequested)
                            Text("공식 견적서는 나피 PC에서 별도 처리합니다.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                AmountSummary(
                    cart.sumOf { it.item.retailPrice.toLong() * it.orderQuantity } + if (isPrepaid) shippingFee else 0,
                    cart.sumOf { it.orderQuantity.toLong() }, isPrepaid, shippingFee, "최종 견적"
                )
            }
        }
    }
}

@Composable
internal fun TodayQuotesDialog(quotes: List<QuoteEntity>, onSelect: (QuoteEntity) -> Unit, onDismiss: () -> Unit) {
    NamuDialog("오늘 견적", "오늘 견적 ${quotes.size}건", onDismiss) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (quotes.isEmpty()) item { NamuEmptyState("오늘 발송한 견적이 없어요", "온라인 요청이나 빠른 견적에서 작성한 뒤 발송 완료를 기록해주세요.", Icons.Outlined.Description) }
            items(quotes, key = { it.quoteNumber }) { quote ->
                HistoryCard(quote.customerPhone, quote.saveDate, quote.itemSummary, quote.totalPrice, "견적", false, { onSelect(quote) })
            }
        }
    }
}

@Composable
internal fun TodayOrdersDialog(orders: List<OrderEntity>, onSelect: (OrderEntity) -> Unit, onDismiss: () -> Unit, onSales: () -> Unit = {}) {
    NamuDialog("오늘 주문", "주문 기록 ${orders.size}건 · 취소 내역 포함", onDismiss, actions={ OutlinedButton(onClick=onSales,modifier=Modifier.fillMaxWidth()) { Text("전체 판매 관리 · 일/월/년 매출 조회") } }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (orders.isEmpty()) item { NamuEmptyState("오늘 확정한 주문이 없어요", "고객이 구매를 결정하면 견적에서 구매 확정을 눌러주세요.", Icons.Outlined.Inventory2) }
            items(orders, key = { it.orderNumber }) { order ->
                HistoryCard(order.customerPhone, order.orderDate, order.itemSummary, order.totalPrice, order.status, order.status == "주문취소", { onSelect(order) }, order.receiverName)
            }
        }
    }
}

@Composable
private fun HistoryCard(phone: String, date: String, summary: String, amount: Int, status: String, canceled: Boolean, onClick: () -> Unit, customer: String = "") {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(phone.ifBlank { "전화번호 미입력" }, style = MaterialTheme.typography.titleMedium)
                    if (customer.isNotBlank()) Text(customer, style = MaterialTheme.typography.bodyMedium)
                    Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NamuBadge(status, error = canceled)
            }
            val lines = summary.lineSequence().filter { it.isNotBlank() }.toList()
            lines.take(2).forEach { line ->
                val item = parseSummaryLine(line)
                Text(
                    if (item == null) line else "${item.species} · ${item.qty}주${if (item.source == OrderLineSource.MANUAL) " · 수기 견적" else ""}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (lines.size > 2) Text("외 ${lines.size - 2}개 품목", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(money(amount.toLong()), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


/** 견적확정 목록은 작업 버튼이 없는 조회 화면만 표시한다. */
@Composable
internal fun ConfirmedQuoteSummaryDialog(
    quote: QuoteEntity,
    viewModel: ManagementViewModel,
    onDismiss: () -> Unit
) {
    var recipient by remember(quote.quoteNumber) { mutableStateOf(QuoteRecipient()) }
    var recipientError by remember(quote.quoteNumber) { mutableStateOf(false) }
    LaunchedEffect(quote.quoteNumber) {
        try {
            recipient = viewModel.quoteRecipient(quote.quoteNumber)
        } catch (_: Exception) {
            recipientError = true
        }
    }
    val lines = remember(quote.itemSummary) {
        runCatching { OrderSummaryCodec.parseStrict(quote.itemSummary) }.getOrNull()
    }
    val sent = quote.sentAt > 0L
    val sentDate = remember(quote.sentAt) {
        if (!sent) "" else java.text.SimpleDateFormat(
            "yyyy.MM.dd HH:mm", java.util.Locale.KOREA
        ).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
        }.format(java.util.Date(quote.sentAt))
    }
    val name = recipient.customerName.ifBlank { recipient.recipientName }
        .ifBlank { if (recipientError) "고객명 확인 불가" else "미입력" }
    val channels = when (recipient.replyChannel) {
        "both" -> "문자 · 메일"
        "email" -> "메일"
        else -> "문자"
    }
    val itemTotal = quote.totalPrice.toLong() -
        if (quote.isPrepaid) quote.shippingFee.toLong() else 0L
    NamuDialog("견적 내역", quote.saveDate, onDismiss, actions = {
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp)) { Text("닫기") }
    }) {
        LazyColumn(modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(color = if (sent) Color(0xFFE3EEE6) else Color(0xFFFFF3D6),
                    shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (sent) "견적 발송 완료" else "견적확정 · 발송 대기",
                            fontWeight = FontWeight.SemiBold,
                            color = if (sent) Color(0xFF2F5D3F) else Color(0xFF8A5A00))
                        Text(if (sent) "발송완료 기록: " + sentDate
                            else "발송완료 기록이 아직 없습니다.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.5.dp, Color(0xFFE3E9E5))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConfirmedInfoRow("고객명", name)
                        ConfirmedInfoRow("연락처", quote.customerPhone)
                        ConfirmedInfoRow("견적번호", quote.quoteNumber)
                        ConfirmedInfoRow("견적일", quote.saveDate)
                        ConfirmedInfoRow("수신방법", if (recipientError) "확인 불가" else channels)
                        if (recipient.customerEmail.isNotBlank()) {
                            ConfirmedInfoRow("이메일", recipient.customerEmail)
                        }
                    }
                }
            }
            item {
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.5.dp, Color(0xFFE3E9E5))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("견적 품목", fontWeight = FontWeight.SemiBold)
                        if (lines == null) {
                            Text("저장된 품목을 읽을 수 없습니다.",
                                color = MaterialTheme.colorScheme.error)
                        } else {
                            lines.forEach { line ->
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(line.speciesName + " · " + line.orderQty + "주")
                                        val specification = listOf(line.quality, line.age,
                                            line.size, line.plantType)
                                            .filter(String::isNotBlank).joinToString(" / ")
                                        if (specification.isNotBlank()) {
                                            Text(specification,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Text(money(line.orderQty.toLong() * line.retailPrice))
                                }
                            }
                        }
                        HorizontalDivider()
                        ConfirmedInfoRow("상품 합계", money(itemTotal))
                        ConfirmedInfoRow("배송비", if (quote.isPrepaid)
                            "선불 · " + money(quote.shippingFee.toLong()) else "후불(착불)")
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("최종 견적 금액", fontWeight = FontWeight.SemiBold)
                            Text(money(quote.totalPrice.toLong()),
                                color = Color(0xFF2F5D3F),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item {
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.5.dp, Color(0xFFE3E9E5))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("발송 정보", fontWeight = FontWeight.SemiBold)
                        ConfirmedInfoRow("발송 상태", if (sent) "완료로 기록됨" else "발송 대기")
                        ConfirmedInfoRow("수신방법", if (recipientError) "확인 불가" else channels)
                        Text("문자·메일 각각의 실제 전송 결과는 별도로 기록되지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmedInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(88.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun QuoteDetailsDialog(quote: QuoteEntity, confirming: Boolean, snackbar: SnackbarHostState, onSms: () -> Unit, onConfirm: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit, onDocument: () -> Unit = {}, sentAction: @Composable () -> Unit = {}, ordered: Boolean = false, onOrder: () -> Unit = {}, onEmail: () -> Unit = {}, taxAmount: Long = 0, allowPayment: Boolean = true) {
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("실제 입금을 확인하셨나요?") },
        text = { Text("최종 견적 ${money(quote.totalPrice.toLong() + taxAmount)}의 실제 입금을 확인해주세요. 입금 확인 시 오늘 주문이 증가하고 발송한 견적 기록은 유지됩니다.") },
        confirmButton = { Button(onClick = { confirm = false; onConfirm() }) { Text("입금 완료 · 주문 등록") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("다시 확인") } }
    )
    NamuDialog("견적 상세", quote.saveDate, onDismiss, snackbar, actions = {
        if (allowPayment) {
            if (ordered) Button(onClick = onOrder, modifier = Modifier.fillMaxWidth()) { Text("등록한 주문 보기") }
            else {
                Button(onClick = { confirm = true }, enabled = !confirming && quote.sentAt > 0L,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (confirming) "입금 처리 중…" else "입금확인 · 판매 관리로 이동")
                }
            }
            sentAction()
            OutlinedButton(onClick = onDocument, enabled = !confirming, modifier = Modifier.fillMaxWidth()) { Text("공식 견적서 · PDF / 이미지") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onSms, enabled = !confirming) { Text("문자 보내기") }
                TextButton(onClick = onEmail, enabled = !confirming) { Text("메일 보내기") }
                TextButton(onClick = onDelete, enabled = !confirming && !ordered,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("견적 삭제") }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSms, enabled = !confirming,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("문자 보내기") }
                Button(onClick = onEmail, enabled = !confirming,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("메일 보내기") }
            }
            sentAction()
            TextButton(onClick = onDelete, enabled = !confirming,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("견적 삭제") }
            Text("문자와 메일은 텍스트로 전송합니다. 입금과 배송은 나피 PC에서 처리합니다.",
                style = MaterialTheme.typography.bodySmall)
        }
    }) {
        CommerceDetailBody(quote.customerPhone, "", quote.quoteNumber, quote.itemSummary, quote.totalQty, quote.totalPrice, quote.isPrepaid, quote.shippingFee, taxAmount = taxAmount)
    }
}

@Composable
internal fun OrderDetailsDialog(order: OrderEntity, snackbar: SnackbarHostState, onDelivery: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit, onStatus: (String) -> Unit = {}, onCosts: () -> Unit = {}) {
    var statusConfirm by remember { mutableStateOf(false) }
    val next=com.example.namufarm.data.OrderStatus.next(order.status)
    if(statusConfirm && next!=null) AlertDialog(onDismissRequest={statusConfirm=false},title={Text("$next 처리할까요?")},
        text={Text(when(next) { "입금확인" -> "고객의 대금 ${money(order.totalPrice.toLong()+order.taxAmount)} 입금을 실제로 확인한 뒤 눌러주세요."; "배송확인" -> "배송 또는 고객의 상품 인수를 확인한 뒤 눌러주세요."; else -> "원가·비용과 배송을 확인하고 이 판매를 완료합니다. 완료 후에는 금액과 상태를 변경할 수 없습니다." })},
        confirmButton={TextButton(onClick={statusConfirm=false;onStatus(next)}) {Text(next)}},dismissButton={TextButton(onClick={statusConfirm=false}) {Text("돌아가기")}})
    NamuDialog("주문 상세", order.orderDate, onDismiss, snackbar, actions = {
        if(next!=null) {
            Button(onClick={statusConfirm=true},enabled=next!="판매완료" || order.costConfirmed,modifier=Modifier.fillMaxWidth()) { Text(next) }
            OutlinedButton(onClick=onCosts,modifier=Modifier.fillMaxWidth()) { Text("원가·비용 확인 · 수익 계산") }
            if(next=="판매완료" && !order.costConfirmed) Text("판매 완료 전에 원가·비용을 확인해주세요.")
        }
        Button(onClick = onDelivery, enabled=next!=null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Outlined.LocalShipping, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("배송정보 관리")
        }
        TextButton(
            onClick = onCancel, enabled = com.example.namufarm.data.OrderStatus.canCancel(order.status), modifier = Modifier.align(Alignment.CenterHorizontally),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text(if (order.status == "주문취소") "취소된 주문입니다" else "주문취소") }
    }) {
        CommerceDetailBody(order.customerPhone, order.receiverName.ifBlank { "주문자 미입력" }, order.orderNumber, order.itemSummary, order.totalQty, order.totalPrice, order.isPrepaid, order.shippingFee, order.status, taxAmount = order.taxAmount)
    }
}

/** Shipping addresses intentionally belong only to the delivery editor. */
@Composable
internal fun CommerceDetailBody(phone: String, customer: String, number: String, summary: String, quantity: Int, amount: Int, prepaid: Boolean, fee: Int, status: String? = null, taxAmount: Long = 0) {
    LazyColumn(Modifier.testTag("commerce_detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    status?.let { NamuBadge(it, error = it == "주문취소") }
                    Text(phone.ifBlank { "전화번호 미입력" }, style = MaterialTheme.typography.headlineSmall)
                    if (customer.isNotBlank()) Text(customer, style = MaterialTheme.typography.bodyLarge)
                    SelectionContainer { Text(number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item { NamuSectionTitle("품목 내역", "총 ${quantity}주") }
        items(summary.lineSequence().filter { it.isNotBlank() }.toList()) { line ->
            val item = parseSummaryLine(line)
            if (item == null) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("기존 품목 기록", style = MaterialTheme.typography.labelLarge)
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else CommerceItemCard(item.species, buildSpecText(item.quality, item.age, item.size, item.plantType, " · "), item.qty, item.retailPrice, item.wholesalePrice, item.farmName, item.source)
        }
        item { AmountSummary(amount.toLong() + taxAmount, quantity.toLong(), prepaid, fee, if (taxAmount > 0) "최종 합계 · 추가 세액 ${money(taxAmount)} 포함" else "합계") }
    }
}
