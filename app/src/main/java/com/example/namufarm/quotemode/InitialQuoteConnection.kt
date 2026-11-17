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

private class QuoteRebase : IllegalStateException()

data class InitialQuoteCache(val requests: List<WebQuoteRequestEntity>, val deferredCount: Int)

/**
 * Reads are local. A full snapshot replaces the cache only when complete (manual sync, once a day);
 * on launch/resume only the server's change counter is read and changed rows are merged.
 */
class InitialQuoteConnection(
    private val database: InitialQuoteDatabase,
    private val gateway: OperationsMasterGateway,
    private val syncPrefs: android.content.SharedPreferences,
    private val receiveFile: java.io.File
) {
    private val dao = database.inbox()
    private val mutex = Mutex()
    private val gson = Gson()
    init {
        if (receiveFile.exists() && System.currentTimeMillis() - receiveFile.lastModified() !in 0..3_600_000L)
            android.database.sqlite.SQLiteDatabase.deleteDatabase(receiveFile)
    }
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

    @Volatile var knownRequestIds: () -> Set<String> = { emptySet() }
    @Volatile var onReceiveProgress: (String) -> Unit = {}
    private val prefix = "quote_receive_v2:"
    private fun put(key: String, value: String) {
        check(syncPrefs.edit().putString(prefix + key, value).commit()) { "견적 동기화 기록을 저장하지 못했습니다." }
    }
    private fun cacheStamp(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var after = ""
        while (true) {
            val keys = dao.receiveKeys(after)
            if (keys.isEmpty()) break
            keys.forEach { digest.update((it.requestId + ":" + it.sequence + "\n").toByteArray(Charsets.UTF_8)) }
            after = keys.last().requestId
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun checkpoint(): com.google.gson.JsonObject? = syncPrefs.getString(prefix + "checkpoint", null)?.let {
        gson.fromJson(it, com.google.gson.JsonObject::class.java)
    }?.takeIf { it.get("stamp")?.asString == cacheStamp() }
    private fun saveCheckpoint(watermark: Long, full: Boolean) {
        val state = com.google.gson.JsonObject().apply {
            addProperty("cursor", watermark); addProperty("stamp", cacheStamp())
            addProperty("fullSyncAt", if (full) System.currentTimeMillis() else syncPrefs.getString(prefix + "checkpoint", null)?.let { gson.fromJson(it, com.google.gson.JsonObject::class.java).get("fullSyncAt")?.asLong } ?: 0L)
        }
        put("checkpoint", state.toString())
    }
    private fun replayDeletes() {
        val pending = syncPrefs.getStringSet(prefix + "deleted", emptySet()).orEmpty().toSet()
        if (pending.isNotEmpty()) {
            onServerDeleted(pending) // Must throw on a partial/failed local write.
            check(syncPrefs.edit().remove(prefix + "deleted").commit())
        }
    }
    private suspend fun checkedStates(extra: Set<String>, stage: QuoteReceiveCache): MutableSet<String> {
        val ids = knownRequestIds() + extra
        val sorted = ids.sorted()
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(sorted.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
        if (stage.meta.get("statesKey")?.asString != key) {
            stage.meta.addProperty("statesKey", key); stage.meta.addProperty("statesAfter", "")
            // A known deletion/expiry cannot be undone by a changing local candidate set during recovery.
            if (!stage.meta.has("deleted")) stage.meta.add("deleted", com.google.gson.JsonArray())
            if (!stage.meta.has("closed")) stage.meta.add("closed", com.google.gson.JsonArray())
            stage.save()
        }
        val deleted = stage.meta.getAsJsonArray("deleted").map { it.asString }.toMutableSet()
        val closed = stage.meta.getAsJsonArray("closed").map { it.asString }.toMutableSet()
        val started = android.os.SystemClock.elapsedRealtime()
        val remaining = sorted.filter { it > stage.meta.get("statesAfter").asString }.chunked(50)
        remaining.forEachIndexed { index, batch ->
            val response = gateway.request("quote_request_states", mapOf("quoteSyncVersion" to 2, "requestIds" to batch))
            check(response.get("quoteSyncVersion")?.asInt == 2 && response.get("schemaEpoch")?.asInt == 1)
            val states = checkNotNull(response.getAsJsonArray("states"))
            val seen = mutableSetOf<String>()
            check(states.size() == batch.size)
            states.forEach { item ->
                val row = item.asJsonObject
                val id = row.get("requestId").asString
                val sequence = row.get("sequence").asLong
                check(id in batch && seen.add(id) && sequence in 0..9_007_199_254_740_991L)
                when (row.get("state").asString) {
                    "admin_deleted" -> { check(sequence > 0); deleted += id; closed += id }
                    "expired" -> { check(sequence > 0); closed += id }
                    "active" -> check(sequence > 0)
                    "unknown" -> check(sequence == 0L)
                    else -> error("견적 종료 상태를 확인하지 못했습니다.")
                }
            }
            stage.meta.addProperty("statesAfter", batch.last())
            stage.meta.add("deleted", gson.toJsonTree(deleted)); stage.meta.add("closed", gson.toJsonTree(closed)); stage.save()
            onReceiveProgress("견적 종료 상태 확인 중… ${sorted.indexOf(batch.last()) + 1}/${sorted.size}")
            if (index < remaining.lastIndex && (index >= 19 || android.os.SystemClock.elapsedRealtime() - started >= 60_000))
                throw QuoteReceivePending("견적 종료 상태 확인 중 · 동기화를 다시 누르면 이어받습니다. 기존 자료를 유지합니다.")
        }
        return deleted
    }
    suspend fun refresh(): Int = mutex.withLock { receive(full = true) }
    private suspend fun receive(full: Boolean): Int = try { receiveBatch(full) }
    catch (_: QuoteRebase) { receive(true) }
    private suspend fun receiveBatch(full: Boolean): Int {
        replayDeletes()
        if (!full && syncPrefs.getBoolean(prefix + "rebase", false)) return receive(true)
        val state = checkpoint()
        if (!full && state == null) return receive(true)
        val base = if (full) 0L else checkNotNull(state).get("cursor").asLong
        val scope = (if (full) "full:" else "delta:") + base + ":" + cacheStamp()
        QuoteReceiveCache(receiveFile, scope).use { stage ->
            val started = android.os.SystemClock.elapsedRealtime()
            var pages = 0
            try {
                while (stage.meta.get("complete")?.asBoolean != true) {
                    val after = stage.meta.get("cursor").asLong.coerceAtLeast(base)
                    val fields = mutableMapOf<String, Any>("quoteSyncVersion" to 2, "afterSequence" to after, "fromSequence" to base,
                        "snapshot" to full)
                    stage.meta.get("snapshotId")?.let { fields["snapshotId"] = it.asString }
                    val response = gson.fromJson(gateway.request(if (full) "snapshot_requests" else "list_requests", fields), WebQuoteEnvelope::class.java)
                    check(response.quoteSyncVersion == 2 && response.schemaEpoch == 1 && response.scope == (if (full) "active" else "changes")) {
                        "견적 동기화 v2 서버 배포가 필요합니다. 기존 자료를 유지합니다."
                    }
                    if (response.requiresSnapshot == true) {
                        check(!full)
                        check(syncPrefs.edit().putBoolean(prefix + "rebase", true).commit())
                        stage.clear(); throw QuoteRebase()
                    }
                    check(response.requiresSnapshot == false)
                    val token = checkNotNull(response.snapshotId)
                    val total = checkNotNull(response.total)
                    val watermark = checkNotNull(response.watermark)
                    check(Regex("[a-f0-9]{64}").matches(token) && total >= 0 && watermark in base..9_007_199_254_740_991L)
                    if (stage.meta.get("snapshotId") == null) {
                        stage.meta.addProperty("snapshotId", token); stage.meta.addProperty("total", total)
                        stage.meta.addProperty("watermark", watermark)
                    }
                    check(stage.meta.get("snapshotId").asString == token && stage.meta.get("total").asInt == total &&
                        stage.meta.get("watermark").asLong == watermark) { "견적 목록이 변경되었습니다. 다시 가져와주세요." }
                    response.requests.orEmpty().forEach { remote ->
                        checkNotNull(remote)
                        val created = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false
                        }.parse(checkNotNull(remote.createdAt))?.time
                        check(created != null && remote.expiresAt == created + 30L * 86_400_000L)
                    }
                    val page = checkedPage(response, after)
                    check(page.cursor <= watermark)
                    if (full) check(response.requests.orEmpty().none { it?.expired == true })
                    stage.append(response.requests.orEmpty(), page.cursor)
                    check(stage.count() <= total)
                    if (response.hasMore == false) {
                        check(stage.count() == total)
                        stage.meta.addProperty("complete", true); stage.save()
                    }
                    onReceiveProgress("견적 ${stage.count()}/$total 건 수신 중…")
                    pages++
                    if (stage.meta.get("complete")?.asBoolean != true && (pages >= 20 || android.os.SystemClock.elapsedRealtime() - started >= 60_000))
                        throw QuoteReceivePending("견적 ${stage.count()}/$total 건 수신 · 동기화를 다시 누르면 이어받습니다. 기존 자료를 유지합니다.")
                }
                // Known local UUIDs include confirmed/sent/pending history, not just the active inbox.
                val localIds = mutableSetOf<String>()
                var idAfter = ""
                while (true) {
                    val keys = dao.receiveKeys(idAfter); if (keys.isEmpty()) break
                    keys.forEach { localIds += it.requestId }; idAfter = keys.last().requestId
                }
                val deleted = checkedStates(localIds, stage)
                val closed = stage.meta.getAsJsonArray("closed").map { it.asString }.toSet()
                var cursor = 0L
                while (true) {
                    val remote = stage.page(cursor); if (remote.isEmpty()) break
                    remote.filterNotNull().filter { it.deleted == true }.forEach { deleted += checkNotNull(it.requestId) }
                    cursor = checkNotNull(remote.last()?.sequence)
                }
                if (deleted.isNotEmpty()) {
                    check(syncPrefs.edit().putStringSet(prefix + "deleted", deleted).commit())
                    replayDeletes() // Deletion evidence is durable before any cache/cursor change.
                }
                var inserted = 0
                database.withTransaction {
                    if (full) {
                        var after = ""
                        while (true) {
                            val keys = dao.receiveKeys(after); if (keys.isEmpty()) break
                            keys.filter { !stage.contains(it.requestId) || it.requestId in deleted }.forEach { dao.deleteRequest(it.requestId) }
                            after = keys.last().requestId
                        }
                        dao.clearDeferred()
                    }
                    cursor = 0L
                    while (true) {
                        val remote = stage.page(cursor); if (remote.isEmpty()) break
                        val page = checkedPage(WebQuoteEnvelope(ok = true, requests = remote, hasMore = false), cursor)
                        page.expiredIds.forEach { dao.deleteRequest(it); dao.deleteDeferred(it) }
                        val overdue = remote.filterNotNull().filter { (it.expiresAt ?: 0L) <= System.currentTimeMillis() }.mapNotNull { it.requestId }.toSet()
                        overdue.forEach { dao.deleteRequest(it); dao.deleteDeferred(it) }
                        val fresh = page.rows.filter { it.requestId !in deleted && it.requestId !in closed && it.requestId !in overdue }.mapNotNull { row ->
                            val old = dao.request(row.requestId)
                            if (!full && old != null && old.sequence >= row.sequence) null else row.copy(readAt = old?.readAt)
                        }
                        fresh.forEach { dao.deleteDeferred(it.requestId) }
                        page.deferred.forEach { dao.deleteRequest(it.requestId) }
                        dao.insertRequests(fresh); dao.insertDeferred(page.deferred.filter { it.requestId !in deleted && it.requestId !in closed && it.requestId !in overdue })
                        inserted += fresh.size; cursor = page.cursor
                    }
                    (deleted + closed).forEach { dao.deleteRequest(it); dao.deleteDeferred(it) }
                }
                saveCheckpoint(stage.meta.get("watermark").asLong, full)
                if (full) check(syncPrefs.edit().remove(prefix + "rebase").commit())
                syncedHead = stage.meta.get("watermark").asLong
                stage.clear()
                return inserted
            } catch (e: MasterServerException) {
                if (e.code in setOf("BUSY", "CONFLICT")) stage.clear()
                throw e
            } catch (e: QuoteRebase) { throw e }
            catch (e: QuoteReceivePending) { throw e }
            catch (e: CancellationException) { throw e }
            catch (e: java.io.IOException) { throw e } // Retain verified pages across a transport failure.
            catch (e: retrofit2.HttpException) { throw e }
            catch (e: Exception) { stage.clear(); throw e }
        }
    }
    private fun checkedPage(response: WebQuoteEnvelope, after: Long) = WebQuoteValidator.checkedPage(response, after) { remote ->
        if (remote.encryptedRequest != null) throw LegacyRequestKeyUnavailable()
        remote
    }
    private fun fullSyncDue(): Boolean {
        val last = checkpoint()?.get("fullSyncAt")?.asLong ?: 0L
        return System.currentTimeMillis() - last !in 0..86_400_000L
    }
    suspend fun syncIfChanged(): Int? {
        val head = try { gateway.request("sync_head").get("requestSequence")?.asLong ?: return null }
        catch (e: MasterServerException) { if (e.code == "INVALID_ACTION") return null; throw e }
        return mutex.withLock {
            replayDeletes()
            if (fullSyncDue()) receive(true)
            else if (head == syncedHead || head <= (checkpoint()?.get("cursor")?.asLong ?: -1L)) 0
            else receive(false)
        }
    }
    suspend fun latestRequest(id: String): WebQuoteRequestEntity? = mutex.withLock {
        receive(fullSyncDue()); dao.request(id)
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
