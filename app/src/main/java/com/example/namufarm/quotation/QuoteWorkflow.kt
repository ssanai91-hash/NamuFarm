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
    val confirmationPending: Boolean = false, val confirmedAt: Long = 0,
    val originalDraft: SellerQuoteDraft? = null, val revisedAt: Long = 0,
    val revision: Int = 0, val revisionSentAt: Long = 0
)

fun quoteWorkflowFingerprint(draft: SellerQuoteDraft): String {
    val json = Gson().toJsonTree(draft).asJsonObject
    // Preserve delivery fingerprints for records written before per-item remarks existed.
    json.getAsJsonArray("rows").forEach { row ->
        if (row.asJsonObject.get("remarks")?.let { !it.isJsonNull && it.asString.isNotBlank() } != true)
            row.asJsonObject.remove("remarks")
    }
    return MessageDigest.getInstance("SHA-256").digest(Gson().toJson(json).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

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
    fun remove(id: String) {
        check(prefs.edit().remove(id).commit()) { "삭제한 견적의 기기 기록을 정리하지 못했습니다." }
    }
    override fun save(record: QuoteWorkflowRecord) {
        check(prefs.edit().putString(record.request.requestId, gson.toJson(record)).commit()) {
            "견적 기록을 휴대폰에 저장하지 못했습니다. 창을 닫지 말고 다시 시도해주세요."
        }
    }
}

class ServerQuoteRequestChanged : IllegalStateException("서버에서 이미 처리되었거나 내용이 변경된 요청입니다. 최신 목록에서 다시 확인해주세요.")

fun sameServerQuoteRequest(a: WebQuoteRequestEntity, b: WebQuoteRequestEntity): Boolean =
    a.copy(sequence = b.sequence, status = b.status, readAt = b.readAt) == b

class QuoteWorkflow(
    private val store: QuoteWorkflowStore,
    private val call: suspend (String, Map<String, Any>) -> WebQuoteEnvelope,
    private val confirmRemote: suspend (String, SellerQuoteDraft) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val latestRequest: suspend (WebQuoteRequestEntity) -> WebQuoteRequestEntity? = { it }
) {
    fun records() = store.records()
    /** Local-only revision. The caller supplies the server snapshot checked before opening the editor. */
    fun reviseConfirmed(record: QuoteWorkflowRecord, newDraft: SellerQuoteDraft,
        latest: WebQuoteRequestEntity): QuoteWorkflowRecord {
        check(confirmedQuoteEditBlock(record, latest) == null) { confirmedQuoteEditBlock(record, latest).orEmpty() }
        check(store.records().firstOrNull { it.request.requestId == record.request.requestId }?.let { it == record } != false) {
            "휴대폰 견적이 바뀌었습니다. 닫고 다시 열어주세요."
        }
        check(newDraft.phone == record.draft.phone &&
            newDraft.recipient.copy(formalQuoteRequested = record.draft.recipient.formalQuoteRequested) == record.draft.recipient) {
            "고객명·전화번호·이메일·접수정보는 수정할 수 없습니다."
        }
        quoteWorkflowText(record.request, newDraft)
        return record.copy(draft = newDraft, originalDraft = record.originalDraft ?: record.draft,
            revisedAt = clock(), revision = Math.addExact(record.revision, 1), revisionSentAt = 0).also(store::save)
    }
    private suspend fun prepare(request: WebQuoteRequestEntity, draft: SellerQuoteDraft,
        completing: Boolean = false, refreshRequest: Boolean = true): QuoteWorkflowRecord {
        check(request.status == "신규") { "확정견적은 결과 확인만 가능합니다." }
        quoteWorkflowText(request, draft) // Validate the same totals and limits before any state or network write.
        val previous = store.records().firstOrNull { it.request.requestId == request.requestId }
        check(previous?.confirmedAt?.let { it > 0 } != true) { "확정견적은 결과 확인만 가능합니다." }
        check(previous?.confirmationPending != true || previous.draft == draft) {
            "완료 확인 중인 견적은 내용을 바꾸지 말고 같은 내용으로 견적완료를 다시 눌러주세요."
        }
        check(previous?.confirmationPending != true || completing) { "견적완료 확인 중입니다. 전송 대신 견적완료를 다시 눌러주세요." }
        // Pending completion retries the exact acknowledged input, even after the server moved it.
        if (refreshRequest && previous?.confirmationPending != true) {
            val current = latestRequest(request)
            if (current == null || current.status != "신규" || !sameServerQuoteRequest(request, current))
                throw ServerQuoteRequestChanged()
        }
        return (previous?.copy(request = request, draft = draft) ?: QuoteWorkflowRecord(request, draft)).also(store::save)
    }
    /** Persist the draft before opening the phone's composer. This never sends or marks it sent. */
    suspend fun prepareExternalDelivery(request: WebQuoteRequestEntity, draft: SellerQuoteDraft,
        action: QuoteDelivery): QuoteWorkflowRecord {
        require(action == QuoteDelivery.SMS || action == QuoteDelivery.EMAIL)
        draft.validate(action)
        return prepare(request, draft, refreshRequest = false)
    }

    /** Only the seller's explicit confirmation can attest delivery through an external app. */
    suspend fun completeExternal(request: WebQuoteRequestEntity, draft: SellerQuoteDraft,
        sentVia: String): QuoteWorkflowRecord {
        require(sentVia in setOf("sms", "email", "both")) { "실제로 보낸 방법을 선택해주세요." }
        if (sentVia == "email" || sentVia == "both") draft.validate(QuoteDelivery.EMAIL)
        val record = prepare(request, draft, completing = true)
        // Retry the existing journal without sending again or changing its delivery evidence.
        if (record.confirmationPending) return confirm(record)
        val fingerprint = quoteWorkflowFingerprint(draft)
        val sentAt = clock()
        var delivery = record.delivery
        if (sentVia == "sms" || sentVia == "both") delivery = delivery.copy(
            smsState = "success", smsMessageId = "", smsFingerprint = fingerprint,
            smsStatusMessage = "판매자 전송 확인", smsSentAt = sentAt)
        if (sentVia == "email" || sentVia == "both") delivery = delivery.copy(
            emailSentAt = sentAt, emailFingerprint = fingerprint)
        return confirm(record.copy(delivery = delivery))
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
        val text = quoteWorkflowText(request, draft, QuoteDelivery.EMAIL)
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
        val record = prepare(request, draft, completing = true)
        return confirm(record)
    }
    private suspend fun confirm(record: QuoteWorkflowRecord): QuoteWorkflowRecord {
        check(record.delivery.canComplete(record.draft)) { "문자 또는 메일을 먼저 보내 주세요. 현재 견적의 전송이 완료되어야 합니다." }
        // Crash/network failure leaves this exact input retryable; server confirmation is idempotent.
        store.save(record.copy(confirmationPending = true))
        confirmRemote(record.request.requestId, record.draft)
        return record.copy(confirmationPending = false, confirmedAt = clock()).also(store::save)
    }
}

fun quoteWorkflowText(request: WebQuoteRequestEntity, draft: SellerQuoteDraft,
    channel: QuoteDelivery = QuoteDelivery.SMS): String {
    val lines = draft.validate(QuoteDelivery.COMPLETE)
    val total = lines.sumOf { it.orderQty.toLong() * it.retailPrice } + draft.shipping() +
        if (draft.recipient.formalQuoteRequested) draft.options.taxAmount() else 0L
    require(lines.all { it.orderQty <= 1_000_000 }) { "품목별 수량은 1,000,000주 이하여야 합니다." }
    require(total in 0..Int.MAX_VALUE.toLong()) { "세액을 포함한 총금액이 저장 범위를 넘습니다." }
    fun number(value: Long) = "%,d".format(java.util.Locale.KOREA, value)
    val customer = draft.recipient.customerName.ifBlank { "고객" }
    val quoteNumber = request.receiptNumber.ifBlank { request.requestId }
    val separator = "-".repeat(53)
    val items = lines.flatMapIndexed { index, line ->
        listOf("품목${index + 1} : ${line.speciesName} ${number(line.orderQty.toLong())}주",
            "판매금액 : ${number(line.orderQty.toLong() * line.retailPrice)}원") +
            draft.rows[index].remarks.takeIf(String::isNotBlank)?.let { listOf("비고 : $it") }.orEmpty() + listOf("")
    }
    val sellerNotes = draft.options.notes.trim().takeIf(String::isNotBlank)?.let { listOf("요청사항 : $it", "") }.orEmpty()
    val shippingFee = if (draft.prepaid) draft.shipping() else
        draft.fee.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val shippingLine = if (draft.prepaid) "택배비 : 선불 ${number(shippingFee.toLong())}원" else
        "택배비 : 후불 / 선불 시 ${number(shippingFee.toLong())}원"
    val account = draft.options.account.trim()
    val payment = when (account) {
        "", "농협 328-12-035671 / 오진웅", "농협 328-12-035671 오진웅" ->
            "입금계좌 : 농협 328-12-035671\n예금주 : 오진웅"
        else -> "입금계좌 안내 : $account"
    }
    // Match Napi's existing SMS and email templates, including spacing and signatures.
    val message = if (channel == QuoteDelivery.EMAIL) {
        listOf(
            "${customer}님, 안녕하세요.", "",
            "나무팜농원입니다.", "요청하신 묘목 견적을 안내드립니다.", "",
            separator, "", "견적번호 : $quoteNumber", ""
        ) + items + sellerNotes + listOf(
            shippingLine,
            if (draft.prepaid) "총금액 : ${number(total)}원  (총금액 포함)" else "총금액 : ${number(total)}원",
            "", payment, "", separator, "",
            "견적 내용을 확인하시고 궁금하신 사항은 편하게 연락 부탁드립니다.",
            "감사합니다.", "", "나무팜농원", "010-4446-0304 / 043-732-0980"
        )
    } else {
        listOf(
            "${customer}님, 안녕하세요.", "",
            "나무팜농원입니다.", "", "요청하신 묘목 견적을 안내드립니다.", "",
            separator, "견적번호 : $quoteNumber", ""
        ) + items + sellerNotes + listOf(
            shippingLine,
            if (draft.prepaid) "총금액 : ${number(total)}원 (택배비 포함)" else "총금액 : ${number(total)}원",
            separator, "", payment, "", "감사합니다."
        )
    }
    return message.joinToString("\n").trim()
}

