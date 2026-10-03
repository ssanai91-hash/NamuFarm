package com.example.namufarm.data.local

import com.example.namufarm.data.WebQuoteRequestEntity
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
import kotlinx.coroutines.sync.withLock

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

    suspend fun <T> serializedSync(block: suspend () -> T): T =
        database.syncMutex.withLock { block() }

    fun requireNoPending() {
        check(dao.getAllPendingSync().isEmpty()) {
            "전송 대기 중인 로컬 변경이 있어 Pull을 중단했습니다. 다시 동기화해주세요."
        }
    }

    fun getWebRequests() = dao.getWebRequests()
    fun webRequestCursor() = dao.webRequestCursor()
    fun markWebRequestRead(id: String, time: Long) = dao.markWebRequestRead(id, time)
    fun insertWebRequests(requests: List<WebQuoteRequestEntity>) {
        requests.forEach { incoming ->
            val existing = dao.getWebRequest(incoming.requestId)
            if (existing == null) dao.insertWebRequest(incoming)
            else check(existing.copy(readAt = null) == incoming.copy(readAt = null)) {
                "이미 받은 요청의 내용이 변경되었습니다. 관리자 확인이 필요합니다."
            }
        }
    }

    fun getAllFarms(): List<FarmEntity> = dao.getAllFarms()
    fun getAllSpecies(): List<SpeciesEntity> = dao.getAllSpecies()
    fun getAllSpeciesIncludingInactive(): List<SpeciesEntity> = dao.getAllSpeciesIncludingInactive()
    fun getAllInventoryEntities(): List<FarmInventoryEntity> = dao.getAllInventoryEntities()
    fun getOpenCommerceSummaries(): List<String> = dao.getOpenCommerceSummaries()
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

    fun getFarmByName(farmName: String): FarmEntity? {
        val matches = getAllFarms().filter { it.farmName.trim() == farmName.trim() }
        check(matches.size <= 1) { "중복 농원명을 먼저 확인해주세요: $farmName" }
        return matches.singleOrNull()
    }
    fun getSpeciesByName(speciesName: String): SpeciesEntity? {
        val matches = getAllSpeciesIncludingInactive().filter { it.speciesName.trim() == speciesName.trim() }
        check(matches.size <= 1) { "중복 묘목명을 먼저 확인해주세요: $speciesName" }
        return matches.singleOrNull()
    }

    fun upsertFarm(farm: FarmEntity): Long = dao.upsertFarm(farm)
    fun deleteFarmById(farmId: Int) = dao.deleteFarmById(farmId)
    fun deleteInventoryByFarmId(farmId: Int) = dao.deleteInventoryByFarmId(farmId)

    fun upsertSpecies(species: SpeciesEntity): Long = dao.upsertSpecies(species)
    fun deactivateSpecies(speciesId: Int) = dao.deactivateSpecies(speciesId)

    fun insertQuote(quote: QuoteEntity) = dao.insertQuote(quote)
    fun getQuoteById(quoteId: Int): QuoteEntity? = dao.getQuoteById(quoteId)
    fun deleteQuote(quote: QuoteEntity) = dao.deleteQuote(quote)
    fun getLastQuoteId(): Int? = dao.getLastQuoteId()
    fun getQuotesForDate(datePrefix: String): List<QuoteEntity> =
        dao.getQuotesForDate(datePrefix)

    fun insertOrder(order: OrderEntity) = dao.insertOrder(order)
    fun upsertOrder(order: OrderEntity) = dao.upsertOrder(order)
    fun getOrderByNumber(orderNumber: String): OrderEntity? {
        check(dao.countOrdersByNumber(orderNumber) <= 1) {
            "중복 주문번호를 먼저 확인해주세요: $orderNumber"
        }
        return dao.getOrderByNumber(orderNumber)
    }

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
    fun getPendingSyncById(syncId: Int): PendingSyncEntity? = dao.getPendingSyncById(syncId)
    fun deletePendingSync(syncId: Int) = dao.deletePendingSync(syncId)
    fun markPendingSyncFailed(syncId: Int, error: String) =
        dao.markPendingSyncFailed(syncId, error)
}
