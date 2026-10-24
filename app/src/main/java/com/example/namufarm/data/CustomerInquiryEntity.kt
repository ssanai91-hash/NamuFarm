package com.example.namufarm.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy

@Entity(tableName = "customer_inquiry")
data class CustomerInquiryEntity(
    @PrimaryKey val requestId: String,
    val revision: Long,
    val body: String,
    val localExpiry: Boolean = false
)

@Entity(tableName = "customer_inquiry_draft")
data class CustomerInquiryDraftEntity(
    @PrimaryKey val requestId: String,
    val text: String,
    val channel: String,
    val baseRevision: Long
)

@Entity(tableName = "customer_inquiry_operation")
data class CustomerInquiryOperationEntity(
    @PrimaryKey val requestId: String,
    val action: String,
    val payload: String
)

@Entity(tableName = "customer_inquiry_sync")
data class CustomerInquirySyncEntity(
    @PrimaryKey val name: String = "inquiries",
    val serverUrl: String,
    val cursor: Long
)

@Dao
interface CustomerInquiryDao {
    @Query("SELECT * FROM customer_inquiry")
    fun records(): List<CustomerInquiryEntity>
    @Query("SELECT * FROM customer_inquiry WHERE requestId=:id")
    fun record(id: String): CustomerInquiryEntity?
    @Query("SELECT * FROM customer_inquiry_draft")
    fun drafts(): List<CustomerInquiryDraftEntity>
    @Query("SELECT * FROM customer_inquiry_draft WHERE requestId=:id")
    fun draft(id: String): CustomerInquiryDraftEntity?
    @Query("SELECT * FROM customer_inquiry_operation")
    fun operations(): List<CustomerInquiryOperationEntity>
    @Query("SELECT * FROM customer_inquiry_operation WHERE requestId=:id")
    fun operation(id: String): CustomerInquiryOperationEntity?
    @Query("SELECT * FROM customer_inquiry_sync WHERE name='inquiries'")
    fun syncState(): CustomerInquirySyncEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(value: CustomerInquiryEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(value: CustomerInquiryDraftEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(value: CustomerInquiryOperationEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(value: CustomerInquirySyncEntity): Long
    @Query("DELETE FROM customer_inquiry_draft WHERE requestId=:id")
    fun deleteDraft(id: String): Int
    @Query("DELETE FROM customer_inquiry_operation WHERE requestId=:id")
    fun deleteOperation(id: String): Int
}
