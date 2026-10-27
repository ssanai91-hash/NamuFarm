package com.example.namufarm.data

import androidx.room.withTransaction
import com.example.namufarm.data.remote.OperationsMasterGateway
import com.example.namufarm.data.remote.MasterServerException
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

interface InventoryLinkSettings { var inventoryLinked: Boolean }

data class InventoryKey(val farmId: Int, val speciesId: Int, val quality: String, val age: String, val size: String, val plantType: String)
internal fun FarmInventoryEntity.stockKey() = InventoryKey(farmId, speciesId, quality, age, size, plantType)
internal fun InventoryLinkEntity.stockKey() = InventoryKey(farmId, speciesId, quality, age, size, plantType)

data class InventoryMutation(val operationId: String, val farmId: Int, val speciesId: Int,
    val quality: String, val age: String, val size: String, val plantType: String,
    val quantity: Int, val wholesalePrice: Int, val retailPrice: Int) {
    fun key() = InventoryKey(farmId, speciesId, quality, age, size, plantType)
    fun local() = FarmInventoryEntity(farmId, speciesId, quality, age, size, plantType, quantity, wholesalePrice, retailPrice)
    fun body(remoteFarm: Int, remoteSpecies: Int): Map<String, Any> = mapOf("farmId" to remoteFarm,
        "speciesId" to remoteSpecies, "quality" to quality, "age" to age, "size" to size, "plantType" to plantType,
        "quantity" to quantity, "wholesalePrice" to wholesalePrice, "retailPrice" to retailPrice)
    companion object {
        fun from(entity: FarmInventoryEntity) = InventoryMutation(UUID.randomUUID().toString(), entity.farmId,
            entity.speciesId, entity.quality, entity.age, entity.size, entity.plantType,
            entity.quantity, entity.wholesalePrice, entity.retailPrice)
    }
}

data class InventoryRecord(val farmId: Int, val speciesId: Int, val quality: String, val age: String, val size: String,
    val plantType: String, val quantity: Int, val wholesalePrice: Int, val retailPrice: Int, val updatedAt: String, val version: String) {
    fun key() = InventoryKey(farmId, speciesId, quality, age, size, plantType)
    fun local(farm: Int, species: Int) = FarmInventoryEntity(farm, species, quality, age, size, plantType, quantity, wholesalePrice, retailPrice)
}

data class InventoryDifference(val key: InventoryKey, val local: FarmInventoryEntity?, val remote: InventoryRecord?,
    val pending: List<PendingSyncEntity>, val firstLink: Boolean)
data class InventoryReviewState(val differences: List<InventoryDifference> = emptyList(), val firstLink: Boolean = false)
data class InventoryOutcome(val success: Boolean, val message: String, val sent: Int = 0, val received: Int = 0)

