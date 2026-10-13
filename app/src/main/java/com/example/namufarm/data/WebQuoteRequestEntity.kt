package com.example.namufarm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An inbound enquiry, never an OrderLine or a stock reservation. */
@Entity(tableName = "web_quote_request", indices = [Index(value = ["sequence"], unique = true)])
data class WebQuoteRequestEntity(
    @PrimaryKey val requestId: String,
    val sequence: Long,
    val createdAt: String,
    val customerName: String,
    val customerPhone: String,
    val region: String,
    val itemsJson: String,
    val message: String,
    val readAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val customerEmail: String = "",
    @ColumnInfo(defaultValue = "0") val formalQuoteRequested: Boolean = false,
    @ColumnInfo(defaultValue = "''") val recipientName: String = "",
    @ColumnInfo(defaultValue = "'sms'") val replyChannel: String = "sms",
    @ColumnInfo(defaultValue = "'신규'") val status: String = "신규",
    @ColumnInfo(defaultValue = "''") val receiptNumber: String = ""
)

data class WebQuoteItem(val species: String, val specification: String, val quantity: Int)
