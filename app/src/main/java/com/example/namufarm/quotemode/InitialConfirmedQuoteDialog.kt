package com.example.namufarm.quotemode

import android.content.Intent
import android.net.Uri
import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.WebQuoteItem
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.phone.openSmsComposer
import com.example.namufarm.quotation.*
import com.example.namufarm.quotemode.components.NamuDialog
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val documentGreen = Color(0xFF2F5D3F)
private val documentBlue = Color(0xFF0969CE)
private val documentInk = Color(0xFF17212B)
private val documentMuted = Color(0xFF6B7280)
private val documentLine = Color(0xFFE2E6ED)

/** Selected document-style confirmation. Sharing never changes the saved delivery state. */
@Composable
internal fun InitialConfirmedQuoteDialog(request: WebQuoteRequestEntity, draft: SellerQuoteDraft?,
    confirmedAt: Long = 0, delivery: QuoteDeliveryRecord? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var formal by remember { mutableStateOf(false) }
    var actionError by remember(request.requestId) { mutableStateOf<String?>(null) }
    val rows = remember(request, draft) {
        draft?.rows ?: runCatching {
            Gson().fromJson(request.itemsJson, Array<WebQuoteItem>::class.java).map {
                SellerQuoteRow(it.species, it.specification, it.quantity.toString(), "")
            }
        }.getOrDefault(emptyList())
    }
    val itemAmount = runCatching { draft?.rows?.sumOf { it.quantity.toLong() * it.price.toLong() } }.getOrNull()
    val shipping = runCatching { draft?.shipping()?.toLong() }.getOrNull()
    val tax = runCatching { draft?.let { if (it.recipient.formalQuoteRequested) it.options.taxAmount() else 0L } }.getOrNull()
    val phone = draft?.phone ?: request.customerPhone
    val dialNumber = PhoneNumberUtils.normalizeNumber(phone)
    val displayPhone = PhoneNumberUtils.formatNumber(phone, "KR") ?: phone
    val recipient = draft?.recipient?.recipientName ?: request.recipientName
    val number = request.receiptNumber.ifBlank { request.requestId }
    // A receipt-number date is not evidence of when the quote was confirmed.
    val dateLabel = if (confirmedAt > 0) "확정일 ${resultDate(confirmedAt)}" else "견적일 —"
    NamuDialog("확정견적 확인", "$number\n$dateLabel", onDismiss,
        compact = true, documentStyle = true, actions = {
            actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { formal = true }, enabled = draft != null,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))) {
                    Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(21.dp))
                    Spacer(Modifier.width(6.dp)); Text("PDF 생성", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = {
                    actionError = runCatching {
                        val saved = checkNotNull(draft)
                        openSmsComposer(context, saved.phone, quoteWorkflowText(request, saved))
                    }.getOrElse { it.message ?: "문자 앱을 열 수 없습니다." }
                }, enabled = draft != null && dialNumber.isNotBlank(),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                    border = BorderStroke(1.5.dp, documentGreen),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = documentGreen, containerColor = Color(0xFFF9FCF9))) {
                    Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(21.dp))
                    Spacer(Modifier.width(6.dp)); Text("문자 발송", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("고객 정보", Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = documentInk)
                    Surface(color = Color(0xFFE1F2E4), shape = RoundedCornerShape(6.dp)) {
                        Text("견적확정", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = documentGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                DocumentInfoRow("고객명") {
                    Text(draft?.recipient?.customerName?.ifBlank { request.customerName } ?: request.customerName,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = documentInk)
                }
                DocumentInfoRow("연락처") {
                    TextButton(onClick = {
                        actionError = runCatching {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dialNumber, null)))
                            null
                        }.getOrElse { "전화 앱을 열 수 없습니다." }
                    }, enabled = dialNumber.isNotBlank(), contentPadding = PaddingValues(0.dp)) {
                        Text(displayPhone.ifBlank { "—" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = documentInk)
                        Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.Phone, "전화 앱 열기", Modifier.size(21.dp), tint = documentBlue)
                    }
                }
                DocumentInfoRow("수신인") {
                    Text(recipient.ifBlank { "—" }, fontSize = 16.sp, color = documentInk)
                    if (recipient.trim() == "시작") {
                        Spacer(Modifier.width(8.dp))
                        Surface(color = Color(0xFFFFF4D9), shape = RoundedCornerShape(8.dp)) {
                            Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Info, null, Modifier.size(15.dp), tint = Color(0xFFB46B00))
                                Spacer(Modifier.width(3.dp)); Text("확인 필요", fontSize = 12.sp, color = Color(0xFF9A5C00))
                            }
                        }
                    }
                }
                val email = draft?.recipient?.customerEmail ?: request.customerEmail
                if (email.isNotBlank()) DocumentInfoRow("이메일") { Text(email, fontSize = 14.sp, color = documentInk) }
                Surface(color = Color(0xFFFFF8DB), shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CalendarMonth, null, tint = documentGreen, modifier = Modifier.size(32.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("고객 요청사항", color = documentGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(request.message.ifBlank { "등록된 요청사항이 없습니다." },
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = documentInk)
                        }
                    }
                }
                DocumentSectionTitle("주문 내역 · ${rows.size}품목")
                Surface(color = Color(0xFFF3F5F8), border = BorderStroke(0.5.dp, documentLine),
                    shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)) {
                    DocumentTableRow("품목명", "규격", "수량·단가", "판매금액", header = true)
                }
            }
            itemsIndexed(rows) { index, row ->
                Surface(color = Color.White, border = BorderStroke(0.5.dp, documentLine),
                    shape = if (index == rows.lastIndex) RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp) else RoundedCornerShape(0.dp)) {
                    Column {
                        DocumentTableRow(row.name, row.spec.ifBlank { "—" },
                            if (draft != null) "${row.quantity}주 × ${resultNumber(row.price.toLong())}원" else "${row.quantity}주",
                            if (draft != null) "${resultNumber(row.quantity.toLong() * row.price.toLong())}원" else "—")
                        if (row.remarks.isNotBlank()) Text("묘목 설명 · ${row.remarks}",
                            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                            fontSize = 14.sp, color = documentGreen)
                    }
                }
            }
            item {
                DocumentSectionTitle("견적 금액")
                if (itemAmount != null && shipping != null && tax != null) {
                    DocumentAmountRow("묘목 합계", "${resultNumber(itemAmount)}원")
                    DocumentAmountRow(if (draft?.prepaid == true) "택배비(선불)" else "택배비(후불)",
                        if (draft?.prepaid == true) "${resultNumber(shipping)}원" else "착불")
                    if (tax > 0) DocumentAmountRow("추가 세액", "${resultNumber(tax)}원")
                    Surface(color = Color(0xFFEEF6FF), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("총금액", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = documentBlue)
                            Text("${resultNumber(itemAmount + shipping + tax)}원", fontSize = 27.sp,
                                fontWeight = FontWeight.Bold, color = documentBlue)
                        }
                    }
                } else Text("이전 견적의 확정 단가 내역이 없습니다. 확정한 기기에서 확인해주세요.", color = documentMuted)
            }
            if (!draft?.options?.notes.isNullOrBlank()) item {
                DocumentSectionTitle("판매자 요청사항")
                Text(draft!!.options.notes, fontSize = 15.sp, color = documentInk)
            }
            if (delivery != null && draft != null) item {
                DocumentSectionTitle("전송 기록")
                DocumentAmountRow("문자", delivery.smsLabel(draft))
                if (delivery.smsSentAt > 0) DocumentAmountRow("전송일", resultDate(delivery.smsSentAt))
                DocumentAmountRow("메일", delivery.emailLabel(draft))
                if (delivery.emailSentAt > 0) DocumentAmountRow("전송일", resultDate(delivery.emailSentAt))
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
    if (formal && draft != null) InitialFormalQuoteDialog(request, draft, onDismiss = { formal = false })
}

