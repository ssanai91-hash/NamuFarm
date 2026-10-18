package com.example.namufarm.data

import androidx.room.Room
import com.example.namufarm.data.remote.OperationsMasterGateway
import com.example.namufarm.data.remote.MasterServerException
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OperationsMasterSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ManagementDao
    private lateinit var server: FakeServer
    private lateinit var sync: OperationsMasterSync
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.managementDao()
        server = FakeServer()
        sync = OperationsMasterSync(db, server)
    }
    @After fun close() { db.close() }

    @Test fun offlineSaveIsDurableAndManualSyncAcknowledgesServerId() = runBlocking {
        server.offline = true
        val result = sync.saveFarm(FarmEntity(farmName = "새 농원"))
        assertFalse(result.success)
        val localId = dao.getAllFarms().single().farmId!!
        assertEquals(1, dao.getAllPendingSync().size)
        server.offline = false
        assertTrue(sync.sync().success)
        assertTrue(dao.getAllPendingSync().isEmpty())
        assertEquals(localId, dao.getMasterLinks("farm").single().localId)
        assertEquals(101, dao.getMasterLinks("farm").single().remoteId)
        assertTrue(sync.initialized)
    }

    @Test fun numericCollisionAndInactiveSpeciesNeverBreakLocalStock() = runBlocking {
        dao.upsertFarm(FarmEntity(1, "기기 농원"))
        dao.upsertSpecies(SpeciesEntity(1, "기기 묘목"))
        dao.upsertInventory(FarmInventoryEntity(1, 1, "상묘", "1년", "", "실생", 9, 100, 200))
        server.farms[1] = server.farm(1, "서버 농원")
        server.species[1] = server.species(1, "서버 묘목", 0)
        assertTrue(sync.sync().success)
        assertEquals(1, dao.getFarmInventoryCount(1))
        assertEquals("기기 농원", dao.getAllFarms().first { it.farmId == 1 }.farmName)
        val newLocalId = dao.getMasterLinks("farm").single().localId
        assertNotEquals(1, newLocalId)
        val speciesId = dao.getMasterLinks("species").single().localId
        assertEquals(0, dao.getSpeciesById(speciesId)!!.isActive)
        assertFalse(dao.getAllSpecies().any { it.speciesId == speciesId })
    }

    @Test fun lostCreateResponseRetriesSameOperationWithoutDuplicatingFarm() = runBlocking {
        server.loseNextSaveResponse = true
        assertFalse(sync.saveFarm(FarmEntity(farmName = "응답 유실 농원")).success)
        assertEquals(1, server.farms.size)
        val queued = dao.getAllPendingSync().single().payloadJson
        assertTrue(sync.sync().success)
        assertEquals(1, server.farms.size)
        assertTrue(dao.getAllPendingSync().isEmpty())
        val operationId = Gson().fromJson(queued, JsonObject::class.java).get("operationId").asString
        assertEquals(listOf(operationId, operationId), server.writeOperations)
    }

    @Test fun externalEditConflictKeepsLocalEditAndQueue() = runBlocking {
        server.farms[70] = server.farm(70, "원래 농원")
        assertTrue(sync.sync().success)
        val local = dao.getAllFarms().single()
        server.offline = true
        assertFalse(sync.saveFarm(local.copy(farmName = "기기 수정")).success)
        server.farms[70] = server.farm(70, "서버 수정").apply { addProperty("version", "b".repeat(64)) }
        server.offline = false
        assertFalse(sync.sync().success)
        assertEquals("기기 수정", dao.getAllFarms().single().farmName)
        assertEquals(1, dao.getAllPendingSync().size)
        assertEquals("서버 수정", server.farms[70]!!.get("farmName").asString)
    }

    @Test fun masterSyncPreservesLegacyPendingOrders() = runBlocking {
        dao.insertPendingSync(PendingSyncEntity(syncType = PendingSyncType.ORDER, payloadJson = "{}"))
        assertTrue(sync.sync().success)
        assertEquals(PendingSyncType.ORDER, dao.getAllPendingSync().single().syncType)
    }

    @Test fun farmWithLocalStockCannotBeDeleted() = runBlocking {
        dao.upsertFarm(FarmEntity(1, "재고 농원"))
        dao.upsertInventory(FarmInventoryEntity(1, 1, "상묘", "", "", "", 2, 0, 0))
        assertFalse(sync.deleteFarm(1).success)
        assertEquals(1, dao.getAllFarms().size)
        assertTrue(dao.getAllPendingSync().isEmpty())
    }

    private class FakeServer : OperationsMasterGateway {
        override var downloaded = false
        var offline = false
        var loseNextSaveResponse = false
        val farms = linkedMapOf<Int, JsonObject>()
        val species = linkedMapOf<Int, JsonObject>()
        val writeOperations = mutableListOf<String>()
        private val operations = mutableMapOf<String, JsonObject>()
        private var serial = 100
        fun farm(id: Int, name: String) = Gson().toJsonTree(mapOf(
            "farmId" to id, "farmName" to name, "address" to "", "phone" to "", "memo" to "",
            "version" to "a".repeat(64))).asJsonObject
        fun species(id: Int, name: String, active: Int) = Gson().toJsonTree(mapOf(
            "speciesId" to id, "speciesName" to name, "farmName" to "", "chosung" to "",
            "category" to "", "memo" to "", "isActive" to active,
            "version" to "a".repeat(64))).asJsonObject
        override suspend fun request(action: String, fields: Map<String, Any>): JsonObject {
            if (offline) error("offline")
            return when (action) {
                "list_farms" -> Gson().toJsonTree(mapOf("ok" to true, "farms" to farms.values)).asJsonObject
                "list_species" -> Gson().toJsonTree(mapOf("ok" to true, "species" to species.values)).asJsonObject
                "save_farm", "save_species" -> {
                    val op = fields["operationId"] as String
                    writeOperations.add(op)
                    operations[op]?.let { return it }
                    val isFarm = action == "save_farm"
                    val rows = if (isFarm) farms else species
                    val idKey = if (isFarm) "farmId" else "speciesId"
                    var id = fields[idKey] as Int
                    if (id != 0 && rows[id]?.get("version")?.asString != fields["expectedVersion"])
                        throw MasterServerException("CONFLICT")
                    if (id == 0) id = ++serial
                    val record = Gson().toJsonTree(fields[if (isFarm) "farm" else "species"]).asJsonObject
                    record.addProperty(idKey, id)
                    record.addProperty("version", "%064x".format(serial.toLong() + writeOperations.size))
                    rows[id] = record
                    val response = JsonObject().apply {
                        addProperty("ok", true); addProperty("saved", true); addProperty("operationId", op)
                        add(if (isFarm) "farm" else "speciesRecord", record)
                    }
                    operations[op] = response
                    if (loseNextSaveResponse) {
                        loseNextSaveResponse = false
                        error("response lost after commit")
                    }
                    response
                }
                else -> error("Unexpected action: $action")
            }
        }
    }
}
