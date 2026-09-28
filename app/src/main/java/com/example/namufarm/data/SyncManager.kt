package com.example.namufarm.data

import android.util.Log
import com.example.namufarm.data.local.LocalDataSource
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
import com.example.namufarm.data.remote.UpdateOrderDeliveryRequest
import com.google.gson.Gson

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
    private val local: LocalDataSource,
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

                    // 이전 전송이 서버에서는 성공했지만 응답만 유실된 경우
                    // 같은 주문을 재업로드해 재고를 두 번 차감하지 않도록 1차 방어한다.
                    when (orderExistsOnSheet(payload.order.orderNumber)) {
                        true -> true
                        false -> uploadOrderToSheet(
                            orderEntity = payload.order,
                            itemsToDeduct = payload.items
                        )
                        null -> false
                    }
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

            val protectedFarmIds = local.getAllPendingSync().mapNotNull { pending ->
                when (pending.syncType) {
                    PendingSyncType.FARM_SAVE -> gson.fromJson(
                        pending.payloadJson,
                        PendingFarmSyncPayload::class.java
                    ).farm.farmId

                    PendingSyncType.FARM_DELETE -> gson.fromJson(
                        pending.payloadJson,
                        PendingDeleteFarmSyncPayload::class.java
                    ).farmId

                    else -> null
                }
            }.toSet()

            local.transaction {
                // 아직 시트로 못 보낸 로컬 농원 변경은 Pull로 덮어쓰지 않는다.
                farms.sortedBy { it.farm_id }.forEach { dto ->
                    if (dto.farm_id in protectedFarmIds) return@forEach

                    local.upsertFarm(
                        FarmEntity(
                            farmId = dto.farm_id,
                            farmName = dto.farm_name,
                            address = dto.address.ifBlank { null },
                            phone = dto.phone.ifBlank { null },
                            memo = dto.memo.ifBlank { null }
                        )
                    )
                }
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

            val protectedSpeciesIds = local.getAllPendingSync().mapNotNull { pending ->
                when (pending.syncType) {
                    PendingSyncType.SPECIES_SAVE -> gson.fromJson(
                        pending.payloadJson,
                        PendingSpeciesSyncPayload::class.java
                    ).species.speciesId

                    PendingSyncType.SPECIES_DELETE -> gson.fromJson(
                        pending.payloadJson,
                        PendingDeleteSpeciesSyncPayload::class.java
                    ).speciesId

                    else -> null
                }
            }.toSet()

            local.transaction {
                // 아직 시트로 못 보낸 로컬 묘목 변경은 Pull로 덮어쓰지 않는다.
                speciesList.sortedBy { it.species_id }.forEach { dto ->
                    if (dto.species_id in protectedSpeciesIds) return@forEach

                    local.upsertSpecies(
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

            local.transaction {
                // pending push가 모두 성공한 뒤에만 호출되는 Pull이므로,
                // 원격 재고를 현재 스냅샷으로 보고 로컬 재고만 교체한다.
                local.clearAllInventory()
    
                val farmMap = local.getAllFarms()
                    .associateBy { it.farmName.trim() }
    
                val speciesMap = local.getAllSpecies()
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
    
                    local.upsertInventory(
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

    private suspend fun orderExistsOnSheet(orderNumber: String): Boolean? {
        return try {
            api.getOrdersData("get_orders").any {
                it.order_number?.trim() == orderNumber.trim()
            }
        } catch (e: Exception) {
            // 확인 실패를 "주문 없음"으로 취급하면 중복 주문을 만들 수 있으므로
            // null을 반환해 이번 재전송 자체를 보류한다.
            Log.e("SyncManager", "주문 중복 확인 실패", e)
            null
        }
    }

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
                raw_summary = orderEntity.itemSummary,
                status = orderEntity.status,
                is_prepaid = orderEntity.isPrepaid,
                shipping_fee = orderEntity.shippingFee,
                receiver_name = orderEntity.receiverName,
                zip_no = orderEntity.zipNo,
                road_address = orderEntity.roadAddress,
                detail_address = orderEntity.detailAddress,
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
                    zip_no = zipNo,
                    road_address = roadAddress,
                    detail_address = detailAddress,
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

            local.transaction {
                remoteOrders.forEach { dto ->
                    val orderNumber = dto.order_number?.trim().orEmpty()
                    if (orderNumber.isBlank()) return@forEach
    
                    val existing = local.getOrderByNumber(orderNumber)
    
                    if (existing != null) {
                        // 이미 폰에 존재하는 주문은 로컬의 구조화된 주문상세/배송비/주소를 보존한다.
                        // 시트에서 안전하게 되가져올 수 있는 상태/기본 주문 정보만 반영한다.
                        local.upsertOrder(
                            existing.copy(
                                orderDate = dto.date ?: existing.orderDate,
                                customerPhone = dto.customer_phone ?: existing.customerPhone,
                                totalQty = dto.total_qty ?: existing.totalQty,
                                totalPrice = dto.total_price ?: existing.totalPrice,
                                receiverName = dto.receiver_name ?: existing.receiverName,
                                zipNo = dto.zip_no?.takeIf { it.isNotBlank() } ?: existing.zipNo,
                                roadAddress = dto.road_address?.takeIf { it.isNotBlank() }
                                    ?: existing.roadAddress,
                                detailAddress = dto.detail_address?.takeIf { it.isNotBlank() }
                                    ?: existing.detailAddress,
                                note = dto.note ?: existing.note,
                                status = dto.status?.takeIf { it.isNotBlank() } ?: existing.status,
                                isPrepaid = dto.is_prepaid ?: existing.isPrepaid,
                                shippingFee = dto.shipping_fee ?: existing.shippingFee
                            )
                        )
                    } else {
                        // 다른 기기/시트에서만 존재하는 주문은 새 주문으로 가져온다.
                        // 현재 시트 API는 주소를 한 문자열로 내려주므로 roadAddress에 보관한다.
                        local.insertOrder(
                            OrderEntity(
                                orderNumber = orderNumber,
                                orderDate = dto.date ?: "",
                                customerPhone = dto.customer_phone ?: "",
                                totalQty = dto.total_qty ?: 0,
                                totalPrice = dto.total_price ?: 0,
                                itemSummary = dto.raw_summary?.takeIf { it.isNotBlank() }
                                    ?: dto.summary.orEmpty(),
                                receiverName = dto.receiver_name ?: "",
                                zipNo = dto.zip_no ?: "",
                                roadAddress = dto.road_address?.takeIf { it.isNotBlank() }
                                    ?: dto.address.orEmpty(),
                                detailAddress = dto.detail_address ?: "",
                                note = dto.note ?: "",
                                status = dto.status?.takeIf { it.isNotBlank() }
                                    ?: OrderStatus.CONFIRMED,
                                isPrepaid = dto.is_prepaid ?: false,
                                shippingFee = dto.shipping_fee ?: 0
                            )
                        )
                    }
    
                    syncedCount++
                }
            }

            syncedCount
        } catch (e: Exception) {
            Log.e("SyncManager", "주문내역 동기화 실패", e)
            -1
        }
    }


}