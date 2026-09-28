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
import com.example.namufarm.data.remote.SheetOrderItemDto
import com.google.gson.Gson

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

private data class OrderLine(
    val speciesName: String,
    val quality: String,
    val age: String,
    val size: String,
    val plantType: String,
    val orderQty: Int,
    val retailPrice: Int,
    val farmName: String,
    val wholesalePrice: Int
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
    private val syncManager: SyncManager
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

    fun insertQuote(quote: QuoteEntity) = local.insertQuote(quote)
    fun deleteQuote(quote: QuoteEntity) = local.deleteQuote(quote)

    suspend fun saveFarm(
        farmId: Int?,
        farmName: String,
        address: String,
        phone: String,
        memo: String
    ): FarmSaveResult {
        val savedId = local.upsertFarm(
            FarmEntity(
                farmId = farmId,
                farmName = farmName,
                address = address.ifBlank { null },
                phone = phone.ifBlank { null },
                memo = memo.ifBlank { null }
            )
        ).toInt()

        val target = FarmEntity(
            farmId = farmId ?: savedId,
            farmName = farmName,
            address = address.ifBlank { null },
            phone = phone.ifBlank { null },
            memo = memo.ifBlank { null }
        )

        val pendingId = enqueue(
            PendingSyncType.FARM_SAVE,
            PendingFarmSyncPayload(target)
        )

        val cloudSynced = syncManager.uploadFarmToSheet(target)
        if (cloudSynced) local.deletePendingSync(pendingId)

        return FarmSaveResult(target, cloudSynced)
    }

    suspend fun deleteFarm(farmId: Int): CloudWriteResult {
        local.transaction {
            deleteInventoryByFarmId(farmId)
            deleteFarmById(farmId)
        }

        val pendingId = enqueue(
            PendingSyncType.FARM_DELETE,
            PendingDeleteFarmSyncPayload(farmId)
        )

        val cloudSynced = syncManager.deleteFarmOnSheet(farmId)
        if (cloudSynced) local.deletePendingSync(pendingId)

        return CloudWriteResult(cloudSynced)
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
        val savedId = local.upsertSpecies(
            SpeciesEntity(
                speciesId = speciesId,
                speciesName = speciesName,
                nameFarm = nameFarm.ifBlank { null },
                chosung = chosung.ifBlank { null },
                category = category.ifBlank { null },
                memo = memo.ifBlank { null },
                isActive = isActive
            )
        ).toInt()

        val target = SpeciesEntity(
            speciesId = speciesId ?: savedId,
            speciesName = speciesName,
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

        val cloudSynced = syncManager.uploadSpeciesToSheet(target)
        if (cloudSynced) local.deletePendingSync(pendingId)

        return SpeciesSaveResult(target, cloudSynced)
    }

    suspend fun deleteSpecies(speciesId: Int): CloudWriteResult {
        local.deactivateSpecies(speciesId)

        val pendingId = enqueue(
            PendingSyncType.SPECIES_DELETE,
            PendingDeleteSpeciesSyncPayload(speciesId)
        )

        val cloudSynced = syncManager.deleteSpeciesOnSheet(speciesId)
        if (cloudSynced) local.deletePendingSync(pendingId)

        return CloudWriteResult(cloudSynced)
    }

    suspend fun saveInventory(
        entity: FarmInventoryEntity,
        farmName: String,
        speciesName: String
    ): CloudWriteResult {
        val isDelete = entity.quantity <= 0
        val action = if (isDelete) "delete" else "save"

        if (isDelete) {
            local.deleteInventory(
                entity.farmId,
                entity.speciesId,
                entity.quality,
                entity.age,
                entity.size,
                entity.plantType
            )
        } else {
            local.upsertInventory(entity)
        }

        val pendingId = enqueue(
            PendingSyncType.INVENTORY,
            PendingInventorySyncPayload(
                action = action,
                entity = entity,
                farmName = farmName,
                speciesName = speciesName
            )
        )

        val cloudSynced = syncManager.uploadToSheet(
            action = action,
            entity = entity,
            farmName = farmName,
            speciesName = speciesName
        )

        if (cloudSynced) local.deletePendingSync(pendingId)

        return CloudWriteResult(cloudSynced)
    }

    suspend fun confirmOrder(
        quote: QuoteEntity,
        newOrder: OrderEntity,
        itemsToDeduct: List<SheetOrderItemDto>
    ): CloudWriteResult {
        val pendingId = local.transaction {
            insertOrder(newOrder)
            deleteQuote(quote)
            deductInventoryStrict(quote.itemSummary)

            enqueue(
                PendingSyncType.ORDER,
                PendingOrderSyncPayload(
                    order = newOrder,
                    items = itemsToDeduct
                )
            )
        }

        val cloudSynced = syncManager.uploadOrderToSheet(newOrder, itemsToDeduct)
        if (cloudSynced) local.deletePendingSync(pendingId)

        return CloudWriteResult(cloudSynced)
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

                val lines = parseOrderLinesStrict(currentOrder.itemSummary)

                // 원격에서도 주문취소가 재고복원보다 먼저 처리되도록 먼저 큐에 적재한다.
                val cancelPendingId = enqueue(
                    PendingSyncType.CANCEL_ORDER,
                    PendingCancelOrderSyncPayload(orderNumber)
                )

                val inventoryTasks = mutableListOf<InventoryPendingTask>()
                var restoredQuantity = 0

                lines.forEach { line ->
                    if (line.orderQty <= 0) return@forEach

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
                        existing.copy(quantity = existing.quantity + line.orderQty)
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
                    restoredQuantity += line.orderQty

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

            val cancelCloudSynced = syncManager.cancelOrderOnSheet(orderNumber)

            if (cancelCloudSynced) {
                localWork.cancelPendingId?.let(local::deletePendingSync)
            } else {
                return OrderCancelResult(
                    success = true,
                    restoredQuantity = localWork.restoredQuantity,
                    restoredLines = localWork.inventoryTasks.size,
                    cancelCloudSynced = false,
                    inventoryCloudSynced = 0,
                    inventoryPending = localWork.inventoryTasks.size
                )
            }

            var inventorySynced = 0

            localWork.inventoryTasks.forEach { task ->
                val ok = syncManager.uploadToSheet(
                    action = "save",
                    entity = task.entity,
                    farmName = task.farmName,
                    speciesName = task.speciesName
                )

                if (ok) {
                    local.deletePendingSync(task.pendingId)
                    inventorySynced++
                }
            }

            OrderCancelResult(
                success = true,
                restoredQuantity = localWork.restoredQuantity,
                restoredLines = localWork.inventoryTasks.size,
                cancelCloudSynced = true,
                inventoryCloudSynced = inventorySynced,
                inventoryPending = localWork.inventoryTasks.size - inventorySynced
            )
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
        local.updateOrderDeliveryInfo(
            orderNumber,
            receiverName,
            zipNo,
            roadAddress,
            detailAddress,
            note
        )

        val pendingId = enqueue(
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

        val cloudSynced = syncManager.updateOrderDeliveryOnSheet(
            orderNumber,
            receiverName,
            zipNo,
            roadAddress,
            detailAddress,
            note
        )

        if (cloudSynced) local.deletePendingSync(pendingId)

        return CloudWriteResult(cloudSynced)
    }

    suspend fun syncAll(): GeneralSyncResult {
        var resentSuccess = 0
        var pendingFailureCount = 0

        // pending_sync는 단순 목록이 아니라 업무 순서다.
        // 앞 항목 실패 시 뒤 항목을 먼저 보내지 않는다.
        for (pending in local.getAllPendingSync()) {
            val result = syncManager.processPendingSync(pending)

            if (result.success) {
                pending.syncId?.let(local::deletePendingSync)
                resentSuccess++
            } else {
                pending.syncId?.let {
                    local.markPendingSyncFailed(
                        it,
                        result.errorMessage ?: "알 수 없는 오류"
                    )
                }
                pendingFailureCount++
                break
            }
        }

        if (pendingFailureCount > 0) {
            return GeneralSyncResult(
                resentSuccess = resentSuccess,
                pendingFailureCount = pendingFailureCount
            )
        }

        val inventoryResult = syncManager.syncWithGoogleSheet()
        val orderSyncCount =
            if (inventoryResult.errorMessage == null) {
                syncManager.syncOrdersWithGoogleSheet()
            } else {
                -1
            }

        return GeneralSyncResult(
            resentSuccess = resentSuccess,
            pendingFailureCount = 0,
            inventoryResult = inventoryResult,
            orderSyncCount = orderSyncCount
        )
    }

    suspend fun syncFarmMaster(): Int = syncManager.syncFarmsWithGoogleSheet()
    suspend fun syncSpeciesMaster(): Int = syncManager.syncSpeciesWithGoogleSheet()

    private fun enqueue(type: String, payload: Any): Int =
        local.insertPendingSync(
            PendingSyncEntity(
                syncType = type,
                payloadJson = gson.toJson(payload)
            )
        )

    private fun deductInventoryStrict(summary: String) {
        val lines = parseOrderLinesStrict(summary)

        lines.forEach { line ->
            if (line.orderQty <= 0) return@forEach

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

    private fun parseOrderLinesStrict(summary: String): List<OrderLine> {
        val rawLines = summary.lines().filter { it.isNotBlank() }
        if (rawLines.isEmpty()) error("주문 품목 정보가 비어 있습니다.")

        return rawLines.mapIndexed { index, raw ->
            val parts = raw.split("|")

            when {
                parts.size >= 9 -> OrderLine(
                    speciesName = parts[0].trim(),
                    quality = parts[1].trim(),
                    age = parts[2].trim(),
                    size = parts[3].trim(),
                    plantType = parts[4].trim(),
                    orderQty = parts[5].toIntOrNull()
                        ?: error((index + 1).toString() + "번째 품목 수량 오류"),
                    retailPrice = parts[6].toIntOrNull() ?: 0,
                    farmName = parts[7].trim(),
                    wholesalePrice = parts[8].toIntOrNull() ?: 0
                )

                // 초기 저장 포맷 호환:
                // species|quality|age|qty|retail|farm|wholesale
                parts.size >= 7 -> OrderLine(
                    speciesName = parts[0].trim(),
                    quality = parts[1].trim(),
                    age = parts[2].trim(),
                    size = "0.0m",
                    plantType = "실생",
                    orderQty = parts[3].toIntOrNull()
                        ?: error((index + 1).toString() + "번째 품목 수량 오류"),
                    retailPrice = parts[4].toIntOrNull() ?: 0,
                    farmName = parts[5].trim(),
                    wholesalePrice = parts[6].toIntOrNull() ?: 0
                )

                else -> error((index + 1).toString() + "번째 주문 품목 형식을 읽을 수 없습니다.")
            }
        }
    }
}
