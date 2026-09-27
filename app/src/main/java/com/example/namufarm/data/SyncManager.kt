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

            // 로컬 우선 정책: Pull 시 전체 삭제하지 않고 원격 항목만 upsert 한다.
            // 오프라인에서 생성된 로컬 농원이 pending 상태일 수 있으므로 보존한다.
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

            // 농원과 동일하게 로컬 미전송 데이터를 보호하기 위해 전체 삭제하지 않는다.
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

            var upserted = 0
            var skipped = 0

            // pending push가 모두 성공한 뒤에만 호출되는 Pull이므로,
            // 원격 재고를 현재 스냅샷으로 보고 로컬 재고만 교체한다.
            dao.clearAllInventory()

            val farmMap = dao.getAllFarms()
                .associateBy { it.farmName.trim() }

            val speciesMap = dao.getAllSpecies()
                .associateBy { it.speciesName.trim() }

            remoteData.forEach { dto ->
                val farmName = dto.farm_name.trim()
                val speciesName = dto.species_name.trim()

                val farmId = farmMap[farmName]?.farmId
                val speciesId = speciesMap[speciesName]?.speciesId

                // 재고 Pull이 농원/묘목 마스터를 임의 생성하지 않도록 한다.
                // 마스터가 없으면 해당 행을 스킵하고 별도 마스터 동기화가 필요하다.
                if (farmId == null || speciesId == null) {
                    skipped++
                    Log.w(
                        "SyncManager",
                        "재고 스킵: 마스터 없음 farm='$farmName', species='$speciesName'"
                    )
                    return@forEach
                }

                dao.upsertInventory(
                    FarmInventoryEntity(
                        farmId = farmId,
                        speciesId = speciesId,
                        quality = dto.quality.trim(),
                        age = dto.age.trim(),
                        size = dto.size.trim(),
                        plantType = dto.plant_type.trim(),
                        quantity = dto.quantity,
                        wholesalePrice = dto.wholesale_price,
                        retailPrice = dto.retail_price
                    )
                )
                upserted++
            }

            SyncResult(
                total = remoteData.size,
                upserted = upserted,
                skipped = skipped,
                insertedFarms = 0,
                insertedSpecies = 0
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
                val orderNumber = dto.order_number?.trim().orEmpty()
                if (orderNumber.isBlank()) return@forEach

                val existing = dao.getOrderByNumber(orderNumber)

                if (existing != null) {
                    // 이미 폰에 존재하는 주문은 로컬의 구조화된 주문상세/배송비/주소를 보존한다.
                    // 시트에서 안전하게 되가져올 수 있는 상태/기본 주문 정보만 반영한다.
                    dao.upsertOrder(
                        existing.copy(
                            orderDate = dto.date ?: existing.orderDate,
                            customerPhone = dto.customer_phone ?: existing.customerPhone,
                            totalQty = dto.total_qty ?: existing.totalQty,
                            totalPrice = dto.total_price ?: existing.totalPrice,
                            receiverName = dto.receiver_name ?: existing.receiverName,
                            note = dto.note ?: existing.note,
                            status = dto.status?.takeIf { it.isNotBlank() } ?: existing.status
                        )
                    )
                } else {
                    // 다른 기기/시트에서만 존재하는 주문은 새 주문으로 가져온다.
                    // 현재 시트 API는 주소를 한 문자열로 내려주므로 roadAddress에 보관한다.
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
                            status = dto.status?.takeIf { it.isNotBlank() } ?: "입금확인"
                        )
                    )
                }

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