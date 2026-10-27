package com.example.namufarm.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Inquiries never migrate or replace the original farm/order or quote databases. */
@Database(entities = [CustomerInquiryEntity::class, CustomerInquiryDraftEntity::class,
    CustomerInquiryOperationEntity::class, CustomerInquirySyncEntity::class],
    version = 1, exportSchema = false)
abstract class CustomerInquiryDatabase : RoomDatabase() {
    abstract fun customerInquiryDao(): CustomerInquiryDao

    companion object {
        @Volatile private var instance: CustomerInquiryDatabase? = null
        private fun migrateLegacyLocation(context: Context): Boolean {
            val legacy = File(context.noBackupFilesDir, "NamuFarmInquiries.db")
            val target = context.getDatabasePath("NamuFarmInquiries.db")
            if (target.exists() || !legacy.exists()) return false
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            val temp = File(target.parentFile, ".inquiry-migration-${UUID.randomUUID()}.db")
            val old = SQLiteDatabase.openDatabase(legacy.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                old.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use {
                    check(it.moveToFirst() && it.getInt(0) == 0 && (it.getInt(1) < 0 || it.getInt(2) >= it.getInt(1))) {
                        "기존 문의 자료 저장이 끝나지 않아 원본 위치를 유지합니다. 앱을 다시 열어주세요."
                    }
                }
            } finally { old.close() }
            try {
                legacy.inputStream().use { input -> FileOutputStream(temp).use { out -> input.copyTo(out); out.fd.sync() } }
                val copied = SQLiteDatabase.openDatabase(temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    check(copied.version == 1)
                    copied.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
                } finally { copied.close() }
                check(!target.exists() && temp.renameTo(target)) { "문의 자료 위치를 변경하지 못했습니다. 기존 파일은 보존했습니다." }
            } finally { temp.delete() }
            return true
        }
        private fun cleanupLegacyLocation(context: Context, room: CustomerInquiryDatabase, movedThisRun: Boolean) {
            // Cleanup is best effort. An uncertain or unreadable copy is kept for a later launch.
            runCatching {
                val legacy = File(context.noBackupFilesDir, "NamuFarmInquiries.db")
                if (!legacy.exists()) return@runCatching
                if (!movedThisRun) {
                    val current = room.openHelper.writableDatabase
                    val healthy = current.query("PRAGMA integrity_check").use {
                        it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()
                    }
                    if (!healthy) return@runCatching
                    val currentCount = current.query("SELECT COUNT(*) FROM customer_inquiry").use {
                        check(it.moveToFirst()); it.getLong(0)
                    }
                    val old = SQLiteDatabase.openDatabase(legacy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    val oldCount = try {
                        old.rawQuery("SELECT COUNT(*) FROM customer_inquiry", null).use {
                            check(it.moveToFirst()); it.getLong(0)
                        }
                    } finally { old.close() }
                    if (currentCount < oldCount) return@runCatching
                }
                // Leave the base file until every sidecar is gone, so failures can be retried next launch.
                for (suffix in listOf("-wal", "-shm", "-journal", "")) {
                    val file = File(legacy.path + suffix)
                    check(!file.exists() || file.delete())
                }
            }
        }
        @Synchronized fun closeForRestore() {
            instance?.close()
            instance = null
        }
        fun get(context: Context): CustomerInquiryDatabase = instance ?: synchronized(this) {
            instance ?: run {
                val moved = migrateLegacyLocation(context.applicationContext)
                Room.databaseBuilder(context.applicationContext, CustomerInquiryDatabase::class.java,
                    "NamuFarmInquiries.db").build().also { room ->
                    try {
                        room.openHelper.writableDatabase // Open/validate Room before considering the old copy.
                        cleanupLegacyLocation(context.applicationContext, room, moved)
                        instance = room
                    } catch (e: Exception) { room.close(); throw e }
                }
            }
        }
    }
}
