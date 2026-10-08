package com.example.namufarm.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "quote_contact")
data class QuoteContactEntity(@PrimaryKey val quoteNumber: String, val recipientJson: String)

/** Immutable issued document, retained independently of a quote becoming an order. */
@Entity(tableName = "quote_document", indices = [Index(value = ["quoteNumber"], unique = true), Index(value = ["issuedNumber"], unique = true)])
data class QuoteDocumentEntity(@PrimaryKey val documentId: String, val quoteNumber: String, val issuedNumber: String, val issuedAt: Long, val snapshotJson: String)
