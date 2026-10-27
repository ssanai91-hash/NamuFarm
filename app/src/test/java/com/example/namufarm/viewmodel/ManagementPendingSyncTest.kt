package com.example.namufarm.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.namufarm.data.*
import com.example.namufarm.data.remote.OperationsMasterGateway
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Added for 1.1.17; intentionally not run as part of this change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManagementPendingSyncTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: ManagementDao
    private lateinit var viewModel: ManagementViewModel
    private lateinit var filesDir: File
    private var failBackup = false
    private var backupCalls = 0
    private var savedBackup: File? = null
    private lateinit var original: List<PendingSyncEntity>

    @Before fun setUp(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        filesDir = Files.createTempDirectory("pending-sync-test").toFile()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.managementDao()
        dao.upsertFarm(FarmEntity(1, "보존 농원"))
        dao.upsertSpecies(SpeciesEntity(1, "보존 묘목", chosung = "ㅂ"))
        dao.upsertInventory(FarmInventoryEntity(1, 1, "상묘", "1년생", "", "실생", 9, 100, 200))
        dao.insertPendingSync(PendingSyncEntity(syncType = PendingSyncType.ORDER,
            payloadJson = "{\"한글\":\"줄바꿈\\n원문\"}", createdAt = 1000L))
        dao.insertPendingSync(PendingSyncEntity(syncType = PendingSyncType.INVENTORY,
            payloadJson = "보존할 원문", createdAt = 500L, lastError = "이전 전송 오류"))
        original = dao.getAllPendingSync()
        val gateway = object : OperationsMasterGateway {
            override var downloaded = true
            override suspend fun request(action: String, fields: Map<String, Any>): JsonObject =
                error("These tests must not access the network")
        }
        viewModel = ManagementViewModel(dao, SyncManager(dao, OperationsMasterSync(db, gateway))) { _, rows ->
            backupCalls++
            assertEquals(original, dao.getAllPendingSync()) // Nothing deleted before backup.
            if (failBackup) throw IOException("Simulated disk write failure")
            PendingSyncBackup.write(filesDir, rows).also { savedBackup = it }
        }
        awaitWork()
        withTimeout(5000) { viewModel.uiState.first { it.pendingSyncCount == original.size } }
    }

    @After fun tearDown() = runBlocking {
        if (::viewModel.isInitialized) {
            val job = viewModel.viewModelScope.coroutineContext[Job]
            viewModel.viewModelScope.cancel()
            withTimeout(5000) { job?.join() }
        }
        if (::db.isInitialized) db.close()
        if (::filesDir.isInitialized) filesDir.deleteRecursively()
    }

    private suspend fun awaitWork() {
        withTimeout(5000) {
            viewModel.viewModelScope.coroutineContext[Job]!!.children
                .filterNot { it == viewModel.pendingSyncObserver }.toList().joinAll()
        }
    }

    private suspend fun confirmTwice() {
        viewModel.requestPendingSyncClear(); awaitWork()
        assertEquals(PendingSyncClearStep.FIRST_CONFIRMATION, viewModel.uiState.value.pendingSyncClearStep)
        assertEquals(original.size, viewModel.uiState.value.pendingSyncDeleteCount)
        viewModel.continuePendingSyncClear()
        assertEquals(PendingSyncClearStep.FINAL_CONFIRMATION, viewModel.uiState.value.pendingSyncClearStep)
    }

    @Test fun backupFailureNeverDeletesPendingChanges() = runBlocking {
        failBackup = true
        confirmTwice()
        viewModel.backupAndClearConfirmedPendingSync(context); awaitWork()
        assertEquals(1, backupCalls)
        assertEquals(original, dao.getAllPendingSync())
        assertEquals("백업 저장에 실패해 삭제하지 않았습니다", viewModel.uiState.value.message)
        assertEquals(PendingSyncClearStep.NONE, viewModel.uiState.value.pendingSyncClearStep)
    }

    @Test fun backupPrecedesDeletionAndReportedCountMatchesTheSavedRows() = runBlocking {
        confirmTwice()
        viewModel.backupAndClearConfirmedPendingSync(context); awaitWork()
        val file = requireNotNull(savedBackup)
        assertTrue(file.isFile)
        assertTrue(Regex("pending_sync_\\d{8}_\\d{6}\\.json").matches(file.name))
        val rows = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonArray
        assertEquals(original.size, rows.size())
        original.forEachIndexed { i, row ->
            val saved = rows[i].asJsonObject
            assertEquals(requireNotNull(row.syncId), saved["sync_id"].asInt)
            assertEquals(row.syncType, saved["sync_type"].asString)
            assertEquals(row.payloadJson, saved["payload_json"].asString)
            assertEquals(row.createdAt, saved["created_at"].asLong)
        }
        assertTrue(dao.getAllPendingSync().isEmpty())
        assertEquals("전송 대기 ${rows.size()}건 삭제 · 백업: ${file.name}", viewModel.uiState.value.message)
        withTimeout(5000) { viewModel.uiState.first { it.pendingSyncCount == 0 && it.oldestPendingAt == null } }
        assertEquals("보존 농원", dao.getAllFarms().single().farmName)
        assertEquals("보존 묘목", dao.getAllSpecies().single().speciesName)
        assertEquals(9, dao.getAllSellingInventory().single().quantity)
    }

    @Test fun sellingTitleHasNoClickOrDeleteAction() = runBlocking {
        // Source contract in the ViewModel test suite. A real device title tap is checked by the user.
        val source = listOf(
            File("src/main/java/com/example/namufarm/ui/SellingModeScreen.kt"),
            File("app/src/main/java/com/example/namufarm/ui/SellingModeScreen.kt")
        ).first { it.isFile }.readText()
        val header = source.substringAfter("NamuTopBar(\"매매\"").substringBefore("bottomBar =")
        assertTrue(source.contains("NamuTopBar(\"매매\""))
        assertFalse(header.contains("backupAndClearConfirmedPendingSync"))
        assertFalse(header.contains("clearAllPendingSync"))
        assertFalse(source.contains("forceResetSyncQueue"))
        viewModel.backupAndClearConfirmedPendingSync(context); awaitWork() // No confirmation => no deletion.
        assertEquals(0, backupCalls)
        assertEquals(original, dao.getAllPendingSync())
    }

    @Test fun firstConfirmationAndCancelDoNotPermitDeletion() = runBlocking {
        viewModel.requestPendingSyncClear(); awaitWork()
        viewModel.backupAndClearConfirmedPendingSync(context); awaitWork()
        assertEquals(0, backupCalls)
        viewModel.continuePendingSyncClear()
        viewModel.cancelPendingSyncClear()
        viewModel.backupAndClearConfirmedPendingSync(context); awaitWork()
        assertEquals(original, dao.getAllPendingSync())
        assertEquals(0, backupCalls)
    }

    @Test fun changedQueueRequiresNewConfirmationWithoutBackupOrDeletion() = runBlocking {
        confirmTwice()
        dao.insertPendingSync(PendingSyncEntity(syncType = PendingSyncType.DELIVERY, payloadJson = "new"))
        val changed = dao.getAllPendingSync()
        viewModel.backupAndClearConfirmedPendingSync(context); awaitWork()
        assertEquals(changed, dao.getAllPendingSync())
        assertEquals(0, backupCalls)
        assertTrue(viewModel.uiState.value.message!!.contains("변경"))
    }

    @Test fun pendingSummaryUsesTheOldestTimestampAndKeepsErrorsAfterToastIsCleared() {
        val state = viewModel.uiState.value
        assertEquals(original.size, state.pendingSyncCount)
        assertEquals(500L, requireNotNull(state.oldestPendingAt))
        assertEquals("이전 전송 오류", state.lastSyncError)
        viewModel.clearMessage()
        assertEquals("이전 전송 오류", viewModel.uiState.value.lastSyncError)
    }

    @Test fun backupRetentionKeepsTenAndLeavesUnrelatedFilesAlone() {
        val directory = File(filesDir, "sync_backup").apply { mkdirs() }
        val unrelated = File(directory, "keep.json").apply { writeText("keep") }
        val similarlyNamed = File(directory, "pending_sync_notes.json").apply { writeText("keep") }
        val matchingDirectory = File(directory, "pending_sync_19990101_000000.json").apply { mkdirs() }
        for (i in 1..12) {
            File(directory, "pending_sync_20000101_${i.toString().padStart(6, '0')}.json")
                .apply { writeText("[]"); setLastModified(i.toLong()) }
        }
        val backup = PendingSyncBackup.write(filesDir, original)
        val retained = directory.listFiles()!!.filter {
            it.isFile && Regex("pending_sync_\\d{8}_\\d{6}\\.json").matches(it.name)
        }
        assertEquals(10, retained.size)
        assertTrue(backup in retained)
        assertFalse(File(directory, "pending_sync_20000101_000003.json").exists())
        assertTrue(File(directory, "pending_sync_20000101_000004.json").exists())
        assertEquals("keep", unrelated.readText())
        assertEquals("keep", similarlyNamed.readText())
        assertTrue(matchingDirectory.isDirectory)
    }
}
