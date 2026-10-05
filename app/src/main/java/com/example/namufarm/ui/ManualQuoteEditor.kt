package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.WebQuoteItem
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.domain.order.OrderLine
import com.example.namufarm.domain.order.OrderLineSource
import com.example.namufarm.domain.order.OrderAmounts
import com.example.namufarm.quotation.QuoteRecipient
import com.example.namufarm.ui.components.NamuDialog
import com.google.gson.Gson

private data class ManualRow(val name: String = "", val spec: String = "", val quantity: String = "", val price: String = "") {
    fun line(): OrderLine {
        require(name.isNotBlank()) { "묘목명을 입력해주세요." }
        val qty = quantity.toIntOrNull()?.takeIf { it > 0 } ?: error("수량은 1주 이상 입력해주세요.")
        val unit = price.toIntOrNull()?.takeIf { it >= 0 } ?: error("판매 단가를 입력해주세요.")
        return OrderLine(name.trim(), spec.trim(), "", "", "", qty, unit, "수기견적", 0, OrderLineSource.MANUAL)
    }
}

@Composable
internal fun ManualQuoteEditor(request: WebQuoteRequestEntity?, saving: Boolean, message: String?,
    onDismiss: () -> Unit, onSave: (List<OrderLine>, String, QuoteRecipient, Boolean, Int) -> Unit) {
    var rows by remember(request?.requestId) { mutableStateOf(request?.let {
        Gson().fromJson(it.itemsJson, Array<WebQuoteItem>::class.java).map { item -> ManualRow(item.species, item.specification, item.quantity.toString()) }
    } ?: listOf(ManualRow())) }
    var phone by remember { mutableStateOf(request?.customerPhone.orEmpty()) }
    var recipient by remember { mutableStateOf(request?.let(QuoteRecipient::from) ?: QuoteRecipient()) }
    var prepaid by remember { mutableStateOf(false) }
    var fee by remember { mutableStateOf("0") }
    var error by remember { mutableStateOf<String?>(null) }
    NamuDialog("수동 견적 작성", request?.let { "접수번호 ${it.requestId}" } ?: "품목·규격·단가를 직접 입력해주세요", onDismiss = { if (!saving) onDismiss() }, actions = {
        (error ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            try {
                val lines = rows.map { it.line() }
                val shipping = if (prepaid) fee.toIntOrNull()?.takeIf { it >= 0 } ?: error("배송비를 확인해주세요.") else 0
                OrderAmounts.calculate(lines, prepaid, shipping)
                error = null
                onSave(lines, phone, recipient.checked(), prepaid, shipping)
            } catch (e: IllegalArgumentException) { error = e.message }
            catch (e: IllegalStateException) { error = e.message }
            catch (e: ArithmeticException) { error = "견적 수량이나 금액이 너무 큽니다." }
        }, enabled = !saving && rows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(if (saving) "저장 중…" else "견적 저장 · 보내기") }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            QuoteBox("받는 사람 · 연락처") {
                ManualField("고객 성함", recipient.customerName, { recipient = recipient.copy(customerName = it) }, saving)
                ManualField("전화번호 *", phone, { phone = it }, saving, KeyboardType.Phone)
                ManualField("견적 수신인·회사명", recipient.recipientName, { recipient = recipient.copy(recipientName = it) }, saving)
                ManualField("이메일 · 선택", recipient.customerEmail, { recipient = recipient.copy(customerEmail = it) }, saving, KeyboardType.Email)
                request?.let { Text("배송지역 · ${it.region}"); if (it.message.isNotBlank()) Text("요청사항 · ${it.message}") }
            }
            rows.forEachIndexed { index, row ->
                QuoteBox("주문 묘목 ${index + 1}") {
                    fun update(value: ManualRow) { rows = rows.toMutableList().also { it[index] = value } }
                    ManualField("묘목명 *", row.name, { update(row.copy(name = it)) }, saving)
                    ManualField("규격", row.spec, { update(row.copy(spec = it)) }, saving)
                    ManualField("수량 (주) *", row.quantity, { update(row.copy(quantity = it)) }, saving, KeyboardType.Number)
                    ManualField("판매 단가 (원) *", row.price, { update(row.copy(price = it)) }, saving, KeyboardType.Number)
                    val subtotal = (row.quantity.toLongOrNull() ?: 0) * (row.price.toLongOrNull() ?: 0)
                    if (subtotal >= 0) Text("품목 금액 · ${com.example.namufarm.ui.components.money(subtotal)}")
                    TextButton(onClick = { rows = rows.filterIndexed { i, _ -> i != index } }, enabled = !saving && rows.size > 1) { Text("품목 삭제") }
                }
            }
            OutlinedButton(onClick = { rows = rows + ManualRow() }, enabled = !saving && rows.size < 50, modifier = Modifier.fillMaxWidth()) { Text("묘목 추가") }
            QuoteBox("택배 배송비") {
                Row { Checkbox(prepaid, { prepaid = it }, enabled = !saving); Text("배송비 선불 · 견적 합계에 포함") }
                if (prepaid) ManualField("배송비 (원)", fee, { fee = it }, saving, KeyboardType.Number)
                else Text("착불 · 배송비는 견적 합계에 포함하지 않습니다")
            }
        }
    }
}

@Composable
internal fun QuoteBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ManualField(label: String, value: String, onValue: (String) -> Unit, saving: Boolean, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, { onValue(it.take(if (keyboard == KeyboardType.Number) 10 else 254)) }, label = { Text(label) },
        singleLine = true, enabled = !saving, keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth())
}
