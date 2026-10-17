package com.example.namufarm.quotation

import android.content.Context
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.data.remote.WebQuoteEnvelope
import com.google.gson.Gson
import java.security.MessageDigest

/** Delivery and confirmation are independent; opening an external app never counts as delivery. */
data class QuoteDeliveryRecord(
    val smsState: String = "", val smsMessageId: String = "", val smsFingerprint: String = "",
    val smsStatusMessage: String = "", val smsSentAt: Long = 0, val emailSentAt: Long = 0, val emailFingerprint: String = ""
) {
    fun canComplete(draft: SellerQuoteDraft): Boolean {
        val fingerprint = quoteWorkflowFingerprint(draft)
        return (smsState == "success" && smsFingerprint == fingerprint) ||
            (emailSentAt > 0 && emailFingerprint == fingerprint)
    }
    fun smsLabel(draft: SellerQuoteDraft): String = when {
        smsState == "success" && smsFingerprint == quoteWorkflowFingerprint(draft) -> "전송완료"
        smsState == "success" -> "재전송 필요"
        smsState == "pending" -> "결과확인 중"
        smsState == "failed" -> "전송실패"
        else -> "미전송"
    }
    fun emailLabel(draft: SellerQuoteDraft): String = when {
        emailSentAt == 0L -> "미전송"
        emailFingerprint == quoteWorkflowFingerprint(draft) -> "전송완료"
        else -> "재전송 필요"
    }
}

data class QuoteWorkflowRecord(
    val request: WebQuoteRequestEntity, val draft: SellerQuoteDraft,
    val delivery: QuoteDeliveryRecord = QuoteDeliveryRecord(),
    val confirmationPending: Boolean = false, val confirmedAt: Long = 0
)

