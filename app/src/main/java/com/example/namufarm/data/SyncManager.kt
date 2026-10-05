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
import kotlinx.coroutines.CancellationException
import com.example.namufarm.data.remote.OrderRemoteMapper
import com.example.namufarm.data.repository.MasterReferenceGuard
import com.example.namufarm.domain.order.OrderSummaryCodec

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

data class SnapshotSyncResult(val inventoryResult: SyncResult, val orderCount: Int)

class SyncManager(
    private val local: LocalDataSource,
    private val api: GoogleSheetApi,
    val salesGateway: com.example.namufarm.sales.SalesGateway? = null
) {
    private val gson = Gson()

    suspend fun processPendingSync(item: PendingSyncEntity): PendingSyncProcessResult {
        return try {
            if (salesGateway != null && item.syncType in listOf(PendingSyncType.ORDER, PendingSyncType.CANCEL_ORDER, PendingSyncType.DELIVERY)) {
                return processLegacyCommerce(item)
            }
            val ok = when (item.syncType) {
                PendingSyncType.SALE -> checkNotNull(salesGateway).save(gson.fromJson(item.payloadJson, com.example.namufarm.sales.PendingSaleSyncPayload::class.java))
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

                    val matching = api.getOrdersData("get_orders").filter {
                        it.order_number?.trim() == payload.order.orderNumber
                    }
                    check(matching.size <= 1) { "서버에 중복 주문번호가 있습니다." }
                    val existing = matching.singleOrNull()
                    if (existing != null) {
                        OrderRemoteMapper.requireSameOrder(payload.order, existing)
                        true
                    } else {
                        uploadOrderToSheet(payload.order, payload.items)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PendingSyncProcessResult(
                success = false,
                errorMessage = e.message ?: "알 수 없는 오류"
            )
        }
    }


    /** Freeze a migrated encrypted event in Room before any network write. A retry uses the same nonce. */
    private suspend fun processLegacyCommerce(item: PendingSyncEntity): PendingSyncProcessResult {
        val gateway = checkNotNull(salesGateway)
        val current = when (item.syncType) {
            PendingSyncType.ORDER -> com.example.namufarm.sales.SalePolicy.legacy(gson.fromJson(item.payloadJson, PendingOrderSyncPayload::class.java).order)
            else -> {
                val number = if(item.syncType==PendingSyncType.CANCEL_ORDER) gson.fromJson(item.payloadJson, PendingCancelOrderSyncPayload::class.java).orderNumber
                    else gson.fromJson(item.payloadJson, PendingDeliverySyncPayload::class.java).orderNumber
                val remote = gateway.orders().singleOrNull { it.orderNumber==number } ?: error("이전 주문을 먼저 보안 시트로 전송해주세요.")
                val now = maxOf(System.currentTimeMillis(),remote.updatedAt)
                if(item.syncType==PendingSyncType.CANCEL_ORDER) remote.copy(status=OrderStatus.CANCELED, revision=Math.addExact(remote.revision,1),updatedAt=now)
                else gson.fromJson(item.payloadJson, PendingDeliverySyncPayload::class.java).let { d -> remote.copy(receiverName=d.receiverName,zipNo=d.zipNo,roadAddress=d.roadAddress,detailAddress=d.detailAddress,note=d.note,revision=Math.addExact(remote.revision,1),updatedAt=now) }
            }
        }
        val payload = gateway.prepare(current, java.util.UUID.randomUUID().toString(),local.getQuoteDocument(current.sourceQuoteNumber)?.snapshotJson)
        local.transaction {
            check(getPendingSyncById(checkNotNull(item.syncId)) == item)
            replacePending(item.copy(syncType=PendingSyncType.SALE,payloadJson=gson.toJson(payload)))
            getOrderByNumber(current.orderNumber)?.let { old ->
                if(old.revision<=current.revision) upsertOrder(old.copy(orderDate=current.orderDate,revision=current.revision,
                    purchasedAt=current.purchasedAt,paidAt=current.paidAt,deliveredAt=current.deliveredAt,completedAt=current.completedAt,updatedAt=current.updatedAt,
                    profitMode=current.profitMode,commissionBps=current.commissionBps))
            }
            if(item.syncType==PendingSyncType.ORDER) {
                OrderSummaryCodec.parseStrict(current.itemSummary).filter { it.isInventoryBacked }.forEach { line ->
                    val farm=checkNotNull(getFarmByName(line.farmName)); val plant=checkNotNull(getSpeciesByName(line.speciesName))
                    val entity=getInventoryEntity(checkNotNull(farm.farmId),checkNotNull(plant.speciesId),line.quality,line.age,line.size,line.plantType)
                        ?: FarmInventoryEntity(farm.farmId!!,plant.speciesId!!,line.quality,line.age,line.size,line.plantType,0,line.wholesalePrice,line.retailPrice)
                    insertPendingSync(PendingSyncEntity(syncType=PendingSyncType.INVENTORY,payloadJson=gson.toJson(PendingInventorySyncPayload("save",entity,farm.farmName,plant.speciesName))))
                }
            }
        }
        return PendingSyncProcessResult(gateway.save(payload))
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SyncManager", "농원 삭제 시트 업로드 실패", e)
            false
        }
    }

    suspend fun syncFarmsWithGoogleSheet(): Int {
        return try {
            val farms = api.getFarmData("get_farms")

            require(farms.map { it.farm_id }.distinct().size == farms.size &&
                farms.map { it.farm_name.trim() }.distinct().size == farms.size) { "원격 농원 ID/이름 중복" }
            farms.forEach {
                OrderSummaryCodec.validateText(it.farm_name)
                require(it.farm_id > 0 && it.farm_name.isNotBlank() &&
                    it.farm_name.trim() != OrderSummaryCodec.MANUAL_FARM_LABEL) { "원격 농원 정보 오류" }
            }
            local.transaction {
                requireNoPending()
                farms.sortedBy { it.farm_id }.forEach { dto ->
                    val existing = getAllFarms().singleOrNull { it.farmId == dto.farm_id }
                    if (existing != null && existing.farmName != dto.farm_name.trim()) {
                        MasterReferenceGuard.farm(this, dto.farm_id)
                    }
                    check(getAllFarms().none { it.farmName.trim() == dto.farm_name.trim() && it.farmId != dto.farm_id }) {
                        "농원 ID/이름 충돌. 다중 기기 마스터를 확인해주세요."
                    }
                    local.upsertFarm(
                        FarmEntity(
                            farmId = dto.farm_id,
                            farmName = dto.farm_name.trim(),
                            address = dto.address.ifBlank { null },
                            phone = dto.phone.ifBlank { null },
                            memo = dto.memo.ifBlank { null }
                        )
                    )
                }
            }

            farms.size
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SyncManager", "묘목 삭제(비활성화) 시트 업로드 실패", e)
            false
        }
    }

    suspend fun syncSpeciesWithGoogleSheet(): Int {
        return try {
            val speciesList = api.getSpeciesData("get_species")

            require(speciesList.map { it.species_id }.distinct().size == speciesList.size &&
                speciesList.map { it.species_name.trim() }.distinct().size == speciesList.size) { "원격 묘목 ID/이름 중복" }
            speciesList.forEach {
                OrderSummaryCodec.validateText(it.species_name)
                require(it.species_id > 0 && it.species_name.isNotBlank() && it.is_active in 0..1) { "원격 묘목 정보 오류" }
            }
            local.transaction {
                requireNoPending()
                speciesList.sortedBy { it.species_id }.forEach { dto ->
                    val existing = getAllSpeciesIncludingInactive().singleOrNull { it.speciesId == dto.species_id }
                    if (existing != null && existing.speciesName != dto.species_name.trim()) {
                        MasterReferenceGuard.species(this, dto.species_id)
                    }
                    check(getAllSpeciesIncludingInactive().none {
                        it.speciesName.trim() == dto.species_name.trim() && it.speciesId != dto.species_id
                    }) { "묘목 ID/이름 충돌. 다중 기기 마스터를 확인해주세요." }
                    local.upsertSpecies(
                        SpeciesEntity(
                            speciesId = dto.species_id,
                            speciesName = dto.species_name.trim(),
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SyncManager", "묘목 동기화 실패", e)
            -1
        }
    }

    // ===== 재고 =====

    /** Fetch both collections first. Any bad row or concurrent local write aborts the whole apply. */
    suspend fun syncSnapshot(): SnapshotSyncResult {
        return try {
            val inventory = api.getInventoryData("get_inventory")
            val secureOrders = salesGateway?.orders()
            val orders = if (salesGateway == null) api.getOrdersData("get_orders") else emptyList()
            local.transaction {
                requireNoPending()
                val farms = getAllFarms().groupBy { it.farmName.trim() }
                val species = getAllSpeciesIncludingInactive().groupBy { it.speciesName.trim() }
                val rows = inventory.map { dto ->
                    val farm = farms[dto.farm_name.trim()]?.singleOrNull()
                        ?: error("농원 마스터 누락/중복: ${dto.farm_name}. 기존 재고를 보존했습니다.")
                    val plant = species[dto.species_name.trim()]?.singleOrNull()
                        ?: error("묘목 마스터 누락/중복: ${dto.species_name}. 기존 재고를 보존했습니다.")
                    val quantity = requireNotNull(dto.quantity) { "원격 재고 수량 필드 누락" }
                    val wholesale = requireNotNull(dto.wholesale_price) { "원격 재고 원가 필드 누락" }
                    val retail = requireNotNull(dto.retail_price) { "원격 재고 판매가 필드 누락" }
                    require(quantity >= 0 && wholesale >= 0 && retail >= 0) {
                        "원격 재고 수량/단가 오류"
                    }
                    listOf(dto.quality, dto.age, dto.size, dto.plant_type).forEach(OrderSummaryCodec::validateText)
                    FarmInventoryEntity(
                        farmId = checkNotNull(farm.farmId), speciesId = checkNotNull(plant.speciesId),
                        quality = dto.quality.trim(), age = dto.age.trim(), size = dto.size.trim(),
                        plantType = dto.plant_type.trim(), quantity = quantity,
                        wholesalePrice = wholesale, retailPrice = retail
                    )
                }
                val keys = rows.map { listOf(it.farmId, it.speciesId, it.quality, it.age, it.size, it.plantType) }
                check(keys.distinct().size == keys.size) { "원격 재고 키 중복. 기존 재고를 보존했습니다." }
                val numbers = orders.map { it.order_number?.trim().orEmpty() }
                check(numbers.none { it.isBlank() } && numbers.distinct().size == numbers.size) {
                    "원격 주문번호 누락/중복. 기존 데이터를 보존했습니다."
                }
                val merged = secureOrders?.map { incoming ->
                    val existing = getOrderByNumber(incoming.orderNumber)
                    if (existing != null && existing.revision > 0) {
                        check(incoming.revision >= existing.revision) { "서버 주문 상태가 이전 버전입니다. 기존 데이터를 보존했습니다." }
                        if (incoming.revision == existing.revision) check(incoming.copy(orderId=null) == existing.copy(orderId=null)) { "같은 버전의 주문 내용이 다릅니다." }
                        check(incoming.itemSummary == existing.itemSummary && incoming.totalPrice == existing.totalPrice && incoming.purchasedAt == existing.purchasedAt) { "주문 원본이 변경되었습니다." }
                    }
                    incoming.copy(orderId=existing?.orderId)
                } ?: orders.map { dto -> OrderRemoteMapper.merge(getOrderByNumber(dto.order_number!!.trim()), dto) }
                salesGateway?.issuedDocuments()?.forEach { incoming ->
                    val previous=getQuoteDocument(incoming.quoteNumber)
                    check(previous==null || previous==incoming) { "발행 견적서 원본이 변경되었습니다." }
                    if(previous==null) insertQuoteDocument(incoming)
                }
                // This transaction owns both stock and order status, including remote cancellations.
                clearAllInventory()
                rows.forEach { upsertInventory(it) }
                merged.forEach { upsertOrder(it) }
            }
            SnapshotSyncResult(SyncResult(inventory.size, inventory.size, 0, 0, 0), (secureOrders?.size ?: orders.size))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SyncManager", "재고/주문 동기화 실패", e)
            SnapshotSyncResult(SyncResult(0, 0, 0, 0, 0, e.message ?: "원격 데이터 동기화 실패"), -1)
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
        } catch (e: CancellationException) {
            throw e
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
            val expectedItems = OrderSummaryCodec.parseStrict(orderEntity.itemSummary)
                .filter { it.isInventoryBacked }.map { line ->
                    SheetOrderItemDto(line.farmName, line.speciesName, line.quality, line.age,
                        line.size, line.plantType, line.orderQty, line.retailPrice)
                }
            check(itemsToDeduct == expectedItems) { "pending 주문 품목과 원본이 일치하지 않습니다." }
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SyncManager", "배송정보 업로드 실패", e)
            false
        }
    }

}
