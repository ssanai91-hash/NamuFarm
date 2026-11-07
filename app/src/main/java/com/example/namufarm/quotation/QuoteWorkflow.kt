package com.example.namufarm.quotation

import android.content.Context
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.data.remote.WebQuoteEnvelope
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.util.UUID

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
    val revision: Int = 0, val revisionSentAt: Long = 0,
    val originalDelivery: QuoteDeliveryRecord? = null,
    val deliveryHistory: List<ConfirmedQuoteDelivery>? = null,
    val pendingConfirmedDelivery: ConfirmedQuoteDelivery? = null,
    // Shared confirmed quote (server confirmedQuoteVersion:1). Nullable: older records load without them.
    val shared: SharedQuoteLink? = null,
    val pendingSharedRevision: PendingSharedRevision? = null,
    val sharedDelivery: SharedQuoteReservation? = null,
    val sharedPayment: SharedQuotePayment? = null,
    /** Server revision that differs from this phone's copy; the seller chooses which to keep. */
    val sharedConflict: Int? = null,
    /** Copies replaced by '서버 내용 사용', kept on this phone. */
    val sharedArchive: List<SellerQuoteDraft>? = null
)

data class ConfirmedQuoteDelivery(val id: String, val revision: Int, val documentNumber: String,
    val sentVia: String, val fingerprint: String, val sentAt: Long, val draft: SellerQuoteDraft,
    val previousDelivery: QuoteDeliveryRecord)

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
    private val latestRequest: suspend (WebQuoteRequestEntity) -> WebQuoteRequestEntity? = { it },
    private val markConfirmedDelivered: suspend (String) -> Unit = { error("확정견적 발송 상태 연결이 필요합니다.") },
    private val shared: SharedQuoteGateway? = null
) {
    fun records() = store.records()
    /**
     * Revision of a confirmed quote. With shared quotes the new content is first saved on the server as a
     * new version (so PC/other phones see the same price); an older server keeps it local as before.
     */
    suspend fun reviseConfirmed(record: QuoteWorkflowRecord, newDraft: SellerQuoteDraft,
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
        check(record.sharedDelivery == null && record.sharedPayment == null && record.sharedConflict == null) {
            "보낸 결과 확인·입금확인·서버 견적 선택을 먼저 마쳐주세요."
        }
        val base = ensureShared(record, newDraft) ?: record
        return base.copy(draft = newDraft, originalDraft = record.originalDraft ?: record.draft,
            originalDelivery = record.originalDelivery ?: record.delivery,
            revisedAt = clock(), revision = Math.addExact(record.revision, 1), revisionSentAt = 0).also(store::save)
    }
    private fun checkCurrentConfirmed(record: QuoteWorkflowRecord) {
        check(store.records().firstOrNull { it.request.requestId == record.request.requestId }?.let { it == record } != false) {
            "휴대폰 견적이 바뀌었습니다. 닫고 다시 열어주세요."
        }
    }
    /**
     * Preparing/launching an external app is not a send. With shared quotes the server reserves this
     * phone's delivery first, so another device cannot send a different price at the same time.
     */
    suspend fun prepareConfirmedExternal(record: QuoteWorkflowRecord, action: QuoteDelivery,
        payload: String? = null): WebQuoteRequestEntity {
        require(action in setOf(QuoteDelivery.SMS, QuoteDelivery.EMAIL))
        record.draft.validate(action); val text = quoteWorkflowText(record.request, record.draft, action)
        checkCurrentConfirmed(record)
        check(record.sharedConflict == null) { "다른 기기에서 수정한 견적입니다. 사용할 내용을 먼저 선택해주세요." }
        val latest = latestRequest(record.request)
        check(confirmedQuoteEditBlock(record, latest) == null) { confirmedQuoteEditBlock(record, latest).orEmpty() }
        store.save(record)
        reserveShared(record, record.draft, if (action == QuoteDelivery.EMAIL) "email" else "sms", payload ?: text)
        return checkNotNull(latest)
    }
    /** Called only by the existing explicit '고객에게 보냈나요?' confirmation sheet. */
    suspend fun completeConfirmedExternal(record: QuoteWorkflowRecord, sentVia: String,
        documentNumber: String): QuoteWorkflowRecord {
        require(sentVia in setOf("sms", "email", "both")) { "실제로 보낸 방법을 선택해주세요." }
        require(documentNumber.matches(Regex("NQ-[0-9]{8}-[A-F0-9]{8}"))) { "보낸 공식견적서 번호를 확인해주세요." }
        if (sentVia in setOf("email", "both")) record.draft.validate(QuoteDelivery.EMAIL)
        else record.draft.validate(QuoteDelivery.COMPLETE)
        checkCurrentConfirmed(record)
        if (record.pendingConfirmedDelivery != null) return retryConfirmedDelivery(record)
        val latest = latestRequest(record.request)
        check(confirmedQuoteEditBlock(record, latest) == null) { confirmedQuoteEditBlock(record, latest).orEmpty() }
        val evidence = ConfirmedQuoteDelivery(UUID.randomUUID().toString(), record.revision, documentNumber,
            sentVia, quoteWorkflowFingerprint(record.draft), clock(), record.draft, record.delivery)
        val journal = record.copy(pendingConfirmedDelivery = evidence)
        store.save(journal) // Persist the attested snapshot before any remote acknowledgement.
        return finishConfirmedDelivery(journal, checkNotNull(latest))
    }
    suspend fun retryConfirmedDelivery(record: QuoteWorkflowRecord): QuoteWorkflowRecord {
        checkCurrentConfirmed(record)
        val latest = latestRequest(record.request) ?: error("최신 서버 상태를 확인하지 못했습니다.")
        return finishConfirmedDelivery(record, latest)
    }
    private suspend fun finishConfirmedDelivery(record: QuoteWorkflowRecord, latest: WebQuoteRequestEntity): QuoteWorkflowRecord {
        val evidence = checkNotNull(record.pendingConfirmedDelivery)
        check(evidence.fingerprint == quoteWorkflowFingerprint(record.draft) && evidence.revision == record.revision)
        check(confirmedQuoteEditBlock(record.copy(pendingConfirmedDelivery = null), latest) == null) {
            confirmedQuoteEditBlock(record.copy(pendingConfirmedDelivery = null), latest).orEmpty()
        }
        // Shared quotes: the seller's attestation confirms the reserved delivery for this exact version.
        val sharedDone = confirmShared(record, record.draft, evidence.sentVia, quoteWorkflowText(record.request, record.draft,
            if (evidence.sentVia == "email") QuoteDelivery.EMAIL else QuoteDelivery.SMS))
        if (sharedDone == null && latest.status == "견적확정") markConfirmedDelivered(record.request.requestId)
        val done = sharedDone ?: record
        // Already delivered: no status write and never resubmit revised prices to the server.
        var delivery = done.delivery
        if (evidence.sentVia in setOf("sms", "both")) delivery = delivery.copy(smsState = "success",
            smsMessageId = "", smsFingerprint = evidence.fingerprint, smsStatusMessage = "판매자 전송 확인", smsSentAt = evidence.sentAt)
        if (evidence.sentVia in setOf("email", "both")) delivery = delivery.copy(emailSentAt = evidence.sentAt, emailFingerprint = evidence.fingerprint)
        val history = done.deliveryHistory.orEmpty().filterNot { it.id == evidence.id } + evidence
        return done.copy(delivery = delivery, deliveryHistory = history, revisionSentAt = evidence.sentAt,
            pendingConfirmedDelivery = null).also(store::save)
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
        // A shared reservation already moved the request to 견적확정 on the server.
        if (refreshRequest && previous?.confirmationPending != true && previous?.sharedDelivery == null && previous?.shared == null) {
            val current = latestRequest(request)
            if (current == null || current.status != "신규" || !sameServerQuoteRequest(request, current))
                throw ServerQuoteRequestChanged()
        }
        return (previous?.copy(request = request, draft = draft) ?: QuoteWorkflowRecord(request, draft)).also(store::save)
    }
    /**
     * Persist the draft before opening the phone's composer. This never sends or marks it sent.
     * With shared quotes this version is saved on the server and the delivery reserved first.
     */
    suspend fun prepareExternalDelivery(request: WebQuoteRequestEntity, draft: SellerQuoteDraft,
        action: QuoteDelivery, reserve: Boolean = true): QuoteWorkflowRecord {
        require(action == QuoteDelivery.SMS || action == QuoteDelivery.EMAIL)
        draft.validate(action)
        val record = prepare(request, draft, refreshRequest = false)
        if (!reserve) return record
        return reserveShared(record, draft, if (action == QuoteDelivery.EMAIL) "email" else "sms",
            quoteWorkflowText(request, draft, action)) ?: record
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
        // Shared quotes: the server version is the confirmation; save_quote_pricing is not repeated.
        val sharedDone = confirmShared(record, draft, sentVia,
            quoteWorkflowText(request, draft, if (sentVia == "email") QuoteDelivery.EMAIL else QuoteDelivery.SMS))
        if (sharedDone != null) return sharedDone.copy(delivery = delivery, confirmedAt = clock(),
            confirmationPending = false).also(store::save)
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

    // ---- Shared confirmed quotes (server confirmedQuoteVersion:1) ---------------------------------
    private val gson = Gson()

    /** Fresh server copy for a write decision. Null when the server has no shared-quote API (legacy path). */
    suspend fun sharedState(id: String): SharedQuoteState? {
        val gateway = shared ?: return null
        val result = try { gateway.call("get_confirmed_quote", mapOf("requestId" to id)) }
            catch (_: SharedQuoteUnavailable) { return null }
        return SharedQuoteState(checkNotNull(result.obj("quote")) { "서버 확정견적을 확인하지 못했습니다." })
    }

    /** Resend the journaled body with its original operation ID; a lost reply never creates a second version. */
    private suspend fun sendRevision(record: QuoteWorkflowRecord): QuoteWorkflowRecord {
        val gateway = checkNotNull(shared)
        val pending = checkNotNull(record.pendingSharedRevision)
        val body = gson.fromJson(checkNotNull(pending.body), JsonObject::class.java)
        val fields = mapOf<String, Any>("requestId" to body.get("requestId").asString,
            "operationId" to body.get("operationId").asString, "expectedRevision" to body.get("expectedRevision").asInt,
            "quotePricing" to body.getAsJsonObject("quotePricing"), "quoteDetails" to body.getAsJsonObject("quoteDetails"))
        val result = try { gateway.call("revise_confirmed_quote", fields) }
        catch (e: SharedQuoteException) {
            // A definite rejection saved nothing; the seller's edit stays in the open editor.
            if (e.code in setOf("CONFLICT", "INVALID", "UPGRADE_REQUIRED")) store.save(record.copy(pendingSharedRevision = null))
            throw e
        }
        val state = SharedQuoteState(checkNotNull(result.obj("quote")) { "서버 확정견적을 확인하지 못했습니다." })
        val applied = result.get("appliedRevision")?.asInt ?: 0
        check(state.version != null && applied in 1..state.revision) { "서버의 확정견적 저장을 확인하지 못했습니다. 다시 확인해주세요." }
        val draft = checkNotNull(pending.draft)
        return record.copy(pendingSharedRevision = null, sharedConflict = null,
            shared = if (applied == state.revision) state.link(draft) else record.shared).also(store::save)
    }

    /**
     * Make the server's shared version equal to [draft]; null on an older server.
     * Only this phone's copy of the version it last saw (or its own legacy confirmation) may replace it.
     */
    private suspend fun ensureShared(record: QuoteWorkflowRecord, draft: SellerQuoteDraft,
        overwrite: Boolean = false): QuoteWorkflowRecord? {
        if (shared == null) return null
        var current = record
        if (current.pendingSharedRevision != null) current = sendRevision(current)
        val state = sharedState(current.request.requestId) ?: return null
        check(state.payment == null) { "입금확인된 견적은 수정하거나 다시 보낼 수 없습니다." }
        if (state.sameContent(draft)) return current.copy(shared = state.link(draft), sharedConflict = null).also(store::save)
        if (state.revision > 0 && !overwrite) {
            val pricing = state.pricing
            val samePrices = pricing != null && state.version == null &&
                sharedContentKey(pricing, JsonObject()) == sharedContentKey(sharedPricing(draft), JsonObject())
            val ownLegacy = state.version == null && (samePrices || current.confirmedAt > 0 || current.revision > 0)
            check(ownLegacy || current.shared?.revision == state.revision) {
                "다른 기기에서 먼저 확정·수정한 견적입니다. 동기화 후 최신 견적을 확인해주세요."
            }
        }
        val operationId = UUID.randomUUID().toString()
        val body = JsonObject().apply {
            addProperty("requestId", current.request.requestId)
            addProperty("operationId", operationId)
            addProperty("expectedRevision", state.revision)
            add("quotePricing", sharedPricing(draft))
            add("quoteDetails", sharedDetails(draft, state.details))
        }
        val journal = current.copy(pendingSharedRevision = PendingSharedRevision(operationId, body.toString(), draft))
        store.save(journal) // Before the network.
        return sendRevision(journal)
    }

    /**
     * Before opening the phone's SMS/mail app: save this version on the server and reserve the delivery,
     * so no other device sends a different price meanwhile. Null on an older server.
     */
    suspend fun reserveShared(record: QuoteWorkflowRecord, draft: SellerQuoteDraft, channel: String,
        payload: String): QuoteWorkflowRecord? {
        if (shared == null) return null
        require(channel in setOf("sms", "email"))
        val payloadFingerprint = sha256Hex(payload)
        record.sharedDelivery?.let { old ->
            check(old.channel == channel && old.payloadFingerprint == payloadFingerprint) {
                "앞서 연 ${if (old.channel == "sms") "문자" else "메일"}을 실제로 보냈는지 먼저 알려주세요. " +
                    "‘견적완료’ 또는 ‘아직 안 보냈어요’를 눌러주세요."
            }
        }
        val linked = ensureShared(record, draft) ?: return null
        val link = checkNotNull(linked.shared) { "서버 확정견적과 보낼 내용을 맞추지 못했습니다. 동기화 후 다시 보내주세요." }
        val reservation = linked.sharedDelivery ?: SharedQuoteReservation(UUID.randomUUID().toString(), channel,
            link.revision, link.fingerprint, payloadFingerprint)
        val journal = linked.copy(sharedDelivery = reservation).also(store::save) // Before the network.
        try {
            checkNotNull(shared).call("reserve_quote_delivery", mapOf("requestId" to record.request.requestId,
                "deliveryId" to checkNotNull(reservation.deliveryId), "expectedRevision" to reservation.revision,
                "quoteFingerprint" to checkNotNull(reservation.fingerprint), "channel" to channel, "mode" to "external",
                "payloadFingerprint" to payloadFingerprint))
        } catch (e: SharedQuoteException) {
            store.save(journal.copy(sharedDelivery = null)) // Rejected: nothing is reserved under this ID.
            throw e
        }
        return journal
    }

    /** Only after the seller's explicit '보냈어요'. Records this version as delivered on the server. */
    private suspend fun confirmShared(record: QuoteWorkflowRecord, draft: SellerQuoteDraft, sentVia: String,
        payload: String): QuoteWorkflowRecord? {
        if (shared == null) return null
        var current = record
        if (current.sharedDelivery == null) {
            val state = sharedState(current.request.requestId) ?: return null
            // Re-attesting a version the server already records as delivered adds nothing.
            if (state.sameContent(draft) && state.deliveredCurrent)
                return current.copy(shared = state.link(draft), sharedConflict = null).also(store::save)
            // e.g. a formal PDF shared from another app: reserve now with what the seller confirmed sending.
            current = reserveShared(current, draft, if (sentVia == "email") "email" else "sms", payload) ?: return null
        } else {
            check(current.shared?.localKey == sharedLocalKey(draft)) {
                "문자·메일 앱을 연 뒤 견적 내용이 바뀌었습니다. 실제로 보낸 내용으로 되돌리거나, 바꾼 내용을 다시 보내주세요."
            }
        }
        val reservation = checkNotNull(current.sharedDelivery)
        val result = checkNotNull(shared).call("confirm_quote_delivery", mapOf("requestId" to current.request.requestId,
            "deliveryId" to checkNotNull(reservation.deliveryId), "expectedRevision" to reservation.revision,
            "quoteFingerprint" to checkNotNull(reservation.fingerprint), "confirmed" to true))
        val state = SharedQuoteState(checkNotNull(result.obj("quote")) { "서버 발송 기록을 확인하지 못했습니다." })
        check(state.deliveredCurrent) { "서버 발송 기록을 확인하지 못했습니다. 다시 확인해주세요." }
        return current.copy(sharedDelivery = null, shared = state.link(draft)).also(store::save)
    }

    /** The seller said '아직 안 보냈어요': release this phone's reservation so another device can send. */
    suspend fun cancelShared(requestId: String): QuoteWorkflowRecord? {
        val record = store.records().firstOrNull { it.request.requestId == requestId } ?: return null
        val reservation = record.sharedDelivery ?: return record
        val gateway = shared ?: return record
        try {
            gateway.call("cancel_quote_delivery", mapOf("requestId" to requestId,
                "deliveryId" to checkNotNull(reservation.deliveryId), "expectedRevision" to reservation.revision,
                "quoteFingerprint" to checkNotNull(reservation.fingerprint), "notSent" to true))
        } catch (e: SharedQuoteException) {
            if (e.code != "CONFLICT") throw e // CONFLICT: no longer reserved on the server.
        }
        return record.copy(sharedDelivery = null).also(store::save)
    }

    /**
     * After a sync, without network: link records to the shared version in the downloaded rows.
     * An unchanged copy takes another device's newer version; a different copy waits for the seller.
     */
    fun reconcileShared(requests: List<WebQuoteRequestEntity>): Int {
        val byId = requests.associateBy { it.requestId }
        var changed = 0
        store.records().forEach { record ->
            if (record.pendingSharedRevision != null || record.sharedDelivery != null || record.sharedPayment != null ||
                record.confirmationPending || record.pendingConfirmedDelivery != null) return@forEach
            val entity = byId[record.request.requestId] ?: return@forEach
            val state = embeddedSharedState(entity) ?: return@forEach
            val link = record.shared
            val next = runCatching {
                when {
                    state.sameContent(record.draft) -> record.copy(shared = state.link(record.draft), sharedConflict = null)
                    link != null && link.localKey == sharedLocalKey(record.draft) && state.revision > link.revision -> {
                        val draft = draftFromShared(entity, checkNotNull(state.pricing), state.details, record.draft)
                            .also { it.validate(QuoteDelivery.COMPLETE) }
                        record.copy(draft = draft, shared = state.link(draft), sharedConflict = null)
                    }
                    else -> record.copy(sharedConflict = state.revision)
                }
            }.getOrNull() ?: return@forEach
            if (next != record) { store.save(next); changed++ }
        }
        return changed
    }

    /** '서버 내용 사용'. This phone's previous copy is kept in the record's archive. */
    suspend fun useSharedServer(record: QuoteWorkflowRecord): QuoteWorkflowRecord {
        checkCurrentConfirmed(record)
        val state = sharedState(record.request.requestId) ?: error("서버에 확정견적 공유 기능이 아직 배포되지 않았습니다.")
        val pricing = checkNotNull(state.pricing) { "서버 확정견적 가격이 없습니다." }
        val draft = draftFromShared(record.request, pricing, state.details, record.draft).also { it.validate(QuoteDelivery.COMPLETE) }
        return record.copy(draft = draft, shared = state.link(draft), sharedConflict = null,
            sharedArchive = (record.sharedArchive.orEmpty() + record.draft).takeLast(20)).also(store::save)
    }

    /** '이 휴대폰 수정본 저장': this phone's copy becomes the new server version (server history keeps the other). */
    suspend fun keepLocalShared(record: QuoteWorkflowRecord): QuoteWorkflowRecord {
        checkCurrentConfirmed(record)
        return ensureShared(record.copy(sharedConflict = null), record.draft, overwrite = true)
            ?: error("서버에 확정견적 공유 기능이 아직 배포되지 않았습니다.")
    }

    /**
     * 입금확인 for a shared web quote: the server claim comes first (same lock as Napi's sale guard),
     * then the local order with the very same order number. Retries reuse the journal; a lost reply
     * never creates a new number.
     */
    suspend fun confirmSharedPayment(record: QuoteWorkflowRecord, newOrderNumber: () -> String,
        createOrder: suspend (String, QuoteWorkflowRecord) -> Unit): QuoteWorkflowRecord {
        val gateway = shared ?: error("서버에 확정견적 공유 기능이 배포된 뒤 입금확인할 수 있습니다. 지금은 PC 나피에서 입금확인해주세요.")
        checkCurrentConfirmed(record)
        var current = record
        val journal = current.sharedPayment
        if (journal?.confirmed == true) return finishSharedPayment(current, createOrder)
        val state = sharedState(current.request.requestId)
            ?: error("서버에 확정견적 공유 기능이 배포된 뒤 입금확인할 수 있습니다. 지금은 PC 나피에서 입금확인해주세요.")
        state.payment?.let { payment ->
            val number = payment.get("orderNumber")?.asString.orEmpty()
            check(number.isNotEmpty() && journal != null && number == journal.orderNumber) {
                "다른 기기에서 이미 입금확인한 견적입니다 · 주문번호 $number"
            }
            current = current.copy(sharedPayment = journal.copy(confirmed = true)).also(store::save)
            return finishSharedPayment(current, createOrder)
        }
        check(state.version != null) { "고객에게 보낸 기록이 서버에 아직 없습니다. 현재 견적을 보내고 ‘견적완료’한 뒤 입금확인해주세요." }
        check(state.sameContent(current.draft)) { "서버의 최신 확정견적과 내용이 다릅니다. 동기화 후 다시 확인해주세요." }
        check(state.deliveredCurrent) { "수정한 견적을 아직 고객에게 보내지 않았습니다. 현재 견적을 먼저 보내주세요." }
        val claim = journal?.takeIf { it.revision == state.revision && it.fingerprint == state.fingerprint }
            ?: SharedQuotePayment(UUID.randomUUID().toString(), journal?.orderNumber ?: newOrderNumber(),
                state.revision, state.fingerprint, sharedAmount(current.draft))
        current = current.copy(sharedPayment = claim).also(store::save) // Before the network.
        val result = gateway.call("confirm_quote_payment", mapOf("requestId" to current.request.requestId,
            "operationId" to checkNotNull(claim.operationId), "orderNumber" to checkNotNull(claim.orderNumber),
            "expectedRevision" to claim.revision, "quoteFingerprint" to checkNotNull(claim.fingerprint),
            "amount" to claim.amount))
        check(result.obj("payment")?.get("orderNumber")?.asString == claim.orderNumber) {
            "서버 입금확인 응답을 확인하지 못했습니다. 같은 견적으로 다시 확인해주세요."
        }
        current = current.copy(sharedPayment = claim.copy(confirmed = true)).also(store::save)
        return finishSharedPayment(current, createOrder)
    }

    private suspend fun finishSharedPayment(record: QuoteWorkflowRecord,
        createOrder: suspend (String, QuoteWorkflowRecord) -> Unit): QuoteWorkflowRecord {
        val payment = checkNotNull(record.sharedPayment)
        if (!payment.orderCreated) createOrder(checkNotNull(payment.orderNumber), record)
        return record.copy(sharedPayment = payment.copy(orderCreated = true)).also(store::save)
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

