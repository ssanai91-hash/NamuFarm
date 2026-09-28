package com.example.namufarm.data.repository

import com.example.namufarm.data.FarmEntity
import com.example.namufarm.data.FarmInventoryEntity
import com.example.namufarm.data.InventoryDisplayRow
import com.example.namufarm.data.OrderEntity
import com.example.namufarm.data.OrderStatus
import com.example.namufarm.data.PendingCancelOrderSyncPayload
import com.example.namufarm.data.PendingDeliverySyncPayload
import com.example.namufarm.data.PendingDeleteFarmSyncPayload
import com.example.namufarm.data.PendingDeleteSpeciesSyncPayload
import com.example.namufarm.data.PendingFarmSyncPayload
import com.example.namufarm.data.PendingInventorySyncPayload
import com.example.namufarm.data.PendingOrderSyncPayload
import com.example.namufarm.data.PendingSpeciesSyncPayload
import com.example.namufarm.data.PendingSyncEntity
import com.example.namufarm.data.PendingSyncType
import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.data.SellingDisplayRow
import com.example.namufarm.data.SpeciesEntity
import com.example.namufarm.data.SyncManager
import com.example.namufarm.data.SyncResult
import com.example.namufarm.data.local.LocalDataSource
import com.example.namufarm.data.remote.JusoApi
import com.example.namufarm.data.remote.JusoItem
import com.example.namufarm.data.remote.SheetOrderItemDto
import com.example.namufarm.domain.order.OrderSummaryCodec
import com.google.gson.Gson
import com.example.namufarm.domain.order.OrderAmounts
import kotlinx.coroutines.CancellationException

data class CloudWriteResult(
    val cloudSynced: Boolean,
    val errorMessage: String? = null
)

data class FarmSaveResult(
    val farm: FarmEntity,
    val cloudSynced: Boolean
)

data class SpeciesSaveResult(
    val species: SpeciesEntity,
    val cloudSynced: Boolean
)

data class GeneralSyncResult(
    val resentSuccess: Int,
    val pendingFailureCount: Int,
    val inventoryResult: SyncResult? = null,
    val orderSyncCount: Int? = null
)

data class OrderCancelResult(
    val success: Boolean,
    val alreadyCanceled: Boolean = false,
    val restoredQuantity: Int = 0,
    val restoredLines: Int = 0,
    val cancelCloudSynced: Boolean = false,
    val inventoryCloudSynced: Int = 0,
    val inventoryPending: Int = 0,
    val errorMessage: String? = null
)

private data class InventoryPendingTask(
    val pendingId: Int,
    val entity: FarmInventoryEntity,
    val farmName: String,
    val speciesName: String
)

private data class LocalCancelWork(
    val alreadyCanceled: Boolean,
    val cancelPendingId: Int? = null,
    val restoredQuantity: Int = 0,
    val inventoryTasks: List<InventoryPendingTask> = emptyList()
)

/**
 * 앱의 업무 규칙을 담당하는 Repository.
 *
 * - LocalDataSource: Room의 로컬 상태
 * - SyncManager: Google Sheet 원격 동기화
 *
 * ViewModel은 화면 상태만 관리하고 저장/동기화 순서와 트랜잭션 규칙은 이 클래스가 책임진다.
 */