fun quoteWorkflowFingerprint(draft: SellerQuoteDraft): String = MessageDigest.getInstance("SHA-256")
    .digest(Gson().toJson(draft).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

interface QuoteWorkflowStore {
    fun records(): List<QuoteWorkflowRecord>
    fun save(record: QuoteWorkflowRecord)
}

/** Separate pending journal from results. commit() must succeed before advancing the UI. */
class AndroidQuoteWorkflowStore(context: Context) : QuoteWorkflowStore {
    private val prefs = context.applicationContext.getSharedPreferences("quote_workflow_v1", Context.MODE_PRIVATE)
    private val gson = Gson()
    override fun records(): List<QuoteWorkflowRecord> = prefs.all.values.filterIsInstance<String>().map {
        gson.fromJson(it, QuoteWorkflowRecord::class.java)
    }
    override fun save(record: QuoteWorkflowRecord) {
        check(prefs.edit().putString(record.request.requestId, gson.toJson(record)).commit()) {
            "견적 기록을 휴대폰에 저장하지 못했습니다. 창을 닫지 말고 다시 시도해주세요."
        }
    }
}

class QuoteWorkflow(
    private val store: QuoteWorkflowStore,
    private val call: suspend (String, Map<String, Any>) -> WebQuoteEnvelope,
    private val confirmRemote: suspend (String, SellerQuoteDraft) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun records() = store.records()
    private fun prepare(request: WebQuoteRequestEntity, draft: SellerQuoteDraft): QuoteWorkflowRecord {
        check(request.status == "신규") { "확정견적은 결과 확인만 가능합니다." }
        quoteWorkflowText(request, draft) // Validate the same totals and limits before any state or network write.
        val previous = store.records().firstOrNull { it.request.requestId == request.requestId }
        check(previous?.confirmedAt?.let { it > 0 } != true) { "확정견적은 결과 확인만 가능합니다." }
        check(previous?.confirmationPending != true || previous.draft == draft) {
            "완료 확인 중인 견적은 내용을 바꾸지 말고 같은 내용으로 견적완료를 다시 눌러주세요."
        }
        return (previous?.copy(draft = draft) ?: QuoteWorkflowRecord(request, draft)).also(store::save)
    }
    suspend fun sendSms(request: WebQuoteRequestEntity, draft: SellerQuoteDraft): QuoteWorkflowRecord {
        var record = prepare(request, draft)
        val fingerprint = quoteWorkflowFingerprint(draft)
        if (record.delivery.smsState == "pending" && record.delivery.smsMessageId.isNotBlank()) {
            check(record.delivery.smsFingerprint == fingerprint) { "이전 문자의 최종 결과를 먼저 확인해주세요." }
            return refreshSms(request.requestId)
        }
        val text = quoteWorkflowText(request, draft)
        require(text.length <= 1000) { "문자 내용이 1,000자를 넘습니다. 메일로 전달해주세요." }
        val response = call("send_quote_sms", mapOf("requestId" to request.requestId,
            "recipientPhone" to draft.phone, "text" to text))
        check(response.sent == true && response.recipientPhone == draft.phone.filter(Char::isDigit) &&
            !response.messageId.isNullOrBlank()) { "문자 접수를 확인하지 못했습니다. 재전송 전 발송 내역을 확인해주세요." }
        record = record.copy(delivery = record.delivery.copy(smsState = "pending",
            smsMessageId = response.messageId.orEmpty(), smsFingerprint = fingerprint, smsSentAt = 0))
        store.save(record)
        return record
    }
    suspend fun refreshSms(id: String): QuoteWorkflowRecord {
        val record = store.records().first { it.request.requestId == id }
        val response = call("quote_sms_status", mapOf("messageId" to record.delivery.smsMessageId))
        check(response.checked == true && response.messageId == record.delivery.smsMessageId &&
            response.state in setOf("success", "pending", "failed")) { "문자 최종 결과를 확인하지 못했습니다." }
        val success = response.state == "success" && response.statusCode == "4000"
        check(response.state != "success" || success) { "문자 최종 성공 코드를 확인하지 못했습니다." }
        return record.copy(delivery = record.delivery.copy(smsState = response.state.orEmpty(),
            smsSentAt = if (success) clock() else 0, smsStatusMessage = response.statusMessage.orEmpty())).also(store::save)
    }
    suspend fun sendEmail(request: WebQuoteRequestEntity, draft: SellerQuoteDraft): QuoteWorkflowRecord {
        draft.validate(QuoteDelivery.EMAIL)
        val record = prepare(request, draft)
        val text = quoteWorkflowText(request, draft)
        val html = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\n", "<br>")
        val recipient = draft.recipient.customerEmail.trim()
        val response = call("send_quote_email", mapOf("requestId" to request.requestId,
            "recipientEmail" to recipient, "senderName" to draft.options.issuer.businessName,
            "quoteNumber" to request.receiptNumber.ifBlank { request.requestId },
            "subject" to "[나무팜] 요청하신 견적 안내", "bodyText" to text, "bodyHtml" to html))
        check(response.sent == true && response.recipientEmail == recipient) { "메일 전송 완료를 확인하지 못했습니다." }
        return record.copy(delivery = record.delivery.copy(emailSentAt = clock(),
            emailFingerprint = quoteWorkflowFingerprint(draft))).also(store::save)
    }
    suspend fun complete(request: WebQuoteRequestEntity, draft: SellerQuoteDraft): QuoteWorkflowRecord {
        val record = prepare(request, draft)
        check(record.delivery.canComplete(draft)) { "문자 또는 메일을 먼저 보내 주세요. 현재 견적의 전송이 완료되어야 합니다." }
        // Crash/network failure leaves this exact input retryable; server confirmation is idempotent.
        store.save(record.copy(confirmationPending = true))
        confirmRemote(request.requestId, draft)
        return record.copy(confirmationPending = false, confirmedAt = clock()).also(store::save)
    }
}

fun quoteWorkflowText(request: WebQuoteRequestEntity, draft: SellerQuoteDraft): String {
    val lines = draft.validate(QuoteDelivery.COMPLETE)
    val total = lines.sumOf { it.orderQty.toLong() * it.retailPrice } + draft.shipping() +
        if (draft.recipient.formalQuoteRequested) draft.options.taxAmount() else 0L
    require(lines.all { it.orderQty <= 1_000_000 }) { "품목별 수량은 1,000,000주 이하여야 합니다." }
    require(total in 0..Int.MAX_VALUE.toLong()) { "세액을 포함한 총금액이 저장 범위를 넘습니다." }
    return buildString {
        appendLine("${draft.recipient.customerName}님, 안녕하세요. 나무팜농원입니다.")
        appendLine("견적번호 : ${request.receiptNumber.ifBlank { request.requestId }}")
        lines.forEach { line ->
            appendLine("${line.speciesName} ${line.orderQty}주 × ${"%,d".format(line.retailPrice)}원 = ${"%,d".format(line.orderQty.toLong() * line.retailPrice)}원")
        }
        appendLine(if (draft.prepaid) "택배비 : 선불 ${"%,d".format(draft.shipping())}원" else "택배비 : 후불 (합계 미포함)")
        appendLine("총금액 : ${"%,d".format(total)}원")
        appendLine("입금계좌 / 예금주 : ${draft.options.account}")
        append("문의 : ${draft.options.issuer.phone}")
    }
}
