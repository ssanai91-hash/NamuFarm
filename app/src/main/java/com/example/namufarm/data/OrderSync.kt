package com.example.namufarm.data

import android.content.SharedPreferences
import androidx.room.withTransaction
import com.example.namufarm.data.remote.MasterServerException
import com.example.namufarm.data.remote.OperationsMasterGateway
import com.example.namufarm.quotation.QuoteWorkflowRecord
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class OrderSyncOutcome(val success: Boolean, val message: String)
data class OrderServerChoice(val number: String, val phone: String, val amount: Long, val status: String,
    val date: String, val summary: String)
data class OrderLinkReview(val localNumber: String, val choices: List<OrderServerChoice>, val allowNew: Boolean,
    val message: String = "서버 주문을 사용하면 이 기기의 수정본과 대기는 보관하고 서버 내용으로 연결합니다.")

/** Durable protocol state is part of the existing NamuFarmPrefs backup, not a Room migration. */
class OrderSync(private val database: AppDatabase, private val gateway: OperationsMasterGateway,
    private val prefs: SharedPreferences, private val serviceInfo: suspend () -> JsonObject,
    private val webRecords: () -> List<QuoteWorkflowRecord> = { emptyList() },
    private val formalDocument: (QuoteWorkflowRecord) -> String? = { null }) {
    private val dao = database.managementDao()
    private val mutex = Mutex()
    private val json = OrderProtocol.gson
    private val namespace = "order_sync_v1."
    private fun uid() = UUID.randomUUID().toString()
    private fun key(kind: String, id: String) = namespace + kind + "." + OrderProtocol.hash(id)
    private fun read(key: String): JsonObject? = prefs.getString(key, null)?.let {
        val value = json.fromJson(it, JsonObject::class.java)
        check(value != null && OrderProtocol.number(value, "format", 1) == 1L) { "주문 연결 기록을 읽지 못했습니다. 초기화하지 않고 보관합니다." }
        value
    }
    private fun write(key: String, value: JsonObject) {
        val saved = value.deepCopy().apply { addProperty("format", 1) }
        check(prefs.edit().putString(key, json.toJson(saved)).commit()) { "주문 연결 기록을 저장하지 못했습니다. 전송 대기를 유지합니다." }
    }
    private fun links() = prefs.all.keys.filter { it.startsWith(namespace + "link.") }.map { checkNotNull(read(it)) }
    private fun link(number: String) = read(key("link", number))
    private fun saveLink(value: JsonObject) = write(key("link", OrderProtocol.text(value, "localNumber")), value)
    private fun localNumber(work: JsonObject) = OrderProtocol.text(work, "localNumber")
    private fun entity(value: JsonObject, name: String) = json.fromJson(OrderProtocol.obj(value, name), OrderEntity::class.java)
        ?: error("로컬 주문 내용을 읽지 못했습니다.")
    private fun pending(number: String) = dao.getAllPendingSync().filter { row ->
        if (row.syncType == PendingSyncType.ORDER_V2) localNumber(work(row)) == number else legacyNumber(row) == number
    }
    private fun work(row: PendingSyncEntity): JsonObject {
        val value = json.fromJson(row.payloadJson, JsonObject::class.java)
        check(OrderProtocol.number(value, "format", 1) == 2L && OrderProtocol.id(OrderProtocol.text(value, "eventId"))) {
            "주문 전송 대기 형식을 확인해주세요. 기존 기록을 유지합니다."
        }
        return value
    }
    private fun legacyNumber(row: PendingSyncEntity): String? = when (row.syncType) {
        PendingSyncType.ORDER, PendingSyncType.CANCEL_ORDER, PendingSyncType.DELIVERY, "update_order_status" -> {
            val value = json.fromJson(row.payloadJson, JsonObject::class.java)
            if (row.syncType == PendingSyncType.ORDER) value?.getAsJsonObject("order")?.get("orderNumber")?.asString
            else value?.get("orderNumber")?.asString
        }
        else -> null
    }
    fun displayNumber(number: String): String = runCatching { link(number)?.get("serverNumber")?.asString ?: number }.getOrDefault(number)
    fun visible(orders: List<OrderEntity>): List<OrderEntity> = orders.filter { order ->
        val state = link(order.orderNumber)
        state?.get("hidden")?.asBoolean != true && state?.get("deleted")?.asBoolean != true
    }
    fun unresolvedNumbers(): List<String> = dao.getAllPendingSync().mapNotNull { row ->
        if (row.syncType == PendingSyncType.ORDER_V2) null else legacyNumber(row)
    }.distinct()
    fun feedback(orders: List<OrderEntity>): Map<String, String> {
        val queued = dao.getAllPendingSync().mapNotNull { row ->
            val number = if (row.syncType == PendingSyncType.ORDER_V2) localNumber(work(row)) else legacyNumber(row)
            number?.let { it to row }
        }.groupBy({ it.first }, { it.second })
        return orders.associate { order ->
            val rows = queued[order.orderNumber].orEmpty()
            val state = link(order.orderNumber)
            order.orderNumber to when {
                rows.any { it.syncType != PendingSyncType.ORDER_V2 } -> "기존 주문 연결 필요"
                rows.any { it.lastError.isNotBlank() } -> "서버 확인 필요 · 대기 보관"
                rows.isNotEmpty() -> "서버 반영 대기"
                state?.getAsJsonObject("last") != null -> "서버 반영 확인"
                else -> "기기에 보관"
            }
        }
    }
    /** Never clear a submitted, unresolved sale merely because a timeout was shown. */
    fun canClear(rows: List<PendingSyncEntity>): Boolean = links().none { it.get("importPending")?.asBoolean == true } && rows.none {
        it.syncType == PendingSyncType.ORDER_V2 && work(it).has("body")
    }
    /** Runs inside the backup-and-clear Room transaction, before DELETE. Never discards an ACK. */
    fun clearUnsubmittedLinks(rows: List<PendingSyncEntity>) {
        check(canClear(rows))
        val remove = rows.filter { it.syncType == PendingSyncType.ORDER_V2 }.map { localNumber(work(it)) }.distinct()
            .filter { link(it)?.getAsJsonObject("last") == null }
        val edit = prefs.edit(); remove.forEach { edit.remove(key("link", it)) }
        check(edit.commit()) { "미전송 연결 기록을 정리하지 못해 대기를 보존합니다." }
    }
    suspend fun <T> localChange(block: suspend () -> T): T = mutex.withLock {
        check(links().none { it.get("importPending")?.asBoolean == true }) { "중단된 주문 연결을 매매 동기화에서 먼저 복구해주세요." }
        database.withTransaction { block() }
    }

    /** Called in the same Room transaction as the new order. No networking. */
    fun enqueueNew(order: OrderEntity, at: Long, web: QuoteWorkflowRecord? = null) {
        val seed = OrderProtocol.seed(order, at, web)
        web?.let(formalDocument)?.let { saved ->
            check(saved.length <= 30000) { "공식견적서 보관 크기가 서버 기준을 넘습니다. 원본을 보관합니다." }
            val document = com.example.namufarm.quotation.QuoteDocument.read(saved)
            val meta = OrderProtocol.obj(seed, "metadata")
            val personal = OrderProtocol.obj(seed, "personalData")
            val original = OrderProtocol.obj(personal, "order")
            check(document.sourceQuoteNumber == OrderProtocol.text(original, "sourceQuoteNumber") &&
                document.customerPhone == OrderProtocol.text(original, "customerPhone") &&
                document.supplyAmount == OrderProtocol.number(meta, "totalPrice") && document.taxAmount == OrderProtocol.number(meta, "taxAmount")) {
                "보관된 공식견적서와 입금확인 내용이 다릅니다. 원본을 보관하고 주문 반영을 멈춥니다."
            }
            personal.addProperty("quoteDocumentJson", saved) // Reuse the issued snapshot; never issue another number here.
        }
        OrderProtocol.validateSale(seed)
        val value = baseWork(order, "new", at).apply {
            add("seed", seed)
            if (web != null) addProperty("serverNumber", order.orderNumber)
        }
        dao.insertPendingSync(PendingSyncEntity(syncType = PendingSyncType.ORDER_V2, payloadJson = json.toJson(value), createdAt = at))
    }
    fun checkWebCreation(number: String) {
        check(link(number)?.get("deleted")?.asBoolean != true) { "삭제 확인된 웹 주문을 다시 만들지 않습니다. 기존 입금 기록은 보관합니다." }
    }
    /** Only an explicit retry of this very web payment may replace its old local-only packets. */
    fun ensureWebUpload(order: OrderEntity, at: Long, record: QuoteWorkflowRecord) {
        val state = link(order.orderNumber)
        if (state?.getAsJsonObject("last") != null) return
        val rows = pending(order.orderNumber)
        if (rows.any { it.syncType == PendingSyncType.ORDER_V2 && OrderProtocol.text(work(it), "kind") == "new" }) return
        check(rows.none { it.syncType == PendingSyncType.ORDER_V2 }) { "기존 주문 수정 대기를 먼저 확인해주세요." }
        if (rows.isNotEmpty()) {
            archive(order.orderNumber)
            rows.forEach { dao.deletePendingSync(checkNotNull(it.syncId)) }
        }
        enqueueNew(order, at, record)
    }
    fun enqueueChange(before: OrderEntity, after: OrderEntity, kind: String, at: Long) {
        check(kind in setOf("status", "delivery", "delete"))
        check(pending(before.orderNumber).none { it.syncType == PendingSyncType.ORDER_V2 && OrderProtocol.text(work(it), "kind") == "delete" }) {
            "삭제 요청이 대기 중입니다. 같은 삭제 작업의 결과를 먼저 확인해주세요."
        }
        val value = baseWork(after, kind, at).apply {
            add("before", json.toJsonTree(before))
            link(before.orderNumber)?.getAsJsonObject("last")?.get("fingerprint")?.let { add("baseFingerprint", it.deepCopy()) }
        }
        dao.insertPendingSync(PendingSyncEntity(syncType = PendingSyncType.ORDER_V2, payloadJson = json.toJson(value), createdAt = at))
    }
    private fun baseWork(order: OrderEntity, kind: String, at: Long) = JsonObject().apply {
        addProperty("format", 2); addProperty("localNumber", order.orderNumber); addProperty("kind", kind)
        addProperty("eventId", uid()); addProperty("at", at); add("order", json.toJsonTree(order))
    }
    private suspend fun ready() {
        val info = serviceInfo()
        check(info.get("ok")?.asBoolean == true && OrderProtocol.number(info, "version") == 5L &&
            OrderProtocol.text(info, "service") == "namufarm-quote-requests" &&
            OrderProtocol.text(info, "customerDataStorage") == "plain" &&
            info.get("customerDataReady")?.asBoolean == true && OrderProtocol.text(info, "ledgerStorage") == "annual-v1" &&
            info.get("soldNumberReservation")?.asInt == 1) {
            "기존 Apps Script 서버의 주문 API 배포를 확인해주세요. 로컬 자료와 대기를 유지합니다."
        }
    }
    suspend fun reserveWebNumber(requestId: String): String = mutex.withLock {
        check(OrderProtocol.id(requestId)); ready(); reserve("web:$requestId")
    }
    private suspend fun reserve(owner: String): String {
        var state = read(key("reservation", owner))
        state?.get("number")?.asString?.let { return it }
        val day = OrderProtocol.day(System.currentTimeMillis())
        // Only an unissued reservation can be abandoned at midnight; no sale/claim exists yet.
        if (state == null || OrderProtocol.text(state, "day") != day) {
            state = JsonObject().apply { addProperty("operationId", uid()); addProperty("day", day) }
            write(key("reservation", owner), state)
        }
        val operation = OrderProtocol.text(state, "operationId")
        val response = gateway.request("reserve_sold_numbers", mapOf("operationId" to operation, "day" to day, "count" to 1))
        val first = OrderProtocol.number(response, "first", 1)
        check(response.get("reserved")?.asBoolean == true && OrderProtocol.text(response, "operationId") == operation &&
            OrderProtocol.text(response, "day") == day && first == OrderProtocol.number(response, "last", 1)) {
            "판매번호 예약 응답을 확인하지 못했습니다. 새 입금확인을 하지 않고 같은 예약을 유지합니다."
        }
        val number = "SOLD-$day-${first.toString().padStart(4, '0')}"
        state.addProperty("number", number); write(key("reservation", owner), state)
        return number
    }
    private fun fields(body: JsonObject): Map<String, Any> = body.entrySet().filter { it.key != "action" }.associate { (name, value) ->
        name to when {
            value.isJsonObject || value.isJsonArray -> value
            value.asJsonPrimitive.isString -> value.asString
            value.asJsonPrimitive.isBoolean -> value.asBoolean
            else -> value.asLong
        }
    }
    private fun lastFor(serverNumber: String): JsonObject? {
        val remote = read(key("remote", serverNumber))?.getAsJsonObject("row")
        if (remote?.get("deleted")?.asBoolean == true) return remote
        val acknowledged = links().singleOrNull { it.get("serverNumber")?.asString == serverNumber }?.getAsJsonObject("last")
        return if (acknowledged != null && (remote == null ||
                OrderProtocol.number(OrderProtocol.obj(acknowledged, "metadata"), "revision", 1) >
                OrderProtocol.number(OrderProtocol.obj(remote, "metadata"), "revision", 1))) acknowledged else remote
    }
    private fun cache(row: JsonObject) {
        val number = if (row.get("deleted")?.asBoolean == true) OrderProtocol.text(row, "orderNumber")
            else OrderProtocol.text(OrderProtocol.obj(row, "metadata"), "orderNumber")
        write(key("remote", number), JsonObject().apply { add("row", row.deepCopy()) })
    }
    private fun serverNumber(row: JsonObject) = OrderProtocol.text(OrderProtocol.obj(row, "metadata"), "orderNumber")
    private fun newLink(localNumber: String, serverNumber: String) = JsonObject().apply {
        addProperty("localNumber", localNumber); addProperty("serverNumber", serverNumber)
    }
    private fun accept(state: JsonObject, row: JsonObject) {
        val old = state.getAsJsonObject("last")
        if (old == null || OrderProtocol.number(OrderProtocol.obj(old, "metadata"), "revision", 1) <=
            OrderProtocol.number(OrderProtocol.obj(row, "metadata"), "revision", 1)) {
            state.add("last", row.deepCopy()); state.addProperty("hidden", row.get("hidden")?.asBoolean == true)
            state.addProperty("deleted", false); saveLink(state)
        }
    }
    private fun ownsAcknowledgement(number: String, row: JsonObject): Boolean = pending(number).any { queued ->
        if (queued.syncType != PendingSyncType.ORDER_V2) false else work(queued).getAsJsonObject("body")?.let {
            it.get("eventId") == row.get("eventId") && it.get("fingerprint") == row.get("fingerprint")
        } == true
    }
    /** Page validation happens before any local mutation. Partial imports never delete all orders. */
    private suspend fun pull(full: Boolean = false): Pair<Int, String?> {
        if (full && !prefs.getBoolean(namespace + "fullScan", false)) {
            check(prefs.edit().putLong(namespace + "cursor", 0).putBoolean(namespace + "fullScan", true).commit())
        }
        var cursor = prefs.getLong(namespace + "cursor", 0)
        var received = 0
        repeat(30) {
            val response = gateway.request("list_sales", mapOf("afterSequence" to cursor))
            val rows = response.getAsJsonArray("sales") ?: error("주문 목록이 누락되었습니다.")
            val more = OrderProtocol.bool(response, "hasMore")
            check(rows.size() <= 100 && (rows.size() > 0 || !more))
            var next = cursor
            rows.forEach { item -> val row = item.asJsonObject
                val sequence = OrderProtocol.number(row, "sequence", 1); check(sequence > next); next = sequence
                if (row.get("expired")?.asBoolean == true) {
                    if (row.get("deleted")?.asBoolean == true) {
                        OrderProtocol.text(row, "orderNumber"); OrderProtocol.number(row, "revision", 1)
                    }
                } else OrderProtocol.validateRow(row)
            }
            var blocked: String? = null
            for (item in rows) {
                val row = item.asJsonObject
                if (row.get("expired")?.asBoolean == true) {
                    if (row.get("deleted")?.asBoolean == true) {
                        cache(row)
                        val state = links().singleOrNull { it.get("serverNumber")?.asString == OrderProtocol.text(row, "orderNumber") }
                        if (state != null) {
                            val local = OrderProtocol.text(state, "localNumber")
                            if (pending(local).isNotEmpty()) blocked = "서버에서 삭제된 주문 $local 에 기기 수정이 남아 있습니다. 주문 연결을 확인해주세요."
                            else {
                                state.addProperty("deleted", true); saveLink(state)
                                database.withTransaction { dao.deleteOrderByNumber(local) }
                            }
                        }
                    }
                    continue // Archived rows without identity cannot delete a guessed local order.
                }
                cache(row)
                val number = serverNumber(row)
                val matches = links().filter { it.get("serverNumber")?.asString == number }
                check(matches.size <= 1) { "서버 주문번호 연결이 중복돼 있습니다. 기존 자료를 유지합니다." }
                var state = matches.singleOrNull()
                if (state == null) {
                    // Recover a locally journalled web sale, including an ACK lost before link persistence.
                    val own = dao.getAllPendingSync().filter { it.syncType == PendingSyncType.ORDER_V2 }.map { work(it) }
                        .filter { it.get("serverNumber")?.asString == number }
                    val local = own.map { localNumber(it) }.distinct().singleOrNull()
                    if (local != null) state = newLink(local, number)
                    else if (dao.findOrdersByNumber(number).isNotEmpty()) {
                        blocked = "기존 주문 $number 의 출처를 먼저 확인해주세요. 같은 번호만으로 합치지 않았습니다."
                        continue
                    } else state = newLink(number, number).apply { addProperty("imported", true) }
                    // Import marker is durable BEFORE Room; a crash can safely replay this page.
                    saveLink(state)
                }
                val local = OrderProtocol.text(state, "localNumber")
                val orders = dao.findOrdersByNumber(local)
                check(orders.size <= 1) { "기기 주문번호 $local 가 중복돼 있어 덮어쓰지 않았습니다." }
                val dirty = pending(local)
                val acknowledged = state.getAsJsonObject("last")
                check(acknowledged == null || OrderProtocol.number(OrderProtocol.obj(acknowledged, "metadata"), "revision", 1) <=
                    OrderProtocol.number(OrderProtocol.obj(row, "metadata"), "revision", 1)) {
                    "서버 주문 버전이 기기 확인 기록보다 이전입니다. 기존 자료를 덮어쓰지 않았습니다."
                }
                if (dirty.isNotEmpty()) {
                    val old = state.getAsJsonObject("last")
                    if (ownsAcknowledgement(local, row)) accept(state, row)
                    else if (old == null || old.get("fingerprint") != row.get("fingerprint"))
                        blocked = "주문 $local 에 서버 변경과 기기 수정이 함께 있습니다. 주문 연결을 확인해주세요."
                    continue
                }
                database.withTransaction {
                    val current = dao.findOrdersByNumber(local).singleOrNull()
                    val entity = OrderProtocol.local(row, local, current?.orderId)
                    if (current == null) dao.insertOrder(entity) else dao.updateOrder(entity)
                }
                accept(state, row); received++
            }
            if (blocked != null) return received to blocked
            check(prefs.edit().putLong(namespace + "cursor", next).commit()) { "주문 커서를 저장하지 못했습니다. 다음에 같은 페이지를 다시 확인합니다." }
            cursor = next
            if (!more) {
                check(prefs.edit().putBoolean(namespace + "fullScan", false).commit())
                return received to null
            }
        }
        return received to "주문을 나누어 가져왔습니다. 다시 동기화하면 저장된 순번부터 이어집니다."
    }
    suspend fun sync(): OrderSyncOutcome = mutex.withLock {
        try {
            ready()
            recoverImports()
            var pullResult = pull(full = true)
            var received = pullResult.first
            var sent = 0; val errors = mutableListOf<String>()
            val blockedNumbers = mutableSetOf<String>()
            for (row in dao.getAllPendingSync()) {
                if (row.syncType != PendingSyncType.ORDER_V2) continue
                val number = localNumber(work(row))
                if (number in blockedNumbers) continue
                try { push(row); sent++ } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val message = orderError(e)
                    row.syncId?.let { dao.markPendingSyncFailed(it, message) }
                    blockedNumbers += number; errors += "$number · $message"
                }
            }
            // Re-read after uploads. A bad initial page/network response throws before any push.
            pullResult = pull()
            received += pullResult.first
            val legacy = unresolvedNumbers().size
            val count = dao.getAllPendingSync().count { it.syncType == PendingSyncType.ORDER_V2 }
            val messages = errors.take(3) + listOfNotNull(pullResult.second) +
                if (legacy > 0) listOf("기존 주문 $legacy 건은 ‘주문 연결 확인’이 필요합니다.") else emptyList()
            OrderSyncOutcome(messages.isEmpty() && count == 0, "주문 전송 $sent 건 · 가져오기 $received 건 · 대기 $count 건" +
                if (messages.isEmpty()) "" else "\n" + messages.joinToString("\n"))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { OrderSyncOutcome(false, orderError(e)) }
    }
    private fun orderError(error: Exception): String = if (error is MasterServerException) when (error.code) {
        "CONFLICT" -> "CONFLICT: 서버 주문이 바뀌었거나 다른 주문과 연결돼 있습니다. 기존 본문과 대기를 유지합니다."
        "NOT_READY", "INVALID_ACTION", "UPGRADE_REQUIRED" -> "서버 주문 API 배포·장부 설정을 확인해주세요. 대기는 유지합니다."
        "UNAUTHORIZED", "DEVICE_LIMIT" -> "기존 서버의 기기 연결을 확인해주세요. 대기는 유지합니다."
        "INVALID" -> "주문 형식이 서버 기준과 맞지 않습니다. 대기와 원문을 유지합니다."
        else -> "서버 주문 결과를 확인하지 못했습니다. 다시 동기화할 때 같은 작업으로 확인합니다."
    } else error.message ?: "주문 결과 불명 · 전송 대기와 같은 작업ID를 유지합니다."
    private suspend fun push(queued: PendingSyncEntity) {
        val id = checkNotNull(queued.syncId); val value = work(queued); val local = localNumber(value)
        check(pending(local).none { it.syncType != PendingSyncType.ORDER_V2 }) { "기존 주문의 연결을 먼저 확인해주세요." }
        var state = link(local)
        var body = value.getAsJsonObject("body")
        if (body == null) {
            val kind = OrderProtocol.text(value, "kind")
            if (state == null) {
                check(kind == "new") { "주문 연결 정보가 없습니다. 먼저 서버 주문에 연결해주세요." }
                val server = value.get("serverNumber")?.asString ?: reserve("order:" + OrderProtocol.text(value, "eventId"))
                check(links().none { it.get("serverNumber")?.asString == server && it.get("localNumber")?.asString != local })
                val created = newLink(local, server)
                database.withTransaction {
                    check(dao.getAllPendingSync().any { it.syncId == id }) { "대기가 변경돼 서버 주문을 준비하지 않았습니다." }
                    saveLink(created)
                }
                state = created
            }
            val server = OrderProtocol.text(state, "serverNumber")
            val remote = lastFor(server)
            val event = OrderProtocol.text(value, "eventId")
            body = if (kind == "delete") {
                check(remote != null && remote.get("deleted")?.asBoolean != true) { "서버 주문의 현재 삭제 가능 상태를 먼저 확인해주세요." }
                val base = value.get("baseFingerprint")?.asString
                check((base == null || base == remote.get("fingerprint")?.asString ||
                    state.getAsJsonObject("last")?.get("fingerprint") == remote.get("fingerprint")) &&
                    OrderProtocol.matches(remote, entity(value, "before"))) {
                    "CONFLICT: 삭제 확인 후 서버 주문이 바뀌었습니다. 최신 주문을 먼저 확인해주세요."
                }
                JsonObject().apply {
                    addProperty("action", "delete_sale"); addProperty("eventId", event); addProperty("orderNumber", server)
                    addProperty("revision", OrderProtocol.number(OrderProtocol.obj(remote, "metadata"), "revision", 1))
                }
            } else if (kind == "new") {
                if (remote != null && remote.get("deleted")?.asBoolean != true) {
                    val seed = OrderProtocol.obj(value, "seed")
                    check(OrderProtocol.text(seed, "sourceRequestId").isNotEmpty() &&
                        OrderProtocol.text(OrderProtocol.obj(OrderProtocol.obj(remote, "personalData"), "order"), "sourceRequestId") ==
                        OrderProtocol.text(seed, "sourceRequestId") && OrderProtocol.matches(remote, entity(value, "order"))) {
                        "같은 서버 번호의 주문이 이미 있습니다. 새 판매로 덮어쓰지 않았습니다."
                    }
                    accept(state, remote)
                    database.withTransaction { dao.deletePendingSync(id) }
                    return
                }
                check(remote == null) { "이미 삭제된 서버 주문에 새 판매를 만들지 않았습니다." }
                OrderProtocol.saveBody(OrderProtocol.obj(value, "seed"), server, event, 1)
            } else {
                check(remote != null && remote.get("deleted")?.asBoolean != true) { "서버 주문을 먼저 가져와주세요." }
                val base = value.get("baseFingerprint")?.asString
                check(base == null || base == remote.get("fingerprint")?.asString ||
                    state.getAsJsonObject("last")?.get("fingerprint") == remote.get("fingerprint")) {
                    "CONFLICT: 수정 기준과 현재 서버 주문이 다릅니다."
                }
                val seed = OrderProtocol.editSeed(remote, entity(value, "before"), entity(value, "order"), kind, OrderProtocol.number(value, "at", 1))
                OrderProtocol.saveBody(seed, server, event, OrderProtocol.number(OrderProtocol.obj(remote, "metadata"), "revision", 1) + 1)
            }
            value.add("body", body.deepCopy())
            database.withTransaction {
                check(dao.getAllPendingSync().any { it.syncId == id }) { "대기가 변경돼 주문을 전송하지 않았습니다." }
                dao.updatePendingPayload(id, json.toJson(value))
            } // Freeze before any write request.
        }
        database.withTransaction {
            check(dao.getAllPendingSync().any { it.syncId == id }) { "대기가 삭제돼 주문을 전송하지 않았습니다." }
            value.remove("rejected") // A retry is submitted again; concurrent queue clearing must stop.
            dao.updatePendingPayload(id, json.toJson(value))
        }
        val action = OrderProtocol.text(body, "action")
        val response = try { gateway.request(action, fields(body)) } catch (e: MasterServerException) {
            if (e.code in setOf("CONFLICT", "INVALID")) {
                value.addProperty("rejected", true)
                database.withTransaction { dao.updatePendingPayload(id, json.toJson(value)) }
            }
            throw e
        }
        state = checkNotNull(link(local)) { "주문 연결 기록이 없습니다. 전송 대기를 유지합니다." }
        if (action == "delete_sale") {
            check(response.get("deleted")?.asBoolean == true && response.get("orderNumber") == body.get("orderNumber") && response.get("revision") == body.get("revision")) {
                "주문 삭제 응답이 보낸 번호·revision과 다릅니다. 대기를 유지합니다."
            }
            state.addProperty("deleted", true); saveLink(state)
            database.withTransaction { dao.deleteOrderByNumber(local); dao.deletePendingSync(id) }
        } else {
            val meta = OrderProtocol.obj(body, "metadata")
            check(response.get("saved")?.asBoolean == true && response.get("orderNumber") == meta.get("orderNumber") &&
                response.get("revision") == meta.get("revision") && response.get("fingerprint") == body.get("fingerprint")) {
                "서버 주문 응답의 번호·revision·지문을 확인하지 못했습니다. 같은 본문을 보관합니다."
            }
            val row = JsonObject().apply {
                add("metadata", meta.deepCopy()); add("personalData", OrderProtocol.obj(body, "personalData").deepCopy())
                add("eventId", body.get("eventId").deepCopy()); add("fingerprint", body.get("fingerprint").deepCopy())
                addProperty("hidden", false)
            }
            accept(state, row)
            database.withTransaction { dao.deletePendingSync(id) }
        }
    }
    /** Explicit, read-only lookup for old/unlinked/conflicted orders. No identity inferred from phone/amount. */
    private suspend fun scan(): List<JsonObject> {
        var after = 0L; val rows = mutableListOf<JsonObject>()
        repeat(200) {
            val response = gateway.request("list_sales", mapOf("afterSequence" to after))
            val page = response.getAsJsonArray("sales") ?: error("주문 목록이 없습니다.")
            val more = OrderProtocol.bool(response, "hasMore")
            check(page.size() <= 100 && (page.size() > 0 || !more))
            page.forEach { item -> val row = item.asJsonObject
                val sequence = OrderProtocol.number(row, "sequence", 1); check(sequence > after); after = sequence
                if (row.get("expired")?.asBoolean != true) { OrderProtocol.validateRow(row); rows += row }
            }
            if (!more) return rows
        }
        error("기존 주문 조회가 아직 끝나지 않아 새 등록을 허용하지 않았습니다. 자료와 대기를 보관합니다.")
    }
    suspend fun review(number: String): OrderLinkReview = mutex.withLock {
        ready(); val current = dao.findOrdersByNumber(number).singleOrNull() ?: error("기존 주문번호가 없거나 중복돼 있습니다.")
        val rows = scan()
        val candidates = rows.filter {
            val remote = OrderProtocol.local(it, number, current.orderId)
            serverNumber(it) == displayNumber(number) || remote.customerPhone == current.customerPhone && remote.totalPrice == current.totalPrice
        }
        candidates.forEach(::cache)
        val web = webRecords().any { it.sharedPayment?.orderNumber == number } || current.note.startsWith("웹 견적 ")
        OrderLinkReview(number, candidates.take(30).map { row ->
            val order = OrderProtocol.local(row, number)
            OrderServerChoice(serverNumber(row), order.customerPhone, order.totalPrice.toLong(), order.status,
                order.orderDate, order.itemSummary.lines().joinToString(" · ") { it.substringBefore('|') }.take(160))
        }, allowNew = !web && link(number) == null && OrderProtocol.status(current.status) == "입금확인" && candidates.isEmpty())
    }
    private fun archive(number: String) {
        val rows = pending(number)
        check(links().none { it.get("importPending")?.asBoolean == true } && rows.none {
            it.syncType == PendingSyncType.ORDER_V2 && work(it).has("body") && work(it).get("rejected")?.asBoolean != true
        }) { "서버에 제출한 주문의 결과가 아직 불명확합니다. 같은 작업의 결과를 먼저 확인해주세요." }
        write(key("archive", uid()), JsonObject().apply {
            addProperty("localNumber", number); add("order", json.toJsonTree(dao.findOrdersByNumber(number).single()))
            add("pending", json.toJsonTree(rows)); link(number)?.let { add("link", it.deepCopy()) }
        })
    }
    private suspend fun recoverImports() {
        for (state in links().filter { it.get("importPending")?.asBoolean == true }) {
            val number = OrderProtocol.text(state, "localNumber")
            val row = OrderProtocol.obj(state, "last")
            OrderProtocol.validateRow(row)
            val current = dao.findOrdersByNumber(number).singleOrNull() ?: error("연결 중인 기기 주문을 찾지 못했습니다. 보관 기록을 유지합니다.")
            val queued = pending(number)
            val expected = state.get("importQueue")
            check(json.toJsonTree(queued) == expected || queued.isEmpty() && OrderProtocol.matches(row, current)) {
                "중단된 연결 이후 주문 대기가 바뀌었습니다. 보관 기록을 먼저 확인해주세요."
            }
            val merged = state.get("mergeFrom")?.asString
            if (merged != null) check(pending(merged).isEmpty()) { "합칠 서버 사본에 수정 대기가 생겼습니다. 기존 자료를 보관합니다." }
            database.withTransaction {
                dao.updateOrder(OrderProtocol.local(row, number, current.orderId))
                queued.forEach { dao.deletePendingSync(checkNotNull(it.syncId)) }
                if (merged != null) dao.deleteOrderByNumber(merged)
            }
            if (merged != null) check(prefs.edit().remove(key("link", merged)).commit())
            state.remove("importPending"); state.remove("importQueue"); state.remove("mergeFrom")
            accept(state, row)
        }
    }
    suspend fun useServer(number: String, selected: String) = mutex.withLock {
        ready(); val row = scan().singleOrNull { serverNumber(it) == selected } ?: error("선택한 서버 주문을 다시 확인해주세요.")
        val others = links().filter { it.get("serverNumber")?.asString == selected && it.get("localNumber")?.asString != number }
        check(others.size <= 1) { "주문 연결이 중복돼 있습니다. 기존 자료를 유지합니다." }
        val other = others.singleOrNull()
        val merged = other?.let { OrderProtocol.text(it, "localNumber") }
        check(other == null || other.get("imported")?.asBoolean == true && merged == selected && pending(checkNotNull(merged)).isEmpty()) {
            "이 서버 주문에 다른 로컬 변경이 있습니다. 기존 주문을 보관합니다."
        }
        archive(number)
        if (merged != null) archive(merged)
        // Journal the user's selection before Room; replay always recovers the selected server copy.
        val state = newLink(number, selected).apply {
            addProperty("importPending", true); add("last", row.deepCopy()); add("importQueue", json.toJsonTree(pending(number)))
            if (merged != null) addProperty("mergeFrom", merged)
        }
        saveLink(state); cache(row)
        recoverImports()
    }
    suspend fun registerManual(number: String) = mutex.withLock {
        ready(); check(link(number) == null) { "이미 서버 연결이 있는 주문입니다." }
        val current = dao.findOrdersByNumber(number).single()
        check(webRecords().none { it.sharedPayment?.orderNumber == number } && !current.note.startsWith("웹 견적 ")) {
            "웹 입금 주문을 수기 신규 주문으로 등록할 수 없습니다. 기존 입금확인 기록으로 재개해주세요."
        }
        check(OrderProtocol.status(current.status) == "입금확인") { "과거 배송·취소 시각을 추측해 등록하지 않습니다. 서버 주문을 연결해주세요." }
        val candidates = scan().filter {
            val remote = OrderProtocol.local(it, number)
            serverNumber(it) == number || remote.customerPhone == current.customerPhone && remote.totalPrice == current.totalPrice
        }
        check(candidates.isEmpty()) { "유사한 서버 주문이 있어 새 등록하지 않았습니다. 서버 주문 연결을 먼저 확인해주세요." }
        archive(number)
        val at = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul"); isLenient = false
        }.parse(current.orderDate)?.time ?: error("과거 주문시각을 확인해주세요.")
        val seed = OrderProtocol.seed(current, at)
        database.withTransaction {
            pending(number).forEach { dao.deletePendingSync(checkNotNull(it.syncId)) }
            val value = baseWork(current, "new", at).apply { add("seed", seed) }
            dao.insertPendingSync(PendingSyncEntity(syncType = PendingSyncType.ORDER_V2, payloadJson = json.toJson(value), createdAt = at))
        }
    }
}
