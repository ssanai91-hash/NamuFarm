package com.example.namufarm.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Preserve unreadable legacy envelopes before advancing the download cursor. */
@Entity(tableName = "deferred_web_request", indices = [Index(value = ["sequence"], unique = true)])
data class DeferredWebRequestEntity(
    @PrimaryKey val requestId: String,
    val sequence: Long,
    val createdAt: String,
    val envelopeJson: String
)
