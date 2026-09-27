package com.example.namufarm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

object PendingSyncType {
    const val INVENTORY = "inventory"
    const val ORDER = "order"
    const val CANCEL_ORDER = "cancel_order"
    const val DELIVERY = "delivery"


    const val FARM_SAVE = "farm_save"
    const val FARM_DELETE = "farm_delete"
    const val SPECIES_SAVE = "species_save"
    const val SPECIES_DELETE = "species_delete"
}

@Entity(tableName = "pending_sync")
data class PendingSyncEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "sync_id")
    val syncId: Int? = null,

    @ColumnInfo(name = "sync_type")
    val syncType: String,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String = ""
)

data class PendingInventorySyncPayload(
    val action: String,
    val entity: FarmInventoryEntity,
    val farmName: String,
    val speciesName: String
)

data class PendingOrderSyncPayload(
    val order: OrderEntity,
    val items: List<com.example.namufarm.data.remote.SheetOrderItemDto>
)

data class PendingCancelOrderSyncPayload(
    val orderNumber: String
)

data class PendingDeliverySyncPayload(
    val orderNumber: String,
    val receiverName: String,
    val zipNo: String,
    val roadAddress: String,
    val detailAddress: String,
    val note: String
)

data class PendingFarmSyncPayload(val farm: FarmEntity)
data class PendingDeleteFarmSyncPayload(val farmId: Int)
data class PendingSpeciesSyncPayload(val species: SpeciesEntity)
data class PendingDeleteSpeciesSyncPayload(val speciesId: Int)