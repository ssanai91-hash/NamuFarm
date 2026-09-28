package com.example.namufarm.data.local

import androidx.room.withTransaction
import com.example.namufarm.data.AppDatabase
import com.example.namufarm.data.FarmEntity
import com.example.namufarm.data.FarmInventoryEntity
import com.example.namufarm.data.InventoryDisplayRow
import com.example.namufarm.data.ManagementDao
import com.example.namufarm.data.OrderEntity
import com.example.namufarm.data.PendingSyncEntity
import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.data.SellingDisplayRow
import com.example.namufarm.data.SpeciesEntity

/**
 * Room 접근을 한 곳으로 모은 로컬 데이터 소스.
 *
 * ViewModel/SyncManager가 DAO와 DB 트랜잭션을 직접 만지지 않도록 한다.
 */
class LocalDataSource(
    private val database: AppDatabase,
    private val dao: ManagementDao
) {
    suspend fun <T> transaction(block: LocalDataSource.() -> T): T =
        database.withTransaction { block(this@LocalDataSource) }

    fun getAllFarms(): List<FarmEntity> = dao.getAllFarms()
    fun getAllSpecies(): List<SpeciesEntity> = dao.getAllSpecies()
    fun getAllSellingInventory(): List<SellingDisplayRow> = dao.getAllSellingInventory()

    fun getInventoryRows(farmId: Int, speciesId: Int): List<InventoryDisplayRow> =
        dao.getInventoryRows(farmId, speciesId)

    fun getInventoryEntity(
        farmId: Int,
        speciesId: Int,
        quality: String,
        age: String,
        size: String,
        plantType: String
    ): FarmInventoryEntity? =
        dao.getInventoryEntity(farmId, speciesId, quality, age, size, plantType)

    fun upsertInventory(entity: FarmInventoryEntity) = dao.upsertInventory(entity)

    fun deleteInventory(
        farmId: Int,
        speciesId: Int,
        quality: String,
        age: String,
        size: String,
        plantType: String
    ) = dao.deleteInventory(farmId, speciesId, quality, age, size, plantType)

    fun clearAllInventory() = dao.clearAllInventory()

    fun getFarmByName(farmName: String): FarmEntity? = dao.getFarmByName(farmName)
    fun getSpeciesByName(speciesName: String): SpeciesEntity? = dao.getSpeciesByName(speciesName)

    fun upsertFarm(farm: FarmEntity): Long = dao.upsertFarm(farm)
    fun deleteFarmById(farmId: Int) = dao.deleteFarmById(farmId)
    fun deleteInventoryByFarmId(farmId: Int) = dao.deleteInventoryByFarmId(farmId)

    fun upsertSpecies(species: SpeciesEntity): Long = dao.upsertSpecies(species)
    fun deactivateSpecies(speciesId: Int) = dao.deactivateSpecies(speciesId)

    fun insertQuote(quote: QuoteEntity) = dao.insertQuote(quote)
    fun deleteQuote(quote: QuoteEntity) = dao.deleteQuote(quote)
    fun getLastQuoteId(): Int? = dao.getLastQuoteId()
    fun getQuotesForDate(datePrefix: String): List<QuoteEntity> =
        dao.getQuotesForDate(datePrefix)

    fun insertOrder(order: OrderEntity) = dao.insertOrder(order)
    fun upsertOrder(order: OrderEntity) = dao.upsertOrder(order)
    fun getOrderByNumber(orderNumber: String): OrderEntity? =
        dao.getOrderByNumber(orderNumber)

    fun getOrdersForDate(datePrefix: String): List<OrderEntity> =
        dao.getOrdersForDate(datePrefix)

    fun updateOrderStatus(orderNumber: String, status: String) =
        dao.updateOrderStatus(orderNumber, status)

    fun updateOrderDeliveryInfo(
        orderNumber: String,
        receiverName: String,
        zipNo: String,
        roadAddress: String,
        detailAddress: String,
        note: String
    ) = dao.updateOrderDeliveryInfo(
        orderNumber,
        receiverName,
        zipNo,
        roadAddress,
        detailAddress,
        note
    )

    fun insertPendingSync(item: PendingSyncEntity): Int =
        dao.insertPendingSync(item).toInt()

    fun getAllPendingSync(): List<PendingSyncEntity> = dao.getAllPendingSync()
    fun deletePendingSync(syncId: Int) = dao.deletePendingSync(syncId)
    fun markPendingSyncFailed(syncId: Int, error: String) =
        dao.markPendingSyncFailed(syncId, error)
}
