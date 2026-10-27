package com.example.namufarm.data

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "inventory_links", primaryKeys = ["farm_id", "species_id", "quality", "age", "size", "plant_type"])
data class InventoryLinkEntity(
    @ColumnInfo(name = "farm_id") val farmId: Int,
    @ColumnInfo(name = "species_id") val speciesId: Int,
    val quality: String,
    val age: String,
    val size: String,
    @ColumnInfo(name = "plant_type") val plantType: String,
    val version: String
)