@Composable
private fun DocumentInfoRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(74.dp), fontSize = 14.sp, color = documentMuted)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun DocumentSectionTitle(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp), color = documentLine)
    Text(title, Modifier.padding(top = 12.dp, bottom = 10.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = documentInk)
}

@Composable
private fun DocumentTableRow(name: String, spec: String, quantity: String, amount: String, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (header) 9.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(0.23f), fontSize = if (header) 13.sp else 15.sp,
            fontWeight = if (header) FontWeight.Normal else FontWeight.Bold, color = if (header) documentMuted else documentInk)
        Text(spec, Modifier.weight(0.20f), fontSize = if (header) 13.sp else 14.sp, color = if (header) documentMuted else documentInk)
        Text(quantity, Modifier.weight(0.29f), fontSize = 13.sp, color = if (header) documentMuted else documentInk)
        Text(amount, Modifier.weight(0.28f), textAlign = TextAlign.End, fontSize = if (header) 13.sp else 15.sp,
            fontWeight = if (header) FontWeight.Normal else FontWeight.Bold, color = if (header) documentMuted else documentInk)
    }
}

@Composable
private fun DocumentAmountRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = documentMuted)
        Text(value, Modifier.weight(1.2f), textAlign = TextAlign.End, fontSize = 15.sp, color = documentInk)
    }
}

private fun resultNumber(value: Long) = "%,d".format(Locale.KOREA, value)
private fun resultDate(value: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).apply {
    timeZone = TimeZone.getTimeZone("Asia/Seoul")
}.format(Date(value))
