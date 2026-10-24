package com.example.namufarm.quotemode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.WebQuoteItem
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.quotation.*
import com.example.namufarm.quotemode.components.NamuDialog
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Read local or server-confirmed order data without treating a message template as order details. */
@Composable
internal fun InitialConfirmedQuoteDialog(request: WebQuoteRequestEntity, draft: SellerQuoteDraft?,
    confirmedAt: Long = 0, delivery: QuoteDeliveryRecord? = null, onDismiss: () -> Unit) {
    var formal by remember { mutableStateOf(false) }
    val rows = remember(request, draft) {
        draft?.rows ?: runCatching {
            Gson().fromJson(request.itemsJson, Array<WebQuoteItem>::class.java).map {
                SellerQuoteRow(it.species, it.specification, it.quantity.toString(), "")
            }
        }.getOrDefault(emptyList())
    }
    val itemAmount = draft?.rows?.sumOf { it.quantity.toLong() * it.price.toLong() }
    val shipping = draft?.shipping()?.toLong()
    val tax = draft?.let { if (it.recipient.formalQuoteRequested) it.options.taxAmount() else 0L }
    NamuDialog("확정견적 확인", request.receiptNumber.ifBlank { request.requestId }, onDismiss,
        compact = true, actions = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft != null) Button(onClick = { formal = true },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp)) {
                    Text("PDF 생성", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp)) { Text("닫기", fontSize = 15.sp) }
            }
        }) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                QuoteBox("고객 정보", compact = true) {
                    ResultField("고객명", draft?.recipient?.customerName?.ifBlank { request.customerName }
                        ?: request.customerName)
                    ResultField("연락처", draft?.phone ?: request.customerPhone)
                    val email = draft?.recipient?.customerEmail ?: request.customerEmail
                    if (email.isNotBlank()) ResultField("이메일", email)
                    ResultField("상태", "견적확정")
                    if (confirmedAt > 0) ResultField("확정일", resultDate(confirmedAt))
                    if (draft?.recipient?.recipientName?.isNotBlank() == true)
                        ResultField("수신인", draft.recipient.recipientName)
                }
            }
            item { Text("주문 내역 · ${rows.size}품목", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(rows) { index, row ->
                QuoteBox("${index + 1}. ${row.name}", compact = true) {
                    if (row.spec.isNotBlank()) ResultField("규격", row.spec)
                    if (draft != null) {
                        ResultField("수량·단가", "${row.quantity}주 × ${resultNumber(row.price.toLong())}원")
                        ResultField("판매금액", "${resultNumber(row.quantity.toLong() * row.price.toLong())}원", true)
                    } else ResultField("수량", "${row.quantity}주")
                    if (row.remarks.isNotBlank()) ResultField("묘목 설명", row.remarks)
                }
            }
            item {
                if (itemAmount != null && shipping != null && tax != null) QuoteBox("견적 금액", compact = true) {
                    ResultField("묘목 합계", "${resultNumber(itemAmount)}원")
                    ResultField("택배비", if (draft?.prepaid == true) "선불 ${resultNumber(shipping)}원" else "후불")
                    if (tax > 0) ResultField("추가 세액", "${resultNumber(tax)}원")
                    HorizontalDivider()
                    ResultField("총금액", "${resultNumber(itemAmount + shipping + tax)}원", true)
                } else Text("이전 견적의 확정 단가 내역이 없습니다. 확정한 기기에서 확인해주세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!draft?.options?.notes.isNullOrBlank()) item {
                QuoteBox("판매자 요청사항", compact = true) { Text(draft!!.options.notes) }
            }
            item {
                if (request.message.isNotBlank()) QuoteBox("고객 요청사항", compact = true) {
                    Text(request.message)
                } else Text("고객 요청사항 없음", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (delivery != null && draft != null) item {
                QuoteBox("전송 기록", compact = true) {
                    ResultField("문자", delivery.smsLabel(draft))
                    if (delivery.smsSentAt > 0) ResultField("전송일", resultDate(delivery.smsSentAt))
                    ResultField("메일", delivery.emailLabel(draft))
                    if (delivery.emailSentAt > 0) ResultField("전송일", resultDate(delivery.emailSentAt))
                }
            }
        }
    }
    if (formal && draft != null)
        InitialFormalQuoteDialog(request, draft, onDismiss = { formal = false })
}

@Composable
private fun ResultField(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, Modifier.weight(1f), textAlign = TextAlign.End,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
    }
}
private fun resultNumber(value: Long) = "%,d".format(Locale.KOREA, value)
private fun resultDate(value: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).apply {
    timeZone = TimeZone.getTimeZone("Asia/Seoul")
}.format(Date(value))

