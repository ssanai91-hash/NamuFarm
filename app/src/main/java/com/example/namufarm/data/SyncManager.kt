package com.example.namufarm.data

import android.util.Log
import com.example.namufarm.data.remote.CancelOrderRequest
import com.example.namufarm.data.remote.FarmDeleteRequest
import com.example.namufarm.data.remote.FarmUploadRequest
import com.example.namufarm.data.remote.GoogleSheetApi
import com.example.namufarm.data.remote.OrderUploadRequest
import com.example.namufarm.data.remote.SheetFarmDto
import com.example.namufarm.data.remote.SheetInventoryDto
import com.example.namufarm.data.remote.SheetOrderDto
import com.example.namufarm.data.remote.SheetOrderItemDto
import com.example.namufarm.data.remote.SheetSpeciesDto
import com.example.namufarm.data.remote.SheetUploadRequest
import com.example.namufarm.data.remote.SpeciesDeleteRequest
import com.example.namufarm.data.remote.SpeciesUploadRequest
import com.example.namufarm.data.remote.SyncResponse
import com.example.namufarm.data.remote.UpdateOrderDeliveryRequest
import com.google.gson.Gson
import kotlin.jvm.java

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

class SyncManager(
    private val dao: ManagementDao,
    private val api: GoogleSheetApi
) {
    private val gson = Gson()

    suspend fun processPendingSync(item: PendingSyncEntity): PendingSyncProcessResult {
        return try {
            val ok = when (item.syncType) {
                PendingSyncType.INVENTORY -> {
                    val payload = gson.fromJson(
                        item.payloadJson,
                        PendingInventorySyncPayload::class.java
                    )
                    uploadToSheet(
                        action = payload.action,
                        entity = payload.entity,
                        farmName = payload.farmName,
                        speciesName = payload.speciesName
                    )
                }

                PendingSyncType.ORDER -> {
                    val payload = gson.fromJson(
                        item.payloadJson,
                        PendingOrderSyncPayload::class.java
                    )
                    uploadOrderToSheet(
                        orderEntity = payload.order,
                        itemsToDeduct = payload.items
                    )
                }

                PendingSyncType.CANCEL_ORDER -> {
                    val payload = gson.fromJson(
                        item.payloadJson,
                        PendingCancelOrderSyncPayload::class.java
                    )
                    cancelOrderOnSheet(payload.orderNumber)
                }

                PendingSyncType.DELIVERY -> {
                    val payload = gson.fromJson(
                        item.payloadJson,
                        PendingDeliverySyncPayload::class.java
                    )
                    updateOrderDeliveryOnSheet(
                        orderNumber = payload.orderNumber,
                        receiverName = payload.receiverName,
                        zipNo = payload.zipNo,
                        roadAddress = payload.roadAddress,
                        detailAddress = payload.detailAddress,
                        note = payload.note
                    )
                }

                PendingSyncType.FARM_SAVE -> {
                    val payload = gson.fromJson(
                        item.payloadJson,
                        PendingFarmSyncPayload::class.java
                    )
                    uploadFarmToSheet(payload.farm)
                }

                PendingSyncType.FARM_DELETE -> {
                    val payload = gson.fromJson(
                        item.payloadJson,
                        PendingDeleteFarmSyncPayload::class.java
                    )
                    deleteFarmOnSheet(payload.farmId)
                }

                PendingSyncType.SPECIES_SAVE -> {
                    val payload = gson.fromJson(
                        item.payloadJson,
                        PendingSpeciesSyncPayload::class.java
                    )
                    uploadSpeciesToSheet(payload.species)
                }

                PendingSyncType.SPECIES_DELETE -> {
                    val payload = gson.fromJson(
                        item.payloadJson,
                        PendingDeleteSpeciesSyncPayload::class.java
                    )
                    deleteSpeciesOnSheet(payload.speciesId)
                }

                // 🚨 [추가] 주문 상태(입금완료/주문취소) 변경 택배 상자 처리!
                /*PendingSyncType.UPDATE_ORDER_STATUS -> {
                    val payload = gson.fromJson(
                        item.payloadJson,
                        PendingOrderStatusSyncPayload::class.java
                    )
                    updateOrderStatusOnSheet(payload.orderNumber, payload.status)
                }*/

                else -> false
            }

            if (ok) {
                PendingSyncProcessResult(success = true)
            } else {
                PendingSyncProcessResult(
                    success = false,
                    errorMessage = "시트 응답 실패"
                )
            }
        } catch (e: Exception) {
            PendingSyncProcessResult(
                success = false,
                errorMessage = e.message ?: "알 수 없는 오류"
            )
        }
    }

    // ===== 농원 =====

    suspend fun uploadFarmToSheet(farm: FarmEntity): Boolean {
        val farmId = farm.farmId ?: return false

        return try {
            val response = api.uploadFarm(
                FarmUploadRequest(
                    action = "save_farm",
                    data = SheetFarmDto(
                        farm_id = farmId,
                        farm_name = farm.farmName,
                        address = farm.address.orEmpty(),
                        phone = farm.phone.orEmpty(),
                        memo = farm.memo.orEmpty()
                    )
                )
            )
            response.result.equals("success", ignoreCase = true)
        } catch (e: Exception) {
            Log.e("SyncManager", "농원 저장 시트 업로드 실패", e)
            false
        }
    }

    suspend fun deleteFarmOnSheet(farmId: Int): Boolean {
        return try {
            val response = api.deleteFarm(
                FarmDeleteRequest(
                    action = "delete_farm",
                    farm_id = farmId
                )
            )
            response.result.equals("success", ignoreCase = true)
        } catch (e: Exception) {
            Log.e("SyncManager", "농원 삭제 시트 업로드 실패", e)
            false
        }
    }

    suspend fun syncFarmsWithGoogleSheet(): Int {
        return try {
            val farms = api.getFarmData("get_farms")

            dao.clearAllFarms()

            farms.sortedBy { it.farm_id }.forEach { dto ->
                dao.upsertFarm(
                    FarmEntity(
                        farmId = dto.farm_id,
                        farmName = dto.farm_name,
                        address = dto.address.ifBlank { null },
                        phone = dto.phone.ifBlank { null },
                        memo = dto.memo.ifBlank { null }
                    )
                )
            }

            farms.size
        } catch (e: Exception) {
            Log.e("SyncManager", "농원 동기화 실패", e)
            -1
        }
    }

    // ===== 묘목 =====

    suspend fun uploadSpeciesToSheet(species: SpeciesEntity): Boolean {
        val speciesId = species.speciesId ?: return false

        return try {
            val response = api.uploadSpecies(
                SpeciesUploadRequest(
                    action = "save_species",
                    data = SheetSpeciesDto(
                        species_id = speciesId,
                        species_name = species.speciesName,
                        name_farm = species.nameFarm.orEmpty(),
                        chosung = species.chosung.orEmpty(),
                        category = species.category.orEmpty(),
                        memo = species.memo.orEmpty(),
                        is_active = species.isActive ?: 1
                    )
                )
            )
            response.result.equals("success", ignoreCase = true)
        } catch (e: Exception) {
            Log.e("SyncManager", "묘목 저장 시트 업로드 실패", e)
            false
        }
    }

    suspend fun deleteSpeciesOnSheet(speciesId: Int): Boolean {
        return try {
            val response = api.deleteSpecies(
                SpeciesDeleteRequest(
                    action = "delete_species",
                    species_id = speciesId
                )
            )
            response.result.equals("success", ignoreCase = true)
        } catch (e: Exception) {
            Log.e("SyncManager", "묘목 삭제(비활성화) 시트 업로드 실패", e)
            false
        }
    }

    suspend fun syncSpeciesWithGoogleSheet(): Int {
        return try {
            val speciesList = api.getSpeciesData("get_species")

            dao.clearAllSpecies()

            speciesList.sortedBy { it.species_id }.forEach { dto ->
                dao.upsertSpecies(
                    SpeciesEntity(
                        speciesId = dto.species_id,
                        speciesName = dto.species_name,
                        nameFarm = dto.name_farm.ifBlank { null },
                        chosung = dto.chosung.ifBlank { null },
                        category = dto.category.ifBlank { null },
                        memo = dto.memo.ifBlank { null },
                        isActive = dto.is_active
                    )
                )
            }

            speciesList.size
        } catch (e: Exception) {
            Log.e("SyncManager", "묘목 동기화 실패", e)
            -1
        }
    }

    // ===== 재고 =====

    suspend fun syncWithGoogleSheet(): SyncResult {
        return try {
            val remoteData = api.getInventoryData("get_inventory")

            var insertedFarms = 0
            var insertedSpecies = 0
            var upserted = 0
            var skipped = 0

            dao.clearAllInventory()

            val farmMap = dao.getAllFarms()
                .associateBy { it.farmName.trim() }
                .toMutableMap()

            val speciesMap = dao.getAllSpecies()
                .associateBy { it.speciesName.trim() }
                .toMutableMap()

            remoteData.forEach { dto ->
                val farmName = dto.farm_name.trim()
                val speciesName = dto.species_name.trim()
                val quality = dto.quality.trim()
                val age = dto.age.trim()
                val size = dto.size.trim()
                val plantType = dto.plant_type.trim()

                var farm = farmMap[farmName]
                if (farm == null) {
                    dao.upsertFarm(FarmEntity(farmName = farmName))
                    insertedFarms++
                    farm = dao.getFarmByName(farmName)
                    if (farm != null) farmMap[farmName] = farm
                }

                var species = speciesMap[speciesName]
                if (species == null) {
                    dao.upsertSpecies(
                        SpeciesEntity(
                            speciesName = speciesName,
                            chosung = speciesName.firstOrNull()?.toString(),
                            isActive = 1
                        )
                    )
                    insertedSpecies++
                    species = dao.getSpeciesByName(speciesName)
                    if (species != null) speciesMap[speciesName] = species
                }

                val farmId = farm?.farmId
                val speciesId = species?.speciesId

                if (farmId != null && speciesId != null) {
                    dao.upsertInventory(
                        FarmInventoryEntity(
                            farmId = farmId,
                            speciesId = speciesId,
                            quality = quality,
                            age = age,
                            size = size,
                            plantType = plantType,
                            quantity = dto.quantity,
                            wholesalePrice = dto.wholesale_price,
                            retailPrice = dto.retail_price
                        )
                    )
                    upserted++
                } else {
                    skipped++
                    Log.w(
                        "SyncManager",
                        "재고 스킵 farm='$farmName', species='$speciesName'"
                    )
                }
            }

            SyncResult(
                total = remoteData.size,
                upserted = upserted,
                skipped = skipped,
                insertedFarms = insertedFarms,
                insertedSpecies = insertedSpecies
            )
        } catch (e: Exception) {
            Log.e("SyncManager", "재고 동기화 실패", e)
            SyncResult(
                total = 0,
                upserted = 0,
                skipped = 0,
                insertedFarms = 0,
                insertedSpecies = 0,
                errorMessage = e.message
            )
        }
    }

    suspend fun uploadToSheet(
        action: String,
        entity: FarmInventoryEntity,
        farmName: String,
        speciesName: String
    ): Boolean {
        return try {
            val dto = SheetInventoryDto(
                farm_name = farmName,
                species_name = speciesName,
                quality = entity.quality,
                age = entity.age,
                size = entity.size,
                plant_type = entity.plantType,
                quantity = entity.quantity,
                wholesale_price = entity.wholesalePrice,
                retail_price = entity.retailPrice
            )

            val response = api.uploadInventory(
                SheetUploadRequest(
                    action = action,
                    data = dto
                )
            )

            response.result.equals("success", ignoreCase = true)
        } catch (e: Exception) {
            Log.e("SyncManager", "재고 업로드 실패", e)
            false
        }
    }

    // ===== 주문 =====

    suspend fun uploadOrderToSheet(
        orderEntity: OrderEntity,
        itemsToDeduct: List<SheetOrderItemDto>
    ): Boolean {
        return try {
            val orderDto = SheetOrderDto(
                order_number = orderEntity.orderNumber,
                date = orderEntity.orderDate,
                customer_phone = orderEntity.customerPhone,
                total_qty = orderEntity.totalQty,
                total_price = orderEntity.totalPrice,
                summary = orderEntity.itemSummary,
                receiver_name = orderEntity.receiverName,
                address = buildString {
                    if (orderEntity.zipNo.isNotBlank()) append("[${orderEntity.zipNo}] ")
                    append(orderEntity.roadAddress)
                    if (orderEntity.detailAddress.isNotBlank()) append(" ${orderEntity.detailAddress}")
                }.trim(),
                note = orderEntity.note
            )

            val response = api.uploadOrder(
                OrderUploadRequest(
                    action = "order",
                    order_data = orderDto,
                    items = itemsToDeduct
                )
            )

            response.result.equals("success", ignoreCase = true)
        } catch (e: Exception) {
            Log.e("SyncManager", "주문 업로드 실패", e)
            false
        }
    }

    suspend fun cancelOrderOnSheet(orderNumber: String): Boolean {
        return try {
            val response = api.cancelOrder(
                CancelOrderRequest(
                    action = "cancel_order",
                    order_number = orderNumber
                )
            )
            response.result.equals("success", ignoreCase = true)
        } catch (e: Exception) {
            Log.e("SyncManager", "주문 취소 업로드 실패", e)
            false
        }
    }

    suspend fun updateOrderDeliveryOnSheet(
        orderNumber: String,
        receiverName: String,
        zipNo: String,
        roadAddress: String,
        detailAddress: String,
        note: String
    ): Boolean {
        return try {
            val fullAddress = buildString {
                if (zipNo.isNotBlank()) append("[$zipNo] ")
                append(roadAddress)
                if (detailAddress.isNotBlank()) append(" $detailAddress")
            }.trim()

            val response = api.updateOrderDelivery(
                UpdateOrderDeliveryRequest(
                    action = "update_delivery",
                    order_number = orderNumber,
                    receiver_name = receiverName,
                    address = fullAddress,
                    note = note
                )
            )

            response.result.equals("success", ignoreCase = true)
        } catch (e: Exception) {
            Log.e("SyncManager", "배송정보 업로드 실패", e)
            false
        }
    }

    suspend fun syncOrdersWithGoogleSheet(): Int {
        return try {
            val remoteOrders = api.getOrdersData("get_orders")
            if (remoteOrders.isEmpty()) return 0

            var syncedCount = 0
            remoteOrders.forEach { dto ->
                val orderNumber = dto.order_number ?: return@forEach

                // 🚨 [핵심 해결] 무한 복제 방지!
                // 새 데이터를 넣기 전에, 기존 DB에 같은 주문번호가 있으면 싹 지워버림 (완벽한 덮어쓰기 효과)
                dao.deleteOrderByNumber(orderNumber)

                dao.insertOrder(
                    OrderEntity(
                        orderNumber = orderNumber,
                        orderDate = dto.date ?: "",
                        customerPhone = dto.customer_phone ?: "",
                        totalQty = dto.total_qty ?: 0,
                        totalPrice = dto.total_price ?: 0,
                        itemSummary = dto.summary ?: "",
                        receiverName = dto.receiver_name ?: "",
                        zipNo = "",
                        roadAddress = dto.address ?: "",
                        detailAddress = "",
                        note = dto.note ?: "",
                        status = dto.status ?: "입금확인"
                    )
                )
                syncedCount++
            }
            syncedCount
        } catch (e: Exception) {
            Log.e("SyncManager", "주문내역 동기화 실패", e)
            -1
        }
    }

    // 🚨 [추가] 구글 시트로 "이 주문 상태 이걸로 바꿔!" 라고 쏘는 함수
    suspend fun updateOrderStatusOnSheet(orderNumber: String, status: String): Boolean {
        return try {
            val response = api.updateOrderStatus(
                com.example.namufarm.data.remote.UpdateOrderStatusRequest(
                    action = "update_order_status",
                    order_number = orderNumber,
                    status = status
                )
            )
            response.result.equals("success", ignoreCase = true)
        } catch (e: Exception) {
            Log.e("SyncManager", "주문 상태 업데이트 시트 업로드 실패", e)
            false
        }
    }

    // 🚨 파일 맨 아래쯤 아무데나 추가
    suspend fun deleteOrderOnSheet(orderNumber: String): Boolean {
        return try {
            val response = api.deleteOrder(
                com.example.namufarm.data.remote.DeleteOrderRequest(
                    order_number = orderNumber
                )
            )
            response.result.equals("success", ignoreCase = true)
        } catch (e: Exception) {
            android.util.Log.e("SyncManager", "주문 삭제 업로드 실패", e)
            false
        }
    }
}