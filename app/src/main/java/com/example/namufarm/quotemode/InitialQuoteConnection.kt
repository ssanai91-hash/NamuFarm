package com.example.namufarm.quotemode

import androidx.room.withTransaction
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.data.DeferredWebRequestEntity
import com.example.namufarm.data.remote.*
import com.example.namufarm.quotation.*
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InitialQuoteCache(val requests: List<WebQuoteRequestEntity>, val deferredCount: Int)

/**
 * Reads are local. A full snapshot replaces the cache only when complete (manual sync, once a day);
 * on launch/resume only the server's change counter is read and changed rows are merged.
 */
class InitialQuoteConnection(
    private val database: InitialQuoteDatabase,
    private val gateway: OperationsMasterGateway,
    private val syncPrefs: android.content.SharedPreferences? = null
) {
    private val dao = database.inbox()
    private val mutex = Mutex()
    private val gson = Gson()
    /** Last request counter whose changed rows were merged in this process. */
    @Volatile private var syncedHead = -1L
    /** Shared confirmed quote API. Unknown actions on an older deployment keep the legacy path. */
    val shared: SharedQuoteGateway = object : SharedQuoteGateway {
        override suspend fun call(action: String, fields: Map<String, Any>): com.google.gson.JsonObject = try {
            gateway.request(action, fields)
        } catch (e: MasterServerException) {
            // NOT_READY: the server's shared quotes are active only in the annual two-tab storage.
            if (e.code == "INVALID_ACTION" || (e.code == "NOT_READY" && action == "get_confirmed_quote"))
                throw SharedQuoteUnavailable()
            throw SharedQuoteException(e.code)
        }
    }
    /** Called after a complete snapshot with seller-deleted request IDs (drop local confirmed records). */
    @Volatile var onServerDeleted: (Set<String>) -> Unit = {}
    fun localRequests() = dao.requests()
    fun deferredCount() = dao.deferredCount()
    fun observeCache() = combine(dao.observeRequests(), dao.observeDeferredCount()) { requests, deferred ->
        InitialQuoteCache(requests, deferred)
    }
    fun read(id: String) = dao.read(id, System.currentTimeMillis())

    suspend fun refresh(): Int = mutex.withLock {
        repeat(3) { attempt ->
            try { return@withLock downloadSnapshot().also { fullSyncDone() } }
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
        val deletedIds = mutableSetOf<String>()
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
            deletedIds += page.deletedIds
            cursor = page.cursor
            if (response.hasMore == false) {
                check(ids.size == total && cursor <= watermark) { "견적 목록을 전부 받지 못했습니다." }
                database.withTransaction {
                    val readTimes = dao.requests().associate { it.requestId to it.readAt }
                    dao.clearRequests(); dao.clearDeferred()
                    dao.insertRequests(rows.map { it.copy(readAt = readTimes[it.requestId]) })
                    dao.insertDeferred(deferred)
                }
                if (deletedIds.isNotEmpty()) onServerDeleted(deletedIds)
                return rows.size
            }
        }
        error("견적 목록을 전부 받지 못해 기존 기기 자료를 유지합니다.")
    }
    private fun fullSyncDone() {
        syncPrefs?.edit()?.putLong("fullSyncAt", System.currentTimeMillis())?.apply()
    }
    private fun fullSyncDue(): Boolean {
        val last = syncPrefs?.getLong("fullSyncAt", 0L) ?: 0L
        val age = System.currentTimeMillis() - last
        return age < 0 || age > 86_400_000L
    }
    /**
     * Launch/resume check. Reads only the change counter (no sheet scan); downloads changed rows when it moved.
     * Returns null when the server has no counter (older deployment): nothing is downloaded automatically.
     */
    suspend fun syncIfChanged(): Int? {
        val head = try {
            gateway.request("sync_head").get("requestSequence")?.asLong ?: return null
        } catch (e: MasterServerException) {
            if (e.code == "INVALID_ACTION") return null
            throw e
        }
        if (fullSyncDue()) return refresh().also { syncedHead = head }
        // The counter can run ahead of a failed server write: never store it as a cursor.
        if (head == syncedHead || head <= (dao.maxSequence() ?: 0L)) return 0
        return mutex.withLock { downloadChanges() }.also { syncedHead = head }
    }
    /** Rows changed after this phone's newest row, merged by request UUID in one transaction. */
    private suspend fun downloadChanges(): Int {
        var cursor = dao.maxSequence() ?: 0L
        val rows = mutableListOf<WebQuoteRequestEntity>()
        val deferred = mutableListOf<DeferredWebRequestEntity>()
        val expiredIds = mutableSetOf<String>()
        val deletedIds = mutableSetOf<String>()
        repeat(100) {
            val response = gson.fromJson(gateway.request("list_requests",
                mapOf("afterSequence" to cursor, "snapshot" to false)), WebQuoteEnvelope::class.java)
            val page = WebQuoteValidator.checkedPage(response, cursor) { remote ->
                if (remote.encryptedRequest != null) throw LegacyRequestKeyUnavailable()
                remote
            }
            rows += page.rows
            deferred += page.deferred
            expiredIds += page.expiredIds
            deletedIds += page.deletedIds
            cursor = page.cursor
            if (response.hasMore == false) {
                database.withTransaction {
                    val existing = dao.requests().associateBy { it.requestId }
                    expiredIds.forEach { dao.deleteRequest(it); dao.deleteDeferred(it) }
                    // An older copy never replaces a newer one; the read mark is kept.
                    val fresh = rows.filter { (existing[it.requestId]?.sequence ?: -1L) < it.sequence }
                    fresh.forEach { dao.deleteRequest(it.requestId) }
                    dao.insertRequests(fresh.map { it.copy(readAt = existing[it.requestId]?.readAt) })
                    dao.insertDeferred(deferred)
                }
                if (deletedIds.isNotEmpty()) onServerDeleted(deletedIds)
                return rows.size
            }
        }
        error("바뀐 견적이 너무 많습니다. 동기화 버튼으로 전체를 다시 가져와주세요.")
    }
    /** Fresh server state for a write decision: changed rows only (a full snapshot when the day's check is due). */
    suspend fun latestRequest(id: String): WebQuoteRequestEntity? {
        if (fullSyncDue()) refresh() else mutex.withLock { downloadChanges() }
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
    /** Status-only acknowledgement after seller attestation; confirmed pricing stays untouched. */
    suspend fun markConfirmedQuoteDelivered(id: String) = mutex.withLock {
        val response = gson.fromJson(gateway.request("update_request_status",
            mapOf("requestId" to id, "status" to "견적발송완료")), WebQuoteEnvelope::class.java)
        check(response.updated == true && response.requestId == id && response.status == "견적발송완료") {
            "서버의 발송완료 응답을 확인하지 못했습니다. 다시 보내지 말고 결과를 확인해주세요."
        }
    }
    suspend fun close(id: String, admin: Boolean = false) = mutex.withLock {
        val response = gateway.request("close_request",
            if (admin) mapOf("requestId" to id, "reason" to "admin_delete") else mapOf("requestId" to id))
        check(response.get("closed")?.asBoolean == true && response.get("requestId")?.asString == id) {
            "견적요청 삭제 결과를 확인하지 못했습니다."
        }
        database.withTransaction { dao.deleteRequest(id); dao.deleteDeferred(id) }
    }
}

/** Reconstruct only server-acknowledged prices; never invent delivery evidence for another device. */
fun serverConfirmedDraft(request: WebQuoteRequestEntity, base: SellerQuoteDraft? = null): SellerQuoteDraft? {
    if (request.quotePricingJson.isBlank()) return null
    val raw = Gson().fromJson(request.quotePricingJson, com.google.gson.JsonObject::class.java)
    // The shared version (if any) carries the seller's details; pricing readers ignore it.
    val details = raw.remove("quoteVersion")?.takeIf { it.isJsonObject }?.asJsonObject
        ?.get("quoteDetails")?.takeIf { it.isJsonObject }?.asJsonObject ?: com.google.gson.JsonObject()
    // Pricing is authoritative even if an older client omitted the formal-request flag.
    return draftFromShared(request, raw, details, base).also {
        it.validate(QuoteDelivery.COMPLETE)
    }
}
