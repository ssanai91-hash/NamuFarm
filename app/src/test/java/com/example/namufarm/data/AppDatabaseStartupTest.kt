package com.example.namufarm.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Exercise the production file-backed factory with Android's native SQLite behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AppDatabaseStartupTest {
    @Before fun prepare() {
        RuntimeEnvironment.getApplication().deleteDatabase("NamuFarm.db")
    }

    @After fun cleanUp() {
        RuntimeEnvironment.getApplication().deleteDatabase("NamuFarm.db")
    }

    @Test fun freshDatabaseAllowsFirstInboxReadWithSecureDeletion() = runBlocking(Dispatchers.IO) {
        val db = AppDatabase.createDatabase(RuntimeEnvironment.getApplication())
        try {
            assertTrue(db.managementDao().getWebRequests().isEmpty())
            assertSecureDeletionOnWriteConnection(db)
        } finally {
            db.close()
        }
    }

    @Test fun reopeningKeepsOfflineChangesAndReappliesSecureDeletion() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val pending = PendingSyncEntity(
            syncType = PendingSyncType.INVENTORY,
            payloadJson = "{\"regression\":\"offline-change\"}",
            createdAt = 42,
            retryCount = 2,
            lastError = "offline"
        )
        val first = AppDatabase.createDatabase(context)
        val id = try {
            first.managementDao().insertPendingSync(pending)
        } finally {
            first.close()
        }

        val reopened = AppDatabase.createDatabase(context)
        try {
            assertEquals(pending.copy(syncId = id.toInt()), reopened.managementDao().getAllPendingSync().single())
            assertTrue(reopened.managementDao().getWebRequests().isEmpty())
            assertSecureDeletionOnWriteConnection(reopened)
        } finally {
            reopened.close()
        }
    }

    private fun assertSecureDeletionOnWriteConnection(db: AppDatabase) {
        db.runInTransaction {
            db.openHelper.writableDatabase.query("PRAGMA secure_delete").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }
}
