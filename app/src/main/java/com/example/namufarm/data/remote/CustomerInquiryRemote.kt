package com.example.namufarm.data.remote

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CustomerInquiryBody(
    val requestId: String? = null, val category: String? = null,
    val customerName: String? = null, val customerPhone: String? = null,
    val customerEmail: String? = null, val message: String? = null
)
data class CustomerInquiryReply(val text: String? = null, val channel: String? = null)
data class CustomerInquiryDelivery(
    val id: String? = null, val channel: String? = null, val state: String? = null,
    val messageId: String? = null, val startedAt: String? = null,
    val sentAt: String? = null, val statusCode: String? = null
)
data class CustomerInquiryHistory(
    val kind: String? = null, val id: String? = null, val text: String? = null,
    val channel: String? = null, val at: String? = null, val state: String? = null,
    val status: String? = null, val note: String? = null
)
data class CustomerInquiryRemote(
    val kind: String? = null, val requestId: String? = null,
    val inquiryNumber: String? = null, val sequence: Long? = null,
    val revision: Long? = null, val createdAt: String? = null,
    val updatedAt: String? = null, val status: String? = null,
    val expired: Boolean? = null, val lastEventId: String? = null,
    val inquiry: CustomerInquiryBody? = null, val reply: CustomerInquiryReply? = null,
    val delivery: CustomerInquiryDelivery? = null,
    val history: List<CustomerInquiryHistory?>? = null, val answeredAt: String? = null
) {
    fun checked(): CustomerInquiryRemote {
        check(kind == "customer_inquiry" && UUID.matches(requestId.orEmpty()) &&
            Regex("INQ-20\\d{6}-\\d{4,}").matches(inquiryNumber.orEmpty())) { "문의 접수번호를 확인할 수 없습니다." }
        check(sequence != null && sequence in 1..9007199254740991L &&
            revision != null && revision in 1..9007199254740991L) { "문의 변경 정보를 확인할 수 없습니다." }
        inquiryTimestamp(createdAt); inquiryTimestamp(updatedAt)
        val normalized = when (status) {
            "답변 대기" -> "open"
            "답변 완료", "보관종료" -> "closed"
            else -> status
        }
        check(normalized in setOf("open", "closed")) { "문의 상태를 확인할 수 없습니다." }
        if (expired == true) return copy(status = normalized, inquiry = null, reply = null,
            delivery = null, history = emptyList(), answeredAt = null)
        val q = checkNotNull(inquiry) { "문의 원문을 확인할 수 없습니다." }
        check(q.requestId == requestId && q.category in setOf("묘목·재배", "구매·배송", "기타") &&
            q.customerName != null && q.customerName.length <= 50 &&
            Regex("0\\d{8,10}").matches(q.customerPhone.orEmpty()) &&
            q.customerEmail != null && q.customerEmail.length <= 254 &&
            q.message != null && q.message.length <= 2000) { "문의 내용을 확인할 수 없습니다." }
        check(reply?.text != null && reply.text.length <= 2000 && reply.channel in setOf("sms", "email")) { "문의 답변을 확인할 수 없습니다." }
        if (delivery != null) check(UUID.matches(delivery.id.orEmpty()) &&
            delivery.channel in setOf("sms", "email") && delivery.state in DELIVERY_LABELS.keys) { "문의 발송 결과를 확인할 수 없습니다." }
        check(history.orEmpty().all { it != null && UUID.matches(it.id.orEmpty()) && it.kind in setOf("reply", "status") }) { "문의 이력을 확인할 수 없습니다." }
        history.orEmpty().filterNotNull().forEach { entry ->
            inquiryTimestamp(entry.at)
            if (entry.kind == "reply") check(entry.channel in setOf("sms", "email") && entry.text != null && entry.text.length <= 2000) { "답변 이력이 올바르지 않습니다." }
            else check(entry.status in setOf("open", "closed") && entry.note != null && entry.note.length <= 500) { "처리 이력이 올바르지 않습니다." }
        }
        if (!answeredAt.isNullOrEmpty()) inquiryTimestamp(answeredAt)
        return copy(status = normalized)
    }

    val blockedDelivery: Boolean get() = delivery?.state in setOf("sending", "pending", "unknown")
    val deliveryLabel: String get() = DELIVERY_LABELS[delivery?.state] ?: "발송 전"

    companion object {
        val UUID = Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
        val DELIVERY_LABELS = mapOf("sending" to "발송 확인 필요", "pending" to "문자 결과 대기",
            "unknown" to "발송 결과 불명", "sent" to "메일 발송 완료",
            "success" to "문자 전달 완료", "failed" to "문자 전송 실패")
    }
}

fun inquiryTimestamp(value: String?): Long {
    check(value != null && Regex("20\\d{2}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?Z").matches(value)) { "문의 날짜를 확인할 수 없습니다." }
    val format = SimpleDateFormat(if (value.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
    val position = ParsePosition(0)
    return checkNotNull(format.parse(value, position)).also { check(position.index == value.length) }.time
}

fun inquiryLocalTime(value: String?): String = if (value.isNullOrEmpty()) "" else
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
        .format(Date(inquiryTimestamp(value)))
