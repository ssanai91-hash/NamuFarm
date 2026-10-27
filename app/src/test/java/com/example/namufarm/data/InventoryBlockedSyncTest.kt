package com.example.namufarm.data

import android.content.Context
import androidx.room.Room
import com.example.namufarm.data.remote.MasterServerException
import com.example.namufarm.data.remote.OperationsMasterGateway
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.1.21 source-only regression cases. No production transport is instantiated. Not run for this change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InventoryBlockedSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ManagementDao
    private lateinit var directory: File
    private lateinit var gateway: FakeGateway
    private lateinit var masters: OperationsMasterSync
    private lateinit var sync: InventorySync
    private val gson = Gson()
    private val settings = object : InventoryLinkSettings { override var inventoryLinked = true }

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        directory = Files.createTempDirectory("blocked-inventory").toFile()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.managementDao()
        dao.upsertFarm(FarmEntity(1, "연결 없는 농원"))
        dao.upsertFarm(FarmEntity(2, "연결 농원"))
        dao.upsertSpecies(SpeciesEntity(1, "시험 묘목"))
        dao.upsertMasterLink(MasterLinkEntity(OperationsMasterSync.FARM, 2, 22, "a".repeat(64)))
        dao.upsertMasterLink(MasterLinkEntity(OperationsMasterSync.SPECIES, 1, 11, "a".repeat(64)))
        gateway = FakeGateway()
        masters = OperationsMasterSync(db, gateway)
        sync = InventorySync(db, gateway, masters, settings, directory)
    }
    @After fun tearDown() { db.close(); directory.deleteRecursively() }
    private fun stock(farm: Int = 2, size: String = "1.0m", quantity: Int = 30) =
        FarmInventoryEntity(farm, 1, "상묘", "1년생", size, "실생", quantity, 1000, 2000)
    private fun queue(row: FarmInventoryEntity, error: String = ""): Int {
        dao.upsertInventory(row)
        return dao.insertPendingSync(PendingSyncEntity(syncType = PendingSyncType.INVENTORY_SAVE,
            payloadJson = gson.toJson(InventoryMutation.from(row)), lastError = error)).toInt()
    }
    private fun local(row: FarmInventoryEntity) = dao.findInventory(row.farmId, row.speciesId,
        row.quality, row.age, row.size, row.plantType)

    @Test fun unlinkedOperationDoesNotBlockAnotherSpecification() = runBlocking {
        val blocked = queue(stock(farm = 1))
        queue(stock(size = "2.0m"))
        val result = sync.sync()
        assertFalse(result.success)
        assertEquals(listOf("2.0m"), gateway.sent.map { it.getAsJsonObject("inventory")["size"].asString })
        assertEquals(blocked, dao.getAllPendingSync().single().syncId)
        assertTrue(dao.getAllPendingSync().single().lastError.startsWith("UNLINKED:"))
    }

    @Test fun laterOperationOnTheSameUnlinkedSpecificationStaysQueued() = runBlocking {
        val first = queue(stock(farm = 1, quantity = 30))
        val second = queue(stock(farm = 1, quantity = 40))
        queue(stock(size = "2.0m"))
        sync.sync()
        assertEquals(listOf(first, second), dao.getAllPendingSync().map { it.syncId })
        assertEquals(1, gateway.sent.size)
        assertEquals(40, requireNotNull(local(stock(farm = 1))).quantity)
    }

    @Test fun blockedOnlyPullProtectsPendingKeysAndDoesNotEnqueueThemAgain() = runBlocking {
        val rejected = stock(quantity = 30)
        val unlinked = stock(farm = 1, size = "2.0m", quantity = 12)
        queue(rejected, "REJECTED: 서버 거절")
        queue(unlinked, "UNLINKED: 연결 없음")
        gateway.put(stock(quantity = 5))
        gateway.put(stock(size = "3.0m", quantity = 70))
        val result = sync.sync()
        assertFalse(result.success)
        assertEquals(1, result.received)
        assertEquals(30, requireNotNull(local(rejected)).quantity)
        assertEquals(12, requireNotNull(local(unlinked)).quantity)
        assertEquals(70, requireNotNull(local(stock(size = "3.0m"))).quantity)
        assertEquals(2, dao.getAllPendingSync().size)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun invalidBlocksOnlyItsKeyAndIsNotAutomaticallyResent() = runBlocking {
        val first = queue(stock(quantity = 30))
        val second = queue(stock(quantity = 40))
        queue(stock(size = "2.0m"))
        gateway.invalidSizes += "1.0m"
        sync.sync()
        sync.sync()
        assertEquals(listOf(first, second), dao.getAllPendingSync().map { it.syncId })
        assertEquals(listOf("1.0m", "2.0m"), gateway.sent.map { it.getAsJsonObject("inventory")["size"].asString })
        assertTrue(dao.getAllPendingSync().first().lastError.startsWith("REJECTED:"))
        assertEquals(40, requireNotNull(local(stock())).quantity)
    }

    @Test fun busyStillStopsBeforeLaterOperationsAndBeforePull() = runBlocking {
        queue(stock())
        queue(stock(size = "2.0m"))
        gateway.busySizes += "1.0m"
        gateway.put(stock(size = "3.0m", quantity = 70))
        val result = sync.sync()
        assertFalse(result.success)
        assertEquals(1, gateway.sent.size)
        assertEquals(2, dao.getAllPendingSync().size)
        assertNull(local(stock(size = "3.0m")))
    }

    @Test fun rejectedServerChoiceNeverDeletesWhenBackupCannotBeWritten() = runBlocking {
        val row = stock()
        queue(row, "REJECTED: 서버 거절")
        val before = dao.getAllPendingSync()
        val notDirectory = File(directory, "not-a-folder").apply { writeText("keep") }
        val failing = InventorySync(db, gateway, masters, settings, notDirectory)
        val result = failing.useServerForRejected(failing.blockedInventoryWork().single())
        assertFalse(result.success)
        assertEquals(before, dao.getAllPendingSync())
        assertEquals(row, local(row))
    }

    @Test fun rejectedServerChoiceBacksUpTheWholeKeyBeforeReplacingIt() = runBlocking {
        queue(stock(quantity = 30), "REJECTED: 서버 거절")
        queue(stock(quantity = 40))
        val before = dao.getAllPendingSync()
        gateway.put(stock(quantity = 5))
        val result = sync.useServerForRejected(sync.blockedInventoryWork().single())
        assertTrue(result.success)
        assertTrue(dao.getAllPendingSync().isEmpty())
        assertEquals(5, requireNotNull(local(stock())).quantity)
        val backup = File(directory, "sync_backup").listFiles()!!.single()
        assertTrue(backup.name.startsWith("inventory_rejected_"))
        val saved = gson.fromJson(backup.readText(), JsonObject::class.java)
        assertEquals(before.size, saved.getAsJsonArray("pending_sync").size())
        assertEquals(40, saved.getAsJsonArray("farm_inventory")[0].asJsonObject["quantity"].asInt)
    }

    private class FakeGateway : OperationsMasterGateway {
        override var downloaded = true
        val sent = mutableListOf<JsonObject>()
        val invalidSizes = mutableSetOf<String>()
        val busySizes = mutableSetOf<String>()
        private val gson = Gson()
        private val rows = linkedMapOf<InventoryKey, JsonObject>()
        private fun record(fields: Map<*, *>): JsonObject = gson.toJsonTree(fields).asJsonObject.apply {
            addProperty("updatedAt", "2026-10-27 10:00:00"); addProperty("version", "b".repeat(64))
        }
        private fun key(row: JsonObject) = InventoryKey(row["farmId"].asInt, row["speciesId"].asInt,
            row["quality"].asString, row["age"].asString, row["size"].asString, row["plantType"].asString)
        fun put(stock: FarmInventoryEntity) {
            val row = record(InventoryMutation.from(stock).body(22, 11)); rows[key(row)] = row
        }
        override suspend fun request(action: String, fields: Map<String, Any>): JsonObject = when (action) {
            "list_farms" -> gson.toJsonTree(mapOf("farms" to listOf(mapOf("farmId" to 22,
                "farmName" to "연결 농원", "version" to "a".repeat(64))))).asJsonObject
            "list_species" -> gson.toJsonTree(mapOf("species" to listOf(mapOf("speciesId" to 11,
                "speciesName" to "시험 묘목", "isActive" to 1, "version" to "a".repeat(64))))).asJsonObject
            "list_inventory" -> gson.toJsonTree(mapOf("inventoryVersioning" to 1, "inventory" to rows.values.toList())).asJsonObject
            "save_inventory" -> {
                val body = gson.toJsonTree(fields).asJsonObject; sent += body
                val inventory = body.getAsJsonObject("inventory")
                if (inventory["size"].asString in invalidSizes) throw MasterServerException("INVALID")
                if (inventory["size"].asString in busySizes) throw MasterServerException("BUSY")
                val saved = record(fields["inventory"] as Map<*, *>); rows[key(saved)] = saved
                JsonObject().apply { addProperty("saved", true); add("operationId", body["operationId"]); add("inventoryRecord", saved) }
            }
            else -> error("Unexpected fake action: $action")
        }
    }
}