/** A single master/inventory sequence owns mapping and versions. No network call runs in a Room transaction. */
class InventorySync(private val database: AppDatabase, private val client: OperationsMasterGateway,
    private val masters: OperationsMasterSync, private val settings: InventoryLinkSettings,
    private val filesDir: java.io.File? = null) {
    private val dao = database.managementDao()
    private val gson = Gson()
    private var firstBackupDone = false
    private val mutableReview = MutableStateFlow(InventoryReviewState())
    val review = mutableReview.asStateFlow()
    fun pendingCount(): Int = pending().size
    private fun pending() = dao.getAllPendingSync().filter { it.syncType == PendingSyncType.INVENTORY_SAVE }
    private fun link(k: InventoryKey) = dao.getInventoryLink(k.farmId, k.speciesId, k.quality, k.age, k.size, k.plantType)
    private fun local(k: InventoryKey) = dao.findInventory(k.farmId, k.speciesId, k.quality, k.age, k.size, k.plantType)
    private fun deleteLocal(k: InventoryKey) = dao.deleteInventory(k.farmId, k.speciesId, k.quality, k.age, k.size, k.plantType)
    private fun putLink(k: InventoryKey, version: String) = dao.upsertInventoryLink(InventoryLinkEntity(
        k.farmId, k.speciesId, k.quality, k.age, k.size, k.plantType, version))
    private fun mutation(row: PendingSyncEntity): InventoryMutation {
        val value = gson.fromJson(row.payloadJson, InventoryMutation::class.java)
        require(UUID.fromString(value.operationId).toString() == value.operationId) { "재고 작업 번호를 확인해주세요." }
        require(value.farmId > 0 && value.speciesId > 0 && value.quantity >= 0 && value.wholesalePrice >= 0 && value.retailPrice >= 0)
        listOf(value.quality, value.age, value.size, value.plantType).forEach { require(it.length <= 100 && '\n' !in it && '\r' !in it) }
        return value
    }
    private fun sameValues(a: FarmInventoryEntity, b: FarmInventoryEntity) = a.stockKey() == b.stockKey() &&
        a.quantity == b.quantity && a.wholesalePrice == b.wholesalePrice && a.retailPrice == b.retailPrice
    private fun enqueue(entity: FarmInventoryEntity) {
        val value = InventoryMutation.from(entity)
        dao.insertPendingSync(PendingSyncEntity(syncType = PendingSyncType.INVENTORY_SAVE, payloadJson = gson.toJson(value)))
    }
    /** This is also safe when nested in the order-creation Room transaction. */
    suspend fun recordInventoryChange(entity: FarmInventoryEntity, quantityAfter: Int) = database.withTransaction {
        val changed = entity.copy(quantity = quantityAfter.coerceAtLeast(0))
        require(changed.wholesalePrice >= 0 && changed.retailPrice >= 0)
        if (changed.quantity == 0) deleteLocal(changed.stockKey()) else dao.upsertInventory(changed)
        val unattempted = pending().lastOrNull { row ->
            row.retryCount == 0 && row.lastError.isBlank() && mutation(row).key() == changed.stockKey()
        }
        if (unattempted == null) enqueue(changed)
        else {
            val old = mutation(unattempted)
            dao.updatePendingPayload(requireNotNull(unattempted.syncId), gson.toJson(InventoryMutation.from(changed).copy(operationId = old.operationId)))
        }
    }
    fun hasStockOrPending(speciesId: Int): Boolean = dao.getAllInventoryEntities().any { it.speciesId == speciesId && it.quantity > 0 } ||
        dao.getAllPendingSync().any { row ->
            when (row.syncType) {
                PendingSyncType.INVENTORY_SAVE -> runCatching { mutation(row).speciesId == speciesId }.getOrDefault(true)
                PendingSyncType.INVENTORY -> runCatching {
                    gson.fromJson(row.payloadJson, PendingInventorySyncPayload::class.java).entity.speciesId == speciesId
                }.getOrDefault(true)
                else -> false
            }
        }

    private fun integer(obj: JsonObject, field: String, minimum: Int = 0): Int {
        val value = requireNotNull(obj.get(field)) { "재고 응답 필드 없음: $field" }
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "재고 숫자 형식 오류: $field" }
        return value.asBigDecimal.intValueExact().also { require(it >= minimum) { "재고 숫자 범위 오류: $field" } }
    }
    private fun text(obj: JsonObject, field: String, maximum: Int = 100): String {
        val value = requireNotNull(obj.get(field)) { "재고 응답 필드 없음: $field" }
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.also { require(it.length <= maximum && '\r' !in it && '\n' !in it) }
    }
    private fun record(obj: JsonObject): InventoryRecord {
        val version = text(obj, "version", 64)
        require(Regex("[a-f0-9]{64}").matches(version)) { "서버 재고 버전을 확인할 수 없습니다." }
        return InventoryRecord(integer(obj, "farmId", 1), integer(obj, "speciesId", 1), text(obj, "quality"),
            text(obj, "age"), text(obj, "size"), text(obj, "plantType"), integer(obj, "quantity"),
            integer(obj, "wholesalePrice"), integer(obj, "retailPrice"), text(obj, "updatedAt"), version)
    }
    private suspend fun fetch(): List<InventoryRecord> {
        val response = client.request("list_inventory")
        require(response.get("inventoryVersioning")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asBigDecimal.compareTo(java.math.BigDecimal.ONE) == 0 } == true) {
            "운영 서버의 재고 기능이 최신이 아닙니다. 휴대폰 재고는 그대로 보관됩니다."
        }
        val rows = response.getAsJsonArray("inventory") ?: error("서버 재고 목록을 확인할 수 없습니다.")
        require(rows.size() <= 15000) { "서버 재고 목록이 너무 큽니다." }
        val records = rows.map { record(it.asJsonObject) }
        require(records.map { it.key() }.distinct().size == records.size) { "서버 재고 규격이 중복되어 반영하지 않았습니다." }
        return records
    }
    private fun localRows(records: List<InventoryRecord>): Pair<Map<InventoryKey, InventoryRecord>, Int> {
        val farms = dao.getMasterLinks(OperationsMasterSync.FARM).associate { it.remoteId to it.localId }
        val species = dao.getMasterLinks(OperationsMasterSync.SPECIES).associate { it.remoteId to it.localId }
        val actualFarms = dao.getAllFarms().mapNotNull { it.farmId }.toSet()
        val actualSpecies = dao.getAllSpeciesIncludingInactive().mapNotNull { it.speciesId }.toSet()
        var skipped = 0
        val mapped = linkedMapOf<InventoryKey, InventoryRecord>()
        for (row in records) {
            val f = farms[row.farmId]; val s = species[row.speciesId]
            if (f == null || s == null || f !in actualFarms || s !in actualSpecies) { skipped++; continue }
            val key = InventoryKey(f, s, row.quality, row.age, row.size, row.plantType)
            require(key !in mapped) { "재고 연결 키가 중복되어 반영하지 않았습니다." }
            mapped[key] = row
        }
        return mapped to skipped
    }
    private fun applyServer(key: InventoryKey, remote: InventoryRecord?) {
        if (remote == null) {
            deleteLocal(key)
            dao.deleteInventoryLink(key.farmId, key.speciesId, key.quality, key.age, key.size, key.plantType)
        } else {
            if (remote.quantity == 0) deleteLocal(key) else dao.upsertInventory(remote.local(key.farmId, key.speciesId))
            putLink(key, remote.version)
        }
    }
    suspend fun sync(): InventoryOutcome = masters.inventorySequence { master ->
        if (!master.success) return@inventorySequence InventoryOutcome(false, master.message)
        if (dao.getAllPendingSync().any { OperationsMasterSync.kind(it.syncType) != null })
            return@inventorySequence InventoryOutcome(false, "농원·묘목 등록이 서버에서 확인된 뒤 재고를 보냅니다.")
        try {
            val remote = fetch() // Validate the entire protocol before touching any inventory row or queue.
            if (!settings.inventoryLinked) return@inventorySequence reconcileInitial(remote)
            syncLinked(remote)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { InventoryOutcome(false, e.message ?: "재고 반영 대기 ${pendingCount()}건 · 네트워크 확인 후 다시 동기화해주세요.") }
    }
    private suspend fun syncLinked(first: List<InventoryRecord>): InventoryOutcome {
        var sent = 0
        for (row in pending()) {
            if (row.lastError.startsWith("CONFLICT:")) {
                showConflict(row, first)
                return InventoryOutcome(false, "다른 기기(PC)와 재고 수정이 겹쳤어요. 확인하기를 눌러주세요.", sent)
            }
            try { push(row); sent++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val conflict = e is MasterServerException && e.code == "CONFLICT"
                dao.markPendingSyncFailed(requireNotNull(row.syncId), (if (conflict) "CONFLICT: " else "") + (e.message ?: "서버 확인 실패"))
                if (conflict) {
                    showConflict(row, first)
                    try { showConflict(row, fetch()) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                }
                return InventoryOutcome(false, if (conflict) "다른 기기(PC)와 재고 수정이 겹쳤어요. 확인하기를 눌러주세요."
                    else "재고 반영 대기 ${pendingCount()}건 · ${e.message ?: "네트워크 확인 후 다시 동기화해주세요."}", sent)
            }
        }
        val records = if (sent > 0) fetch() else first
        val (mapped, skipped) = localRows(records)
        var missing = 0
        database.withTransaction {
            check(pending().isEmpty()) { "새 재고 변경은 휴대폰에 보관했습니다. 다시 동기화해주세요." }
            val phone = dao.getAllInventoryEntities()
            for ((key, remote) in mapped) applyServer(key, remote)
            for (row in phone) if (row.stockKey() !in mapped) {
                if (link(row.stockKey()) == null) enqueue(row) else missing++
            }
        }
        val count = pendingCount()
        return InventoryOutcome(count == 0, if (count > 0) "재고 반영 대기 ${count}건 · 다시 동기화해주세요." else
            "재고 동기화 완료 (보냄 ${sent}건 · 받음 ${mapped.size}건)" +
                (if (missing > 0) " · 서버에 없는 재고 ${missing}건은 휴대폰에 보관 중" else "") +
                (if (skipped > 0) " · 연결 없는 서버 재고 ${skipped}건 건너뜀" else ""), sent, mapped.size)
    }
    private suspend fun push(row: PendingSyncEntity) {
        val value = mutation(row)
        val farm = dao.getMasterLinks(OperationsMasterSync.FARM).firstOrNull { it.localId == value.farmId }
        val species = dao.getMasterLinks(OperationsMasterSync.SPECIES).firstOrNull { it.localId == value.speciesId }
        check(farm != null && species != null) { "농원·묘목 등록이 서버에서 확인된 뒤 재고를 보냅니다." }
        val expected = database.withTransaction {
            check(dao.getAllPendingSync().firstOrNull { it.syncId == row.syncId }?.payloadJson == row.payloadJson) {
                "전송 대기 내용이 바뀌었습니다. 휴대폰 자료를 유지합니다."
            }
            dao.markInventoryAttempt(requireNotNull(row.syncId)) // Persist before I/O; never coalesce an attempted operation.
            link(value.key())?.version.orEmpty()
        }
        val response = client.request("save_inventory", mapOf("operationId" to value.operationId,
            "inventory" to value.body(farm.remoteId, species.remoteId), "expectedVersion" to expected))
        check(response.get("saved")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true && response.get("operationId")?.asString == value.operationId) {
            "서버 재고 저장 확인이 일치하지 않습니다."
        }
        val saved = record(response.getAsJsonObject("inventoryRecord") ?: error("서버 재고 저장 결과가 없습니다."))
        check(saved.farmId == farm.remoteId && saved.speciesId == species.remoteId &&
            sameValues(saved.local(value.farmId, value.speciesId), value.local())) { "서버 재고 저장 내용이 보낸 값과 다릅니다." }
        database.withTransaction {
            check(dao.getAllPendingSync().firstOrNull { it.syncId == row.syncId }?.payloadJson == row.payloadJson)
            putLink(value.key(), saved.version)
            dao.deletePendingSync(requireNotNull(row.syncId))
        }
    }
    private suspend fun reconcileInitial(records: List<InventoryRecord>, resolved: Set<InventoryKey> = emptySet()): InventoryOutcome {
        if (!firstBackupDone) {
            database.withTransaction {
                InventoryFirstBackup.write(requireNotNull(filesDir) { "재고 맞추기 백업 위치가 없습니다." },
                    dao.getAllInventoryEntities(), dao.getAllPendingSync())
                dao.deleteLegacyInventoryPending() // Only legacy inventory; every removed row is in the durable JSON above.
            }
            firstBackupDone = true
        }
        val (mapped, skipped) = localRows(records)
        val differences = database.withTransaction {
            val phone = dao.getAllInventoryEntities().associateBy { it.stockKey() }.toMutableMap()
            val queued = pending().groupBy { mutation(it).key() }
            queued.forEach { (key, rows) -> if (key !in phone) phone[key] = mutation(rows.last()).local() }
            phone.mapNotNull { (key, row) ->
                mapped[key]?.takeIf { key !in resolved && !sameValues(row, it.local(key.farmId, key.speciesId)) }?.let {
                    InventoryDifference(key, row, it, queued[key].orEmpty(), true)
                }
            }
        }
        if (differences.isNotEmpty()) {
            mutableReview.value = InventoryReviewState(differences, true)
            return InventoryOutcome(false, "재고 맞추기 대기 ${differences.size}건 · 휴대폰과 서버 값을 확인해주세요.")
        }
        database.withTransaction {
            // Recheck local values and pending operations after building the comparison, before writing anything.
            val phone = dao.getAllInventoryEntities().associateBy { it.stockKey() }.toMutableMap()
            val queued = pending().groupBy { mutation(it).key() }
            queued.forEach { (key, rows) -> if (key !in phone) phone[key] = mutation(rows.last()).local() }
            check(phone.none { (key, row) -> key !in resolved && mapped[key]?.let { !sameValues(row, it.local(key.farmId, key.speciesId)) } == true }) {
                "재고가 바뀌었습니다. 휴대폰 자료를 유지했으니 다시 맞춰주세요."
            }
            mapped.forEach { (key, remote) ->
                if (key !in phone) applyServer(key, remote) else putLink(key, remote.version)
            }
            phone.forEach { (key, row) -> if (key !in mapped) {
                dao.deleteInventoryLink(key.farmId, key.speciesId, key.quality, key.age, key.size, key.plantType)
                if (queued[key].isNullOrEmpty()) enqueue(row)
            } }
        }
        settings.inventoryLinked = true
        mutableReview.value = InventoryReviewState()
        val result = syncLinked(records)
        return if (skipped > 0) result.copy(message = result.message + " · 연결 없는 서버 재고 ${skipped}건 건너뜀") else result
    }
    private fun reviewUnchanged(item: InventoryDifference): Boolean {
        val current = local(item.key)
        val sameLocal = current == item.local || (current == null && item.local?.quantity == 0)
        return sameLocal && pending().filter { mutation(it).key() == item.key } == item.pending
    }
    suspend fun resolveInitialReview(useServer: Boolean): InventoryOutcome = masters.inventorySequence { master ->
        if (!master.success) return@inventorySequence InventoryOutcome(false, master.message)
        val review = mutableReview.value
        if (!review.firstLink || review.differences.isEmpty()) return@inventorySequence InventoryOutcome(false, "먼저 재고 맞추기 목록을 확인해주세요.")
        try {
            val remote = fetch()
            val mapped = localRows(remote).first
            database.withTransaction {
                check(review.differences.all { item -> reviewUnchanged(item) && mapped[item.key]?.version == item.remote?.version }) {
                    "비교 중 재고가 바뀌었습니다. 휴대폰 자료를 유지했으니 다시 동기화해주세요."
                }
                for (item in review.differences) {
                    val currentRemote = mapped[item.key]
                    item.pending.forEach { dao.deletePendingSync(requireNotNull(it.syncId)) }
                    if (useServer) applyServer(item.key, currentRemote)
                    else {
                        currentRemote?.let { putLink(item.key, it.version) }
                        enqueue(requireNotNull(item.local))
                    }
                }
            }
            mutableReview.value = InventoryReviewState()
            reconcileInitial(remote, review.differences.map { it.key }.toSet())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { InventoryOutcome(false, e.message ?: "재고 맞추기를 완료하지 못했습니다. 휴대폰 자료를 유지합니다.") }
    }
    suspend fun resolveConflict(useServer: Boolean): InventoryOutcome = masters.inventorySequence { master ->
        if (!master.success) return@inventorySequence InventoryOutcome(false, master.message)
        val review = mutableReview.value
        val item = review.differences.singleOrNull()?.takeIf { !review.firstLink }
            ?: return@inventorySequence InventoryOutcome(false, "먼저 재고 충돌 목록을 확인해주세요.")
        try {
            val remote = fetch()
            val mapped = localRows(remote).first
            val first = pending().firstOrNull() ?: return@inventorySequence InventoryOutcome(false, "재고 대기가 바뀌었습니다. 다시 동기화해주세요.")
            val fresh = mapped[item.key]
            val accepted = database.withTransaction {
                if (first.syncId != item.pending.firstOrNull()?.syncId || !reviewUnchanged(item) || fresh?.version != item.remote?.version) false
                else {
                    if (fresh == null) dao.deleteInventoryLink(item.key.farmId, item.key.speciesId, item.key.quality, item.key.age, item.key.size, item.key.plantType)
                    else putLink(item.key, fresh.version)
                    if (useServer) {
                        dao.deletePendingSync(requireNotNull(first.syncId))
                        // The choice discards this operation only; later local edits must survive.
                        if (pending().none { mutation(it).key() == item.key }) applyServer(item.key, fresh)
                    } else {
                        val retried = mutation(first).copy(operationId = UUID.randomUUID().toString())
                        dao.updatePendingPayload(requireNotNull(first.syncId), gson.toJson(retried))
                        dao.clearPendingSyncError(requireNotNull(first.syncId))
                    }
                    true
                }
            }
            if (!accepted) {
                showConflict(first, remote)
                return@inventorySequence InventoryOutcome(false, "비교 중 재고가 바뀌었습니다. 최신 값으로 다시 선택해주세요.")
            }
            mutableReview.value = InventoryReviewState()
            syncLinked(remote)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { InventoryOutcome(false, e.message ?: "충돌 처리를 완료하지 못했습니다. 전송 대기를 유지합니다.") }
    }
    private fun showConflict(row: PendingSyncEntity, records: List<InventoryRecord>) {
        val key = mutation(row).key()
        val remote = localRows(records).first[key]
        mutableReview.value = InventoryReviewState(listOf(InventoryDifference(key, local(key), remote,
            pending().filter { mutation(it).key() == key }, false)), false)
    }
}
