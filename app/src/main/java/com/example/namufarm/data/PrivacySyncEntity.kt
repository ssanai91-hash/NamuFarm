package com.example.namufarm.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "privacy_sync")
data class PrivacySyncEntity(@PrimaryKey val name: String, val cursor: Long)
