package com.example.namufarm.data

import androidx.room.withTransaction
import com.example.namufarm.data.remote.OperationsMasterGateway
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class MasterSyncOutcome(val success: Boolean, val message: String, val localId: Int? = null)

/** Only farm/species masters use this connection. Legacy stock/orders are never cleared. */
class OperationsMasterSync(internal val database: AppDatabase, internal val client: OperationsMasterGateway) {
    private val dao = database.managementDao()
    private val gson = Gson()
    private val mutex = Mutex()
    val initialized: Boolean get() = client.downloaded
    companion object {
        const val FARM = "farm"
        const val SPECIES = "species"
        fun kind(type: String): String? = when (type) {
            PendingSyncType.FARM_SAVE, PendingSyncType.FARM_DELETE -> FARM
            PendingSyncType.SPECIES_SAVE, PendingSyncType.SPECIES_DELETE -> SPECIES
            else -> null
        }
    }
    private fun queue(type: String, payload: Any) {
        val json = gson.toJsonTree(payload).asJsonObject.apply {
            addProperty("operationId", UUID.randomUUID().toString())
        }
        dao.insertPendingSync(PendingSyncEntity(syncType = type, payloadJson = json.toString()))
    }
    suspend fun saveFarm(farm: FarmEntity): MasterSyncOutcome = mutex.withLock {
        val id = database.withTransaction {
            val saved = farm.farmId ?: dao.upsertFarm(farm).toInt()
            val target = farm.copy(farmId = saved)
            dao.upsertFarm(target)
            queue(PendingSyncType.FARM_SAVE, PendingFarmSyncPayload(target))
            saved
        }
        syncLocked(FARM).copy(localId = id)
    }
    suspend fun saveSpecies(species: SpeciesEntity): MasterSyncOutcome = mutex.withLock {
        val id = database.withTransaction {
            val saved = species.speciesId ?: dao.upsertSpecies(species).toInt()
            val target = species.copy(speciesId = saved)
            dao.upsertSpecies(target)
            queue(PendingSyncType.SPECIES_SAVE, PendingSpeciesSyncPayload(target))
            saved
        }
        syncLocked(SPECIES).copy(localId = id)
    }
    private fun hasInventoryWork(id: Int, farm: Boolean): Boolean =
        dao.getAllInventoryEntities().any { (if (farm) it.farmId else it.speciesId) == id && it.quantity > 0 } ||
            dao.getAllPendingSync().any { row -> when (row.syncType) {
                PendingSyncType.INVENTORY_SAVE -> runCatching {
                    val mutation = gson.fromJson(row.payloadJson, InventoryMutation::class.java)
                    (if (farm) mutation.farmId else mutation.speciesId) == id
                }.getOrDefault(true)
                PendingSyncType.INVENTORY -> runCatching {
                    val entity = gson.fromJson(row.payloadJson, PendingInventorySyncPayload::class.java).entity
                    (if (farm) entity.farmId else entity.speciesId) == id
                }.getOrDefault(true)
                else -> false
            } }

    suspend fun deleteFarm(id: Int): MasterSyncOutcome = mutex.withLock {
        val deleted = database.withTransaction {
            if (hasInventoryWork(id, true)) false else {
                dao.deleteFarmById(id)
                queue(PendingSyncType.FARM_DELETE, PendingDeleteFarmSyncPayload(id))
                true
            }
        }
        if (!deleted) MasterSyncOutcome(false, "이 농원의 재고를 먼저 삭제하고 동기화해주세요.") else syncLocked(FARM)
    }
    suspend fun deleteSpecies(id: Int): MasterSyncOutcome = mutex.withLock {
        val hidden = database.withTransaction {
            if (hasInventoryWork(id, false)) false else {
                dao.deactivateSpecies(id)
                dao.getSpeciesById(id)?.let { queue(PendingSyncType.SPECIES_SAVE, PendingSpeciesSyncPayload(it)) }
                true
            }
        }
        if (!hidden) MasterSyncOutcome(false, "이 묘목의 재고를 먼저 삭제하고 동기화해주세요.") else syncLocked(SPECIES)
    }
    suspend fun sync(onlyKind: String? = null): MasterSyncOutcome = mutex.withLock { syncLocked(onlyKind) }

    /** Master edits and stock transfers share this mutex for the complete sequence. */
    suspend fun <T> inventorySequence(block: suspend (MasterSyncOutcome) -> T): T = mutex.withLock {
        val farm = syncLocked(FARM)
        if (!farm.success) block(farm) else {
            val species = syncLocked(SPECIES)
            block(species)
        }
    }