class NamuFarmRepository(
    private val local: LocalDataSource,
    private val syncManager: SyncManager,
    private val jusoApi: JusoApi
) {
    private val gson = Gson()

    fun getAllFarms(): List<FarmEntity> = local.getAllFarms()
    fun getAllSpecies(): List<SpeciesEntity> = local.getAllSpecies()
    fun getAllSellingInventory(): List<SellingDisplayRow> = local.getAllSellingInventory()

    fun getInventoryRows(farmId: Int, speciesId: Int): List<InventoryDisplayRow> =
        local.getInventoryRows(farmId, speciesId)

    fun getQuotesForDate(datePrefix: String): List<QuoteEntity> =
        local.getQuotesForDate(datePrefix)

    fun getOrdersForDate(datePrefix: String): List<OrderEntity> =
        local.getOrdersForDate(datePrefix)

    fun getLastQuoteId(): Int? = local.getLastQuoteId()

    suspend fun searchAddress(apiKey: String, keyword: String): List<JusoItem> {
        val response = jusoApi.searchAddress(apiKey, keyword)
        val common = response.results.common

        if (common.errorCode != "0") {
            error("주소 검색 오류: " + common.errorMessage)
        }

        return response.results.juso ?: emptyList()
    }

    fun insertQuote(quote: QuoteEntity) {
        val totals = OrderAmounts.calculate(
            OrderSummaryCodec.parseStrict(quote.itemSummary), quote.isPrepaid, quote.shippingFee
        )
        require(quote.totalQty == totals.quantity && quote.totalPrice == totals.price) {
            "견적 합계와 품목 합계가 일치하지 않습니다."
        }
        local.insertQuote(quote)
    }
    fun deleteQuote(quote: QuoteEntity) = local.deleteQuote(quote)

    suspend fun saveFarm(
        farmId: Int?,
        farmName: String,
        address: String,
        phone: String,
        memo: String
    ): FarmSaveResult {
        OrderSummaryCodec.validateText(farmName)
        require(farmName.isNotBlank() && farmName.trim() != OrderSummaryCodec.MANUAL_FARM_LABEL) {
            "농원명을 확인해주세요. 수기견적은 예약 이름입니다."
        }
        val localWork = local.transaction {
            require(getAllFarms().none { it.farmName.trim() == farmName.trim() && it.farmId != farmId }) {
                "같은 이름의 농원이 이미 있습니다."
            }
            if (farmId != null) {
                val old = getAllFarms().singleOrNull { it.farmId == farmId }
                    ?: error("수정할 농원이 없습니다.")
                if (old.farmName != farmName.trim()) MasterReferenceGuard.farm(this, farmId)
            }
            val savedId = upsertFarm(
                FarmEntity(
                    farmId = farmId,
                    farmName = farmName.trim(),
                    address = address.ifBlank { null },
                    phone = phone.ifBlank { null },
                    memo = memo.ifBlank { null }
                )
            ).toInt()

            val target = FarmEntity(
                farmId = farmId ?: savedId,
                farmName = farmName.trim(),
                address = address.ifBlank { null },
                phone = phone.ifBlank { null },
                memo = memo.ifBlank { null }
            )

            val pendingId = enqueue(
                PendingSyncType.FARM_SAVE,
                PendingFarmSyncPayload(target)
            )

            target to pendingId
        }

        val target = localWork.first
        val pendingId = localWork.second
        val cloudSynced = flushThrough(pendingId).cloudSynced

        return FarmSaveResult(target, cloudSynced)
    }

    suspend fun deleteFarm(farmId: Int): CloudWriteResult {
        val pendingId = local.transaction {
            MasterReferenceGuard.farm(this, farmId, deleting = true)
            deleteInventoryByFarmId(farmId)
            deleteFarmById(farmId)

            enqueue(
                PendingSyncType.FARM_DELETE,
                PendingDeleteFarmSyncPayload(farmId)
            )
        }

        return flushThrough(pendingId)
    }

    suspend fun saveSpecies(
        speciesId: Int?,
        speciesName: String,
        nameFarm: String,
        chosung: String,
        category: String,
        memo: String,
        isActive: Int
    ): SpeciesSaveResult {
        OrderSummaryCodec.validateText(speciesName)
        require(speciesName.isNotBlank()) { "묘목명을 입력해주세요." }
        require(isActive == 0 || isActive == 1) { "묘목 활성 상태 오류" }
        val localWork = local.transaction {
            require(getAllSpeciesIncludingInactive().none {
                it.speciesName.trim() == speciesName.trim() && it.speciesId != speciesId
            }) { "같은 이름의 묘목이 이미 있습니다." }
            if (speciesId != null) {
                val old = getAllSpeciesIncludingInactive().singleOrNull { it.speciesId == speciesId }
                    ?: error("수정할 묘목이 없습니다.")
                if (old.speciesName != speciesName.trim()) MasterReferenceGuard.species(this, speciesId)
            }
            val savedId = upsertSpecies(
                SpeciesEntity(
                    speciesId = speciesId,
                    speciesName = speciesName.trim(),
                    nameFarm = nameFarm.ifBlank { null },
                    chosung = chosung.ifBlank { null },
                    category = category.ifBlank { null },
                    memo = memo.ifBlank { null },
                    isActive = isActive
                )
            ).toInt()

            val target = SpeciesEntity(
                speciesId = speciesId ?: savedId,
                speciesName = speciesName.trim(),
                nameFarm = nameFarm.ifBlank { null },
                chosung = chosung.ifBlank { null },
                category = category.ifBlank { null },
                memo = memo.ifBlank { null },
                isActive = isActive
            )

            val pendingId = enqueue(
                PendingSyncType.SPECIES_SAVE,
                PendingSpeciesSyncPayload(target)
            )

            target to pendingId
        }

        val target = localWork.first
        val pendingId = localWork.second
        val cloudSynced = flushThrough(pendingId).cloudSynced

        return SpeciesSaveResult(target, cloudSynced)
    }

    suspend fun deleteSpecies(speciesId: Int): CloudWriteResult {
        val pendingId = local.transaction {
            deactivateSpecies(speciesId)
            enqueue(
                PendingSyncType.SPECIES_DELETE,
                PendingDeleteSpeciesSyncPayload(speciesId)
            )
        }

        return flushThrough(pendingId)
    }

    suspend fun saveInventory(
        entity: FarmInventoryEntity,
        farmName: String,
        speciesName: String
    ): CloudWriteResult {
        require(entity.quantity >= 0 && entity.wholesalePrice >= 0 && entity.retailPrice >= 0) {
            "재고 수량과 단가는 음수일 수 없습니다."
        }
        listOf(entity.quality, entity.age, entity.size, entity.plantType).forEach(OrderSummaryCodec::validateText)
        val isDelete = entity.quantity == 0
        val action = if (isDelete) "delete" else "save"

        val pendingId = local.transaction {
            require(getAllFarms().any { it.farmId == entity.farmId && it.farmName == farmName }) {
                "농원 정보가 변경되었습니다. 다시 선택해주세요."
            }
            require(getAllSpecies().any { it.speciesId == entity.speciesId && it.speciesName == speciesName }) {
                "묘목 정보가 변경되었습니다. 다시 선택해주세요."
            }
            if (isDelete) {
                deleteInventory(
                    entity.farmId,
                    entity.speciesId,
                    entity.quality,
                    entity.age,
                    entity.size,
                    entity.plantType
                )
            } else {
                upsertInventory(entity)
            }

            enqueue(
                PendingSyncType.INVENTORY,
                PendingInventorySyncPayload(
                    action = action,
                    entity = entity,
                    farmName = farmName.trim(),
                    speciesName = speciesName
                )
            )
        }

        return flushThrough(pendingId)
    }

    suspend fun confirmOrder(quote: QuoteEntity, newOrder: OrderEntity): CloudWriteResult {
        val pendingId = local.transaction {
            val stored = getQuoteById(quote.quoteId ?: error("저장된 견적이 아닙니다."))
                ?: error("이미 주문으로 확정되었거나 삭제된 견적입니다.")
            require(stored == quote) { "견적이 변경되었습니다. 다시 열어주세요." }
            require(newOrder.orderId == null && newOrder.orderNumber.isNotBlank()) {
                "새 주문번호가 필요합니다."
            }
            check(getOrderByNumber(newOrder.orderNumber) == null) { "이미 존재하는 주문번호입니다." }
            val lines = OrderSummaryCodec.parseStrict(stored.itemSummary)
            val totals = OrderAmounts.calculate(lines, stored.isPrepaid, stored.shippingFee)
            require(stored.totalQty == totals.quantity && stored.totalPrice == totals.price) {
                "견적 합계와 품목 합계가 일치하지 않습니다."
            }
            // Stored quote is the authority for both local deduction and the wire payload.
            val order = newOrder.copy(
                itemSummary = stored.itemSummary, customerPhone = stored.customerPhone,
                totalQty = totals.quantity, totalPrice = totals.price,
                isPrepaid = stored.isPrepaid, shippingFee = stored.shippingFee,
                status = OrderStatus.CONFIRMED
            )
            insertOrder(order)
            deleteQuote(stored)
            deductInventoryStrict(stored.itemSummary)
            enqueue(
                PendingSyncType.ORDER,
                PendingOrderSyncPayload(order, lines.filter { it.isInventoryBacked }.map { line ->
                    SheetOrderItemDto(
                        farm_name = line.farmName, species_name = line.speciesName,
                        quality = line.quality, age = line.age, size = line.size,
                        plant_type = line.plantType, order_qty = line.orderQty,
                        retail_price = line.retailPrice
                    )
                })
            )
        }
        return flushThrough(pendingId)
    }

    /**
     * 주문취소는 로컬 상태 변경 + 재고복원을 한 트랜잭션으로 처리한다.
     * 같은 주문을 두 번 취소해도 재고는 한 번만 복원된다.
     */
    suspend fun cancelOrder(orderNumber: String): OrderCancelResult {
        return try {
            val localWork = local.transaction {
                val currentOrder = getOrderByNumber(orderNumber)
                    ?: error("주문을 찾을 수 없습니다: $orderNumber")

                if (currentOrder.status == OrderStatus.CANCELED) {
                    return@transaction LocalCancelWork(alreadyCanceled = true)
                }

                check(currentOrder.status == OrderStatus.CONFIRMED) { "지원하지 않는 주문 상태입니다." }
                val lines = OrderSummaryCodec.parseStrict(currentOrder.itemSummary)

                // 원격에서도 주문취소가 재고복원보다 먼저 처리되도록 먼저 큐에 적재한다.
                val cancelPendingId = enqueue(
                    PendingSyncType.CANCEL_ORDER,
                    PendingCancelOrderSyncPayload(orderNumber)
                )

                val inventoryTasks = mutableListOf<InventoryPendingTask>()
                var restoredQuantity = 0

                lines.forEach { line ->
                    if (!line.isInventoryBacked || line.orderQty <= 0) return@forEach

                    val farm = getFarmByName(line.farmName)
                        ?: error("재고 복원 실패: 농원 '" + line.farmName + "' 없음")
                    val species = getSpeciesByName(line.speciesName)
                        ?: error("재고 복원 실패: 묘목 '" + line.speciesName + "' 없음")

                    val farmId = farm.farmId
                        ?: error("재고 복원 실패: 농원 ID 없음")
                    val speciesId = species.speciesId
                        ?: error("재고 복원 실패: 묘목 ID 없음")

                    val existing = getInventoryEntity(
                        farmId = farmId,
                        speciesId = speciesId,
                        quality = line.quality,
                        age = line.age,
                        size = line.size,
                        plantType = line.plantType
                    )

                    val restored = if (existing != null) {
                        existing.copy(quantity = Math.addExact(existing.quantity, line.orderQty))
                    } else {
                        FarmInventoryEntity(
                            farmId = farmId,
                            speciesId = speciesId,
                            quality = line.quality,
                            age = line.age,
                            size = line.size,
                            plantType = line.plantType,
                            quantity = line.orderQty,
                            wholesalePrice = line.wholesalePrice,
                            retailPrice = line.retailPrice
                        )
                    }

                    upsertInventory(restored)
                    restoredQuantity = Math.addExact(restoredQuantity, line.orderQty)

                    val inventoryPendingId = enqueue(
                        PendingSyncType.INVENTORY,
                        PendingInventorySyncPayload(
                            action = "save",
                            entity = restored,
                            farmName = farm.farmName,
                            speciesName = species.speciesName
                        )
                    )

                    inventoryTasks += InventoryPendingTask(
                        pendingId = inventoryPendingId,
                        entity = restored,
                        farmName = farm.farmName,
                        speciesName = species.speciesName
                    )
                }

                updateOrderStatus(orderNumber, OrderStatus.CANCELED)

                LocalCancelWork(
                    alreadyCanceled = false,
                    cancelPendingId = cancelPendingId,
                    restoredQuantity = restoredQuantity,
                    inventoryTasks = inventoryTasks
                )
            }

            if (localWork.alreadyCanceled) {
                return OrderCancelResult(
                    success = true,
                    alreadyCanceled = true
                )
            }

            val lastId = localWork.inventoryTasks.lastOrNull()?.pendingId
                ?: checkNotNull(localWork.cancelPendingId)
            flushThrough(lastId)
            val cancelCloudSynced = local.getPendingSyncById(checkNotNull(localWork.cancelPendingId)) == null
            val inventorySynced = localWork.inventoryTasks.count {
                local.getPendingSyncById(it.pendingId) == null
            }
            OrderCancelResult(
                success = true,
                restoredQuantity = localWork.restoredQuantity,
                restoredLines = localWork.inventoryTasks.size,
                cancelCloudSynced = cancelCloudSynced,
                inventoryCloudSynced = inventorySynced,
                inventoryPending = localWork.inventoryTasks.size - inventorySynced
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OrderCancelResult(
                success = false,
                errorMessage = e.message ?: "주문취소 처리 실패"
            )
        }
    }

    suspend fun updateOrderDeliveryInfo(
        orderNumber: String,
        receiverName: String,
        zipNo: String,
        roadAddress: String,
        detailAddress: String,
        note: String
    ): CloudWriteResult {
        val pendingId = local.transaction {
            check(getOrderByNumber(orderNumber) != null) { "주문을 찾을 수 없습니다." }
            updateOrderDeliveryInfo(
                orderNumber,
                receiverName,
                zipNo,
                roadAddress,
                detailAddress,
                note
            )

            enqueue(
                PendingSyncType.DELIVERY,
                PendingDeliverySyncPayload(
                    orderNumber = orderNumber,
                    receiverName = receiverName,
                    zipNo = zipNo,
                    roadAddress = roadAddress,
                    detailAddress = detailAddress,
                    note = note
                )
            )
        }

        return flushThrough(pendingId)
    }

    private data class DrainResult(val sent: Int, val error: String? = null)

    // Called only while holding the database's shared network mutex. New local writes
    // can commit immediately; each invocation processes a bounded snapshot of the queue.
    private suspend fun drainPending(throughId: Int = Int.MAX_VALUE): DrainResult {
        var sent = 0
        for (pending in local.getAllPendingSync().filter { checkNotNull(it.syncId) <= throughId }) {
            val id = checkNotNull(pending.syncId)
            val result = syncManager.processPendingSync(pending)
            if (!result.success) {
                val error = result.errorMessage ?: "시트 응답 실패"
                local.markPendingSyncFailed(id, error)
                return DrainResult(sent, error)
            }
            local.deletePendingSync(id)
            sent++
        }
        return DrainResult(sent)
    }

    private suspend fun flushThrough(pendingId: Int): CloudWriteResult = local.serializedSync {
        try {
            val result = drainPending(pendingId)
            CloudWriteResult(local.getPendingSyncById(pendingId) == null, result.error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The local transaction has already committed. Keep its outbox for retry.
            CloudWriteResult(false, e.message ?: "동기화 대기")
        }
    }

    suspend fun syncAll(): GeneralSyncResult = local.serializedSync {
        val drained = drainPending()
        val remaining = local.getAllPendingSync().size
        if (drained.error != null || remaining > 0) {
            return@serializedSync GeneralSyncResult(drained.sent, remaining.coerceAtLeast(1))
        }
        val snapshot = syncManager.syncSnapshot()
        GeneralSyncResult(drained.sent, 0, snapshot.inventoryResult, snapshot.orderCount)
    }

    suspend fun syncFarmMaster(): Int = local.serializedSync {
        if (drainPending().error != null) -1 else syncManager.syncFarmsWithGoogleSheet()
    }

    suspend fun syncSpeciesMaster(): Int = local.serializedSync {
        if (drainPending().error != null) -1 else syncManager.syncSpeciesWithGoogleSheet()
    }

    private fun enqueue(type: String, payload: Any): Int =
        local.insertPendingSync(
            PendingSyncEntity(
                syncType = type,
                payloadJson = gson.toJson(payload)
            )
        )

    private fun deductInventoryStrict(summary: String) {
        val lines = OrderSummaryCodec.parseStrict(summary)

        lines.forEach { line ->
            if (!line.isInventoryBacked || line.orderQty <= 0) return@forEach

            val farm = local.getFarmByName(line.farmName)
                ?: error("주문확정 실패: 농원 '" + line.farmName + "' 없음")
            val species = local.getSpeciesByName(line.speciesName)
                ?: error("주문확정 실패: 묘목 '" + line.speciesName + "' 없음")

            val farmId = farm.farmId ?: error("주문확정 실패: 농원 ID 없음")
            val speciesId = species.speciesId ?: error("주문확정 실패: 묘목 ID 없음")

            val current = local.getInventoryEntity(
                farmId,
                speciesId,
                line.quality,
                line.age,
                line.size,
                line.plantType
            ) ?: error("주문확정 실패: '" + line.speciesName + "' 재고 없음")

            if (current.quantity < line.orderQty) {
                error(
                    "주문확정 실패: " + line.speciesName + " 재고 부족 " +
                        "(재고 " + current.quantity + "주 / 주문 " + line.orderQty + "주)"
                )
            }

            val remaining = current.quantity - line.orderQty

            if (remaining <= 0) {
                local.deleteInventory(
                    farmId,
                    speciesId,
                    line.quality,
                    line.age,
                    line.size,
                    line.plantType
                )
            } else {
                local.upsertInventory(current.copy(quantity = remaining))
            }
        }
    }


}
