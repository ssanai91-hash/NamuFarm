package com.example.namufarm.quotemode

import com.example.namufarm.ui.theme.*
import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.namufarm.data.remote.CustomerInquiryRemote
import com.example.namufarm.quotation.QuoteIssuer
import com.example.namufarm.quotation.QuoteIssuerSettings

/** Neutral colors are scoped to the selected inquiry reply design. */
internal object InquiryReplyDesign {
    val green = NamuColors.primary
    val canvas = NamuColors.background
    val ink = NamuColors.text
    val muted = NamuColors.muted
    val line = NamuColors.border
}

internal fun inquiryReplyContent(answer: String, row: CustomerInquiryRemote, channel: String,
                                 issuer: QuoteIssuer): String {
    val business = issuer.businessName.ifBlank { "나무팜농원" }
    val signature = listOf(business, issuer.phone.takeIf { it.isNotBlank() }?.let { "문의 : $it" })
        .filterNotNull().joinToString("\n")
    val text = answer.trim()
    // An already composed server answer must not acquire another greeting/signature.
    if (text.endsWith("감사합니다.\n\n$signature")) return text
    val greeting = if (channel == "sms")
        "${row.inquiry?.customerName.orEmpty().ifBlank { "고객" }}님, 안녕하세요.\n\n${business}입니다."
    else "${business}입니다." // The existing mail envelope already greets the customer.
    return "$greeting\n문의하신 내용에 답변드립니다.\n\n$text\n\n추가로 궁금하신 사항은 편하게 문의해주세요.\n감사합니다.\n\n$signature"
}

/** Keep this prefix identical to the deployed CustomerInquiries.gs envelope. */
internal fun inquiryReplyPrefix(row: CustomerInquiryRemote, channel: String): String {
    val heading = "[나무팜 고객문의 답변]\n문의번호: ${row.inquiryNumber}\n\n"
    return if (channel == "email") heading +
        "${row.inquiry?.customerName.orEmpty()}님, 문의주셔서 감사합니다.\n\n[문의 내용]\n${row.inquiry?.message.orEmpty()}\n\n[답변]\n"
    else heading
}

@Composable
internal fun InquiryReplyDialog(row: CustomerInquiryRemote, channel: String, answer: String,
                                enabled: Boolean, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val context = LocalContext.current
    val issuer = remember { QuoteIssuerSettings(context).load() }
    var text by rememberSaveable(row.requestId, channel) {
        mutableStateOf(inquiryReplyContent(answer, row, channel, issuer))
    }
    val sms = channel == "sms"
    val limit = if (sms) 900 else 2000
    val prefix = inquiryReplyPrefix(row, channel)
    val outgoing = text.trim()
    val valid = outgoing.isNotBlank() && outgoing.length <= limit &&
        (!sms || prefix.length + outgoing.length <= 1000)
    val recipient = if (sms) PhoneNumberUtils.formatNumber(row.inquiry?.customerPhone.orEmpty(), "KR")
        ?: row.inquiry?.customerPhone.orEmpty() else row.inquiry?.customerEmail.orEmpty()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.imePadding().widthIn(max = 560.dp).fillMaxWidth(0.96f).fillMaxHeight(0.96f)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (sms) Icons.Outlined.Sms else Icons.Outlined.Email, null,
                        tint = InquiryReplyDesign.green, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (sms) "문자 전송" else "메일 전송", Modifier.weight(1f),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = InquiryReplyDesign.ink)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "닫기") }
                }
                Surface(color = InquiryReplyDesign.canvas, shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, InquiryReplyDesign.line)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("수신인 · ${row.inquiry?.customerName.orEmpty()}", fontSize = 13.sp, color = InquiryReplyDesign.muted)
                        Text(recipient, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = InquiryReplyDesign.ink)
                        if (!sms) Text("제목 · [나무팜] 고객문의 답변 · ${row.inquiryNumber}",
                            fontSize = 13.sp, color = InquiryReplyDesign.muted)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The server prepends this read-only part; the editor is the exact saved reply.
                    SelectionContainer { Text(prefix.trimEnd(), fontSize = 14.sp, color = InquiryReplyDesign.muted) }
                    OutlinedTextField(value = text, onValueChange = { text = it },
                        label = { Text("인사말 · 답변 · 마무리") }, minLines = 8,
                        textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
                        shape = RoundedCornerShape(8.dp), colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = InquiryReplyDesign.green, unfocusedBorderColor = InquiryReplyDesign.line,
                            focusedTextColor = InquiryReplyDesign.ink, unfocusedTextColor = InquiryReplyDesign.ink,
                            cursorColor = InquiryReplyDesign.green), modifier = Modifier.fillMaxWidth())
                }
                Text("답변 ${outgoing.length} / ${limit}자 · 전체 ${prefix.length + outgoing.length}자",
                    color = if (valid) InquiryReplyDesign.muted else MaterialTheme.colorScheme.error, fontSize = 13.sp)
                if (!enabled) Text("문의나 초안이 변경됐습니다. 창을 닫고 최신 내용을 확인해주세요.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, InquiryReplyDesign.line)) { Text("취소") }
                    Button(onClick = { onSend(outgoing) }, enabled = enabled && valid && recipient.isNotBlank(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = InquiryReplyDesign.green)) {
                        Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp)); Text("전송", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
