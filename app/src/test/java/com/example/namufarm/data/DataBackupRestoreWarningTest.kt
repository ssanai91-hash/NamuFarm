package com.example.namufarm.data

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.1.21 preview-only warning cases, written only. Not executed for this change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DataBackupRestoreWarningTest {
    private lateinit var directory: File
    private lateinit var file: File
    @Before fun setUp() {
        directory = Files.createTempDirectory("restore-warning").toFile()
        file = File(directory, "NamuFarm.db")
    }
    @After fun tearDown() { directory.deleteRecursively() }
    @Test fun countsOnlyTheFourMasterTypesWithoutChangingTheBackup() {
        SQLiteDatabase.openOrCreateDatabase(file.absolutePath, null).use { db ->
            db.execSQL("CREATE TABLE pending_sync (sync_type TEXT NOT NULL)")
            listOf(PendingSyncType.FARM_SAVE, PendingSyncType.FARM_DELETE, PendingSyncType.SPECIES_SAVE,
                PendingSyncType.SPECIES_DELETE, PendingSyncType.INVENTORY_SAVE, PendingSyncType.ORDER).forEach {
                db.execSQL("INSERT INTO pending_sync VALUES (?)", arrayOf(it))
            }
        }
        val before = file.readBytes()
        assertEquals(4L, restoredMasterPendingCount(file))
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun oldBackupWithoutPendingTableHasNoMasterWarning() {
        SQLiteDatabase.openOrCreateDatabase(file.absolutePath, null).use {
            it.execSQL("CREATE TABLE preserved (value TEXT)")
        }
        val before = file.readBytes()
        assertEquals(0L, restoredMasterPendingCount(file))
        assertArrayEquals(before, file.readBytes())
    }
}
