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
        private fun migrateLegacyLocation(context: Context) {
            val legacy = File(context.noBackupFilesDir, "NamuFarmInquiries.db")
            val target = context.getDatabasePath("NamuFarmInquiries.db")
            if (target.exists() || !legacy.exists()) return
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
                // Keep the legacy no-backup source. Only the verified standard-location copy is used from now on.
            } finally { temp.delete() }
        }
        @Synchronized fun closeForRestore() {
            instance?.close()
            instance = null
        }
        fun get(context: Context): CustomerInquiryDatabase = instance ?: synchronized(this) {
            instance ?: run {
                migrateLegacyLocation(context.applicationContext)
                Room.databaseBuilder(context.applicationContext, CustomerInquiryDatabase::class.java,
                    "NamuFarmInquiries.db").build().also { instance = it }
            }
        }
    }
}
