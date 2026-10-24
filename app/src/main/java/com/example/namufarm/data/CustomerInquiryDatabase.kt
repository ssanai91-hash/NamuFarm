package com.example.namufarm.data

import android.content.Context
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
        fun get(context: Context): CustomerInquiryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                CustomerInquiryDatabase::class.java,
                context.noBackupFilesDir.resolve("NamuFarmInquiries.db").absolutePath)
                .build().also { instance = it }
        }
    }
}
