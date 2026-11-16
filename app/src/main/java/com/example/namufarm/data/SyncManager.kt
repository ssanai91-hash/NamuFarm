package com.example.namufarm.data


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

/** Current order protocol is separate from inventory; legacy payloads require explicit linking. */
class SyncManager(private val dao: ManagementDao, val masters: OperationsMasterSync,
    val inventory: InventorySync = InventorySync(masters.database, masters.client, masters,
        object : InventoryLinkSettings { override var inventoryLinked = false }),
    val orders: OrderSync? = null) {
    suspend fun processPendingSync(item: PendingSyncEntity): PendingSyncProcessResult {
        if (item.syncType != PendingSyncType.ORDER_V2) return PendingSyncProcessResult(false, "기존 주문은 서버 연결을 먼저 확인해주세요.")
        val result = orders?.sync() ?: return PendingSyncProcessResult(false, "주문 연결이 준비되지 않았습니다.")
        return PendingSyncProcessResult(result.success, result.message)
    }
    suspend fun syncFarmsWithGoogleSheet(): Int =
        if (masters.sync(OperationsMasterSync.FARM).success) dao.getAllFarms().size else -1
    suspend fun syncSpeciesWithGoogleSheet(): Int =
        if (masters.sync(OperationsMasterSync.SPECIES).success) dao.getAllSpecies().size else -1
    suspend fun syncWithGoogleSheet() = SyncResult(0, 0, 0, 0, 0, "재고는 현재 기기에 보관됩니다.")
    suspend fun syncOrdersWithGoogleSheet(): Int = if (orders?.sync()?.success == true) dao.getTodayOrders().size else -1
    suspend fun uploadToSheet(action: String, entity: FarmInventoryEntity,
        farmName: String, speciesName: String): Boolean = false
}
