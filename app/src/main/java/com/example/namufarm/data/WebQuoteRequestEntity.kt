package com.example.namufarm.data

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
    val readAt: Long? = null
)

data class WebQuoteItem(val species: String, val specification: String, val quantity: Int)
