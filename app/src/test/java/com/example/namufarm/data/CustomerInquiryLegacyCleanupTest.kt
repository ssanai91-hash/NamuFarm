package com.example.namufarm.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.1.21 privacy cleanup cases, written only. Not executed for this change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CustomerInquiryLegacyCleanupTest {
    private lateinit var context: Context
    private lateinit var legacy: File
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        legacy = File(context.noBackupFilesDir, "NamuFarmInquiries.db")
        clearFiles()
        CustomerInquiryDatabase.get(context).openHelper.writableDatabase
        CustomerInquiryDatabase.closeForRestore()
    }
    @After fun tearDown() { clearFiles() }
    private fun clearFiles() {
        CustomerInquiryDatabase.closeForRestore()
        context.deleteDatabase("NamuFarmInquiries.db")
        listOf("", "-wal", "-shm", "-journal").forEach { File(legacy.path + it).delete() }
    }
    private fun makeOldCopy(rows: Int) {
        SQLiteDatabase.openOrCreateDatabase(legacy.absolutePath, null).use { old ->
            old.execSQL("CREATE TABLE customer_inquiry (sample INTEGER NOT NULL)")
            repeat(rows) { old.execSQL("INSERT INTO customer_inquiry VALUES (?)", arrayOf(it)) }
            old.version = 1
        }
    }
    @Test fun preservesOldCopyWhenItHasMoreInquiryRows() {
        makeOldCopy(2)
        CustomerInquiryDatabase.get(context)
        assertTrue(legacy.isFile)
    }
    @Test fun removesOldCopyWhenNewDatabaseIsHealthyAndHasAtLeastAsManyRows() {
        makeOldCopy(0)
        val unrelated = File(context.noBackupFilesDir, "keep.txt").apply { writeText("keep") }
        try {
            CustomerInquiryDatabase.get(context)
            assertFalse(legacy.exists())
            assertEquals("keep", unrelated.readText())
        } finally { unrelated.delete() }
    }
    @Test fun removesTheVerifiedCopyAfterMovingItInThisLaunch() {
        context.getDatabasePath("NamuFarmInquiries.db").copyTo(legacy)
        context.deleteDatabase("NamuFarmInquiries.db")
        val room = CustomerInquiryDatabase.get(context)
        assertTrue(room.isOpen)
        assertTrue(context.getDatabasePath("NamuFarmInquiries.db").isFile)
        assertFalse(legacy.exists())
    }
    @Test fun unreadableOldCopyDoesNotPreventOpeningTheNewDatabase() {
        legacy.writeText("unreadable old copy")
        val room = CustomerInquiryDatabase.get(context)
        assertTrue(room.isOpen)
        assertEquals("unreadable old copy", legacy.readText())
    }
}
