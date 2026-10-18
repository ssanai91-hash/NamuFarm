package com.example.namufarm.quotemode

import androidx.room.withTransaction
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.data.DeferredWebRequestEntity
import com.example.namufarm.data.remote.*
import com.example.namufarm.quotation.*
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Uses the same device connection and operating endpoint as the original master sync. */
class InitialQuoteConnection(
    private val database: InitialQuoteDatabase,
    private val gateway: OperationsMasterGateway
) {
    private val dao = database.inbox()
    private val mutex = Mutex()
    private val gson = Gson()
    fun localRequests() = dao.requests()
    fun deferredCount() = dao.deferredCount()
    fun read(id: String) = dao.read(id, System.currentTimeMillis())

    suspend fun refresh(): Int = mutex.withLock {
        repeat(3) { attempt ->
            try { return@withLock downloadSnapshot() }
            catch (e: CancellationException) { throw e }
            catch (e: MasterServerException) {
                if (e.code !in setOf("BUSY", "CONFLICT") || attempt == 2) throw e
            }
        }
        error("서버 목록이 변경되었습니다. 다시 가져와주세요.")
    }
    private suspend fun downloadSnapshot(): Int {
        val rows = mutableListOf<WebQuoteRequestEntity>()
        val deferred = mutableListOf<DeferredWebRequestEntity>()
        val ids = mutableSetOf<String>()
        var cursor = 0L
        var snapshot: String? = null
        var total = -1
        var watermark = 0L
        repeat(100) {
            val fields = mutableMapOf<String, Any>("afterSequence" to cursor)
            snapshot?.let { fields["snapshotId"] = it }
            val response = gson.fromJson(gateway.request("snapshot_requests", fields), WebQuoteEnvelope::class.java)
            val page = WebQuoteValidator.checkedPage(response, cursor) { remote ->
                if (remote.encryptedRequest != null) throw LegacyRequestKeyUnavailable()
                remote
            }
            val token = checkNotNull(response.snapshotId)
            check(Regex("[a-f0-9]{64}").matches(token))
            check(response.total != null && response.total in 0..10000 &&
                response.watermark != null && response.watermark in 0..9_007_199_254_740_991L)
            if (snapshot == null) {
                snapshot = token; total = response.total; watermark = response.watermark
            }
            check(token == snapshot && total == response.total && watermark == response.watermark) {
                "견적 목록이 변경되어 기존 기기 자료를 유지합니다. 다시 가져와주세요."
            }
            response.requests.orEmpty().forEach { check(ids.add(checkNotNull(it?.requestId))) }
            check(ids.size <= total)
            rows += page.rows
            deferred += page.deferred
            cursor = page.cursor
            if (response.hasMore == false) {
                check(ids.size == total && cursor <= watermark) { "견적 목록을 전부 받지 못했습니다." }
                database.withTransaction {
                    val readTimes = dao.requests().associate { it.requestId to it.readAt }
                    dao.clearRequests(); dao.clearDeferred()
                    dao.insertRequests(rows.map { it.copy(readAt = readTimes[it.requestId]) })
                    dao.insertDeferred(deferred)
                }
                return rows.size
            }
        }
        error("견적 목록을 전부 받지 못해 기존 기기 자료를 유지합니다.")
    }
    suspend fun latestRequest(id: String): WebQuoteRequestEntity? {
        refresh()
        return localRequests().firstOrNull { it.requestId == id }
    }
    suspend fun quoteAction(action: String, fields: Map<String, Any>): WebQuoteEnvelope {
        require(action in setOf("send_quote_sms", "quote_sms_status", "send_quote_email"))
        return gson.fromJson(gateway.request(action, fields), WebQuoteEnvelope::class.java)
    }
    suspend fun confirmQuote(id: String, draft: SellerQuoteDraft) = mutex.withLock {
        draft.validate(QuoteDelivery.COMPLETE)
        val pricing = mapOf<String, Any>(
            "items" to draft.rows.map {
                mapOf("species" to it.name, "specification" to it.spec,
                    "quantity" to it.quantity.toInt(), "unitPrice" to it.price.toInt())
            }, "isPrepaid" to draft.prepaid, "shippingFee" to draft.shipping(),
            "taxAmount" to if (draft.recipient.formalQuoteRequested) draft.options.taxAmount() else 0L)
        val response = gson.fromJson(gateway.request("save_quote_pricing",
            mapOf("requestId" to id, "quotePricing" to pricing)), WebQuoteEnvelope::class.java)
        check(response.updated == true && response.requestId == id && response.status == "견적확정") {
            "서버의 견적확정 응답을 확인하지 못했습니다. 같은 내용으로 다시 확인해주세요."
        }
        database.withTransaction { dao.confirmed(id, gson.toJson(pricing)) }
    }
    suspend fun close(id: String) = mutex.withLock {
        val response = gateway.request("close_request", mapOf("requestId" to id))
        check(response.get("closed")?.asBoolean == true && response.get("requestId")?.asString == id) {
            "견적요청 삭제 결과를 확인하지 못했습니다."
        }
        database.withTransaction { dao.deleteRequest(id); dao.deleteDeferred(id) }
    }
}

/** Reconstruct only server-acknowledged prices; never invent delivery evidence for another device. */
fun serverConfirmedDraft(request: WebQuoteRequestEntity): SellerQuoteDraft? {
    if (request.quotePricingJson.isBlank()) return null
    val raw = Gson().fromJson(request.quotePricingJson, com.google.gson.JsonObject::class.java)
    val rows = raw.getAsJsonArray("items").map {
        val item = it.asJsonObject
        SellerQuoteRow(item.get("species").asString, item.get("specification").asString,
            item.get("quantity").asInt.toString(), item.get("unitPrice").asInt.toString())
    }
    return SellerQuoteDraft(rows, request.customerPhone, QuoteRecipient.from(request),
        raw.get("isPrepaid").asBoolean, raw.get("shippingFee").asInt.toString(),
        QuoteIssueOptions(tax = raw.get("taxAmount").asLong.toString())).also {
        it.validate(QuoteDelivery.COMPLETE)
    }
}
