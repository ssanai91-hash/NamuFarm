package com.example.namufarm.data

import com.example.namufarm.data.remote.SheetOrderItemDto

data class PendingSyncProcessResult(
    val success: Boolean,
    val errorMessage: String? = null
)

data class SyncResult(
    val total: Int,
    val upserted: Int,
    val skipped: Int,
    val insertedFarms: Int,
    val insertedSpecies: Int,
    val errorMessage: String? = null
)

/** Inventory/order transport will be adapted separately; never post legacy bodies to a placeholder. */
class SyncManager(private val dao: ManagementDao, val masters: OperationsMasterSync) {
    suspend fun processPendingSync(item: PendingSyncEntity) = PendingSyncProcessResult(false,
        "농원·묘목 외 전송은 아직 연결되지 않았습니다. 기기 데이터와 대기 기록을 유지합니다.")
    suspend fun syncFarmsWithGoogleSheet(): Int =
        if (masters.sync(OperationsMasterSync.FARM).success) dao.getAllFarms().size else -1
    suspend fun syncSpeciesWithGoogleSheet(): Int =
        if (masters.sync(OperationsMasterSync.SPECIES).success) dao.getAllSpecies().size else -1
    suspend fun syncWithGoogleSheet() = SyncResult(0, 0, 0, 0, 0, "재고는 현재 기기에 보관됩니다.")
    suspend fun syncOrdersWithGoogleSheet(): Int = -1
    suspend fun uploadToSheet(action: String, entity: FarmInventoryEntity,
        farmName: String, speciesName: String): Boolean = false
    suspend fun uploadOrderToSheet(orderEntity: OrderEntity,
        itemsToDeduct: List<SheetOrderItemDto>): Boolean = false
    suspend fun cancelOrderOnSheet(orderNumber: String): Boolean = false
    suspend fun updateOrderDeliveryOnSheet(orderNumber: String, receiverName: String,
        zipNo: String, roadAddress: String, detailAddress: String, note: String): Boolean = false
    suspend fun updateOrderStatusOnSheet(orderNumber: String, status: String): Boolean = false
    suspend fun deleteOrderOnSheet(orderNumber: String): Boolean = false
}