    private fun pendingId(item: PendingSyncEntity): Int {
        val value = gson.fromJson(item.payloadJson, JsonObject::class.java)
        return when (item.syncType) {
            PendingSyncType.FARM_SAVE -> value.getAsJsonObject("farm").get("farmId").asInt
            PendingSyncType.SPECIES_SAVE -> value.getAsJsonObject("species").get("speciesId").asInt
            PendingSyncType.FARM_DELETE -> value.get("farmId").asInt
            PendingSyncType.SPECIES_DELETE -> value.get("speciesId").asInt
            else -> error("지원하지 않는 마스터 작업")
        }
    }
    private suspend fun syncLocked(onlyKind: String?): MasterSyncOutcome {
        return try {
            val kinds = if (onlyKind == null) listOf(FARM, SPECIES) else listOf(onlyKind)
            val counts = mutableMapOf<String, Int>()
            for (k in kinds) counts[k] = pull(k)
            for (item in dao.getAllPendingSync().filter { kind(it.syncType) in kinds }) {
                try { push(item) } catch (e: Exception) {
                    item.syncId?.let { dao.markPendingSyncFailed(it, e.message ?: "시트 반영 실패") }
                    throw e // Keep later edits queued and never pull over unresolved local edits.
                }
            }
            for (k in kinds) counts[k] = pull(k)
            if (onlyKind == null) client.downloaded = true
            val names = kinds.joinToString(", ") { (if (it == FARM) "농원" else "묘목") + " ${counts[it]}건" }
            val other = dao.getAllPendingSync().count { it.syncType in setOf(PendingSyncType.ORDER, PendingSyncType.CANCEL_ORDER, PendingSyncType.DELIVERY, "update_order_status") }
            MasterSyncOutcome(true, "✅ 운영 시트 동기화 완료 ($names)" +
                if (other > 0) " · 주문 전송 대기 ${other}건은 기기에 보관 중" else "")
        } catch (e: Exception) {
            MasterSyncOutcome(false, "⚠️ 기기 데이터 보관 중 · " + (e.message ?: "네트워크 확인 후 다시 동기화해주세요."))
        }
    }

    /** Validate the entire response before changing any local row. No DELETE-all import. */
    private suspend fun pull(k: String): Int {
        val response = client.request(if (k == FARM) "list_farms" else "list_species")
        val key = if (k == FARM) "farms" else "species"
        val array = response.getAsJsonArray(key) ?: error("운영 시트의 목록 응답이 없습니다.")
        require(array.size() <= 5000)
        val idKey = if (k == FARM) "farmId" else "speciesId"
        val nameKey = if (k == FARM) "farmName" else "speciesName"
        val records = array.map { it.asJsonObject }
        records.forEach {
            require(it.get(idKey)?.asInt?.let { id -> id > 0 } == true)
            require(!it.get(nameKey)?.asString.isNullOrBlank())
            require(Regex("[a-f0-9]{64}").matches(it.get("version")?.asString.orEmpty()))
            if (k == SPECIES) require(it.get("isActive")?.asInt in listOf(0, 1))
        }
        require(records.map { it.get(idKey).asInt }.distinct().size == records.size)
        require(records.map { it.get(nameKey).asString }.distinct().size == records.size)
        database.withTransaction {
            val dirty = dao.getAllPendingSync().filter { kind(it.syncType) == k }.map { pendingId(it) }.toSet()
            for (row in records) {
                val remoteId = row.get(idKey).asInt
                val name = row.get(nameKey).asString
                val links = dao.getMasterLinks(k)
                val locals = if (k == FARM) dao.getAllFarms().map { it.farmId!! to it.farmName }
                    else dao.getAllSpeciesIncludingInactive().map { it.speciesId!! to it.speciesName }
                val matched = MasterIdentity.localId(remoteId, name, links, locals)
                val oldLink = links.firstOrNull { it.remoteId == remoteId }
                fun str(key: String) = row.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                if (matched != null && matched in dirty && oldLink == null) {
                    // A new local contact with the same name must not overwrite an existing server row.
                    val fields: Map<String, Any> = if (k == FARM) {
                        val local = dao.getAllFarms().firstOrNull { it.farmId == matched }
                        if (local == null) continue
                        mapOf("farmName" to local.farmName, "address" to local.address.orEmpty(),
                            "phone" to local.phone.orEmpty(), "memo" to local.memo.orEmpty())
                    } else {
                        val local = dao.getSpeciesById(matched) ?: continue
                        mapOf("speciesName" to local.speciesName, "farmName" to local.nameFarm.orEmpty(),
                            "chosung" to local.chosung.orEmpty(), "category" to local.category.orEmpty(),
                            "memo" to local.memo.orEmpty(), "isActive" to (local.isActive ?: 1))
                    }
                    if (!gson.toJsonTree(fields).asJsonObject.entrySet().all { (key, value) -> row.get(key) == value }) continue
                }
                val localId = if (matched != null && matched in dirty) matched
                    else if (k == FARM) dao.upsertFarm(FarmEntity(matched, name,
                        str("address"), str("phone"), str("memo"))).toInt()
                    else dao.upsertSpecies(SpeciesEntity(matched, name, str("farmName"),
                        str("chosung"), str("category"), str("memo"), row.get("isActive").asInt)).toInt()
                // Preserve the version an offline edit was based on.
                if (matched !in dirty || oldLink == null)
                    dao.upsertMasterLink(MasterLinkEntity(k, localId, remoteId, row.get("version").asString))
            }
            if (k == FARM) {
                val present = records.map { it.get(idKey).asInt }.toSet()
                dao.getMasterLinks(k).filter { it.remoteId !in present && it.localId !in dirty }.forEach {
                    // Keep historical stock references even if a contact was removed in Sheets.
                    if (dao.getFarmInventoryCount(it.localId) == 0) {
                        dao.deleteFarmById(it.localId)
                        dao.deleteMasterLink(k, it.localId)
                    }
                }
            }
        }
        return records.size
    }

    private suspend fun push(item: PendingSyncEntity) {
        val k = kind(item.syncType) ?: error("마스터 작업이 아닙니다.")
        val localId = pendingId(item)
        val payload = gson.fromJson(item.payloadJson, JsonObject::class.java)
        val link = dao.getMasterLinks(k).firstOrNull { it.localId == localId }
        val operationId = payload.get("operationId")?.asString ?: UUID.randomUUID().toString().also {
            payload.addProperty("operationId", it)
            // Persist the UUID before I/O, including queues created by the initial APK.
            dao.updatePendingPayload(requireNotNull(item.syncId), payload.toString())
        }
        val deleting = item.syncType == PendingSyncType.FARM_DELETE || item.syncType == PendingSyncType.SPECIES_DELETE
        if (deleting && link == null) {
            database.withTransaction { item.syncId?.let { dao.deletePendingSync(it) } }
            return // A never-published local ID is not a remote ID.
        }
        val body = mutableMapOf<String, Any>("operationId" to operationId)
        body[if (k == FARM) "farmId" else "speciesId"] = link?.remoteId ?: 0
        link?.let { body["expectedVersion"] = it.version }
        val action: String
        if (deleting) {
            action = if (k == FARM) "delete_farm" else "delete_species"
        } else if (k == FARM) {
            val farm = gson.fromJson(payload.get("farm"), FarmEntity::class.java)
            body["farm"] = mapOf("farmName" to farm.farmName, "address" to farm.address.orEmpty(),
                "phone" to farm.phone.orEmpty(), "memo" to farm.memo.orEmpty())
            action = "save_farm"
        } else {
            val species = gson.fromJson(payload.get("species"), SpeciesEntity::class.java)
            body["species"] = mapOf("speciesName" to species.speciesName,
                "farmName" to species.nameFarm.orEmpty(), "chosung" to species.chosung.orEmpty(),
                "category" to species.category.orEmpty(), "memo" to species.memo.orEmpty(),
                "isActive" to (species.isActive ?: 1))
            action = "save_species"
        }
        val response = client.request(action, body)
        if (deleting) {
            check(response.get("deleted")?.asBoolean == true &&
                response.get(if (k == FARM) "farmId" else "speciesId")?.asInt == link!!.remoteId)
            database.withTransaction {
                // Species deletion changes its version; the next clean pull refreshes it.
                if (k == FARM) dao.deleteMasterLink(k, localId)
                item.syncId?.let { dao.deletePendingSync(it) }
            }
        } else {
            check(response.get("saved")?.asBoolean == true && response.get("operationId")?.asString == operationId)
            val record = response.getAsJsonObject(if (k == FARM) "farm" else "speciesRecord")
                ?: error("시트 저장 결과가 없습니다.")
            val remoteId = record.get(if (k == FARM) "farmId" else "speciesId").asInt
            val version = record.get("version").asString
            require(remoteId > 0 && Regex("[a-f0-9]{64}").matches(version))
            if (link != null) require(link.remoteId == remoteId)
            val sent = gson.toJsonTree(body[if (k == FARM) "farm" else "species"]).asJsonObject
            require(sent.entrySet().all { (key, value) -> record.get(key) == value }) {
                "시트 저장 결과가 전송 내용과 다릅니다."
            }
            database.withTransaction {
                dao.upsertMasterLink(MasterLinkEntity(k, localId, remoteId, version))
                item.syncId?.let { dao.deletePendingSync(it) }
            }
        }
    }
}
