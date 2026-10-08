package com.example.namufarm.data.repository

import com.example.namufarm.data.remote.WebQuoteApi
import com.example.namufarm.data.remote.WebQuoteValidator
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
import com.example.namufarm.domain.order.OrderLineSource
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
    val orderSyncCount: Int? = null,
    val errorMessage: String? = null
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
    private val sales get() = syncManager.salesGateway
    fun getAllOrders() = local.getAllOrders()
    fun getAllQuotes() = local.getAllQuotes()
    fun quotesByRequest(): Map<String, QuoteEntity> = local.getAllQuoteContacts().mapNotNull { contact ->
        val id = gson.fromJson(contact.recipientJson, com.example.namufarm.quotation.QuoteRecipient::class.java).sourceRequestId
        val quote = local.getQuoteByNumber(contact.quoteNumber)
        if (id.isNullOrBlank() || quote == null) null else id to quote
    }.sortedBy { it.second.quoteId ?: 0 }.toMap()
    private fun onDay(time: Long, date: String) = time > 0 && java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
    }.format(java.util.Date(time)) == date
    fun sentQuotesForDate(date: String) = local.getAllQuotes().filter { onDay(it.sentAt, date) }
    fun paidOrdersForDate(date: String) = local.getAllOrders().filter { onDay(it.paidAt, date) }
    suspend fun markQuoteSent(number: String): QuoteEntity = local.transaction {
        val quote = getQuoteByNumber(number) ?: error("견적을 찾을 수 없습니다.")
        if (quote.sentAt > 0) quote else quote.copy(sentAt = System.currentTimeMillis()).also { replaceQuote(it) }
    }
    fun getOrder(number: String) = local.getOrderByNumber(number)
    suspend fun initializePrivacy() = local.protectLegacyCustomerCopies()
    suspend fun cleanupPrivacy() = local.cleanupPrivacy()
    suspend fun closeLocalRequest(id: String) = local.transaction { advanceWebCursor(webRequestCursor());purgeRequestCopies(id) }
    private fun requireNoLegacyCommerce() { check(sales == null || local.getAllPendingSync().none { it.syncType in listOf(PendingSyncType.ORDER,PendingSyncType.CANCEL_ORDER,PendingSyncType.DELIVERY) }) { "이전 주문 전송을 먼저 동기화해주세요." } }

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
    suspend fun insertQuoteWithContact(quote: QuoteEntity, recipient: com.example.namufarm.quotation.QuoteRecipient, options: com.example.namufarm.quotation.QuoteIssueOptions? = null, replaceDraft: Boolean = false) = local.transaction {
        val contact = com.example.namufarm.data.QuoteContactEntity(quote.quoteNumber, Gson().toJson(recipient.checked()))
        if (replaceDraft) {
            val previous = getQuoteByNumber(quote.quoteNumber) ?: error("수정할 견적을 찾을 수 없습니다.")
            check(previous.sentAt == 0L && getQuoteDocument(quote.quoteNumber) == null && getAllOrders().none { it.sourceQuoteNumber == quote.quoteNumber }) { "이미 발송·발행·주문한 견적은 수정할 수 없습니다." }
            check(this@NamuFarmRepository.getQuoteRecipient(quote.quoteNumber).sourceRequestId == recipient.sourceRequestId) { "원래 견적 요청을 유지해주세요." }
            val lines = OrderSummaryCodec.parseStrict(quote.itemSummary)
            check(lines.all { it.source == OrderLineSource.MANUAL } && OrderSummaryCodec.parseStrict(previous.itemSummary).all { it.source == OrderLineSource.MANUAL }) { "수기 견적만 수정할 수 있습니다." }
            val amounts = OrderAmounts.calculate(lines, quote.isPrepaid, quote.shippingFee)
            require(quote.totalQty == amounts.quantity && quote.totalPrice == amounts.price)
            replaceQuote(quote.copy(quoteId = previous.quoteId, sentAt = 0))
            replaceQuoteContact(contact)
        } else {
            this@NamuFarmRepository.insertQuote(quote)
            insertQuoteContact(contact)
        }
        if (options != null) {
            val document = com.example.namufarm.quotation.QuoteDocument.create(quote, recipient, options.issuer,
                options.taxAmount(), options.validUntil, options.notes, options.account)
            insertQuoteDocument(com.example.namufarm.data.QuoteDocumentEntity(document.id, quote.quoteNumber, document.number, document.issuedAt, document.json()))
        }
    }
    fun getSavedQuote(number: String) = local.getQuoteByNumber(number)
    fun getQuoteRecipient(number: String): com.example.namufarm.quotation.QuoteRecipient = local.getQuoteContact(number)?.let {
        Gson().fromJson(it.recipientJson, com.example.namufarm.quotation.QuoteRecipient::class.java).checked()
    } ?: com.example.namufarm.quotation.QuoteRecipient()
    fun getQuoteDocument(number: String) = local.getQuoteDocument(number)
    fun getQuoteDocuments() = local.getQuoteDocuments()
    suspend fun issueQuoteDocument(number: String, issuer: com.example.namufarm.quotation.QuoteIssuer, tax: Long, validUntil: String, notes: String, account: String, recipient: com.example.namufarm.quotation.QuoteRecipient? = null): com.example.namufarm.data.QuoteDocumentEntity = local.transaction {
        getQuoteDocument(number)?.let { return@transaction it }
        val quote = checkNotNull(getQuoteByNumber(number)) { "저장된 견적이 없습니다. 먼저 견적을 저장해주세요." }
        val document = com.example.namufarm.quotation.QuoteDocument.create(quote, recipient ?: this@NamuFarmRepository.getQuoteRecipient(number), issuer, tax, validUntil, notes, account)
        val record = com.example.namufarm.data.QuoteDocumentEntity(document.id, number, document.number, document.issuedAt, document.json())
        insertQuoteDocument(record)
        record
    }
    suspend fun deleteQuote(quote: QuoteEntity) = local.transaction {
        val stored = getQuoteByNumber(quote.quoteNumber) ?: error("견적을 찾을 수 없습니다.")
        check(stored.sentAt == 0L && getAllOrders().none { it.sourceQuoteNumber == stored.quoteNumber }) { "발송·주문 기록은 삭제할 수 없습니다." }
        deleteQuote(stored)
    }

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

    suspend fun confirmOrder(quote: QuoteEntity, newOrder: OrderEntity, paid: Boolean = false): CloudWriteResult {
        val pendingId = local.transaction {
            requireNoLegacyCommerce()
            val stored = getQuoteById(quote.quoteId ?: error("저장된 견적이 아닙니다."))
                ?: error("이미 주문으로 확정되었거나 삭제된 견적입니다.")
            require(stored == quote) { "견적이 변경되었습니다. 다시 열어주세요." }
            check(getAllOrders().none { it.sourceQuoteNumber == stored.quoteNumber }) { "이미 주문으로 등록한 견적입니다. 주문 내역을 확인해주세요." }
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
            val contact = getQuoteContact(stored.quoteNumber)?.let { gson.fromJson(it.recipientJson, com.example.namufarm.quotation.QuoteRecipient::class.java) }
            val doc = getQuoteDocument(stored.quoteNumber)?.snapshotJson
            check(contact?.sourceRequestId.isNullOrBlank() || getAllOrders().none {
                it.sourceRequestId == contact?.sourceRequestId && it.status != OrderStatus.CANCELED
            }) { "이 접수번호는 이미 주문 처리되었습니다." }
            val tax = doc?.let { gson.fromJson(it, com.example.namufarm.quotation.QuoteDocument::class.java).taxAmount } ?: 0
            val now = System.currentTimeMillis()
            val order = newOrder.copy(
                itemSummary = stored.itemSummary, customerPhone = stored.customerPhone,
                totalQty = totals.quantity, totalPrice = totals.price,
                isPrepaid = stored.isPrepaid, shippingFee = stored.shippingFee,
                status = if(sales != null) OrderStatus.PURCHASED else OrderStatus.CONFIRMED,
                customerName=contact?.customerName.orEmpty(), customerEmail=contact?.customerEmail.orEmpty(),
                sourceRequestId=contact?.sourceRequestId.orEmpty(), sourceQuoteNumber=stored.quoteNumber,
                revision=if(sales!=null) 1 else 0, purchasedAt=now,updatedAt=now,
                costTotal=lines.fold(0L) { sum,line -> Math.addExact(sum,Math.multiplyExact(line.wholesalePrice.toLong(),line.orderQty.toLong())) },
                costConfirmed=false,taxAmount=tax
            )
            insertOrder(order)
            deductInventoryStrict(stored.itemSummary)
            if(sales != null) {
                var last = enqueueSale(order, doc)
                if (paid) {
                    val confirmed = order.copy(orderId = getOrderByNumber(order.orderNumber)!!.orderId, status = OrderStatus.CONFIRMED, revision = 2, paidAt = now)
                    upsertOrder(confirmed)
                    last = enqueueSale(confirmed, doc)
                }
                lines.filter { it.isInventoryBacked }.forEach { line ->
                    val farm=checkNotNull(getFarmByName(line.farmName)); val plant=checkNotNull(getSpeciesByName(line.speciesName))
                    val entity=getInventoryEntity(farm.farmId!!,plant.speciesId!!,line.quality,line.age,line.size,line.plantType)
                        ?: FarmInventoryEntity(farm.farmId!!,plant.speciesId!!,line.quality,line.age,line.size,line.plantType,0,line.wholesalePrice,line.retailPrice)
                    last=enqueue(PendingSyncType.INVENTORY,PendingInventorySyncPayload("save",entity,farm.farmName,plant.speciesName))
                }
                return@transaction last
            }
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
            ensureSecureOrder(orderNumber)
            val localWork = local.transaction {
                requireNoLegacyCommerce()
                val currentOrder = getOrderByNumber(orderNumber)
                    ?: error("주문을 찾을 수 없습니다: $orderNumber")

                if (currentOrder.status == OrderStatus.CANCELED) {
                    return@transaction LocalCancelWork(alreadyCanceled = true)
                }

                check(OrderStatus.canCancel(currentOrder.status)) { "배송 이후에는 주문취소할 수 없습니다." }
                val lines = OrderSummaryCodec.parseStrict(currentOrder.itemSummary)

                // 원격에서도 주문취소가 재고복원보다 먼저 처리되도록 먼저 큐에 적재한다.
                val canceled = currentOrder.copy(status=OrderStatus.CANCELED,revision=Math.addExact(currentOrder.revision,1),updatedAt=maxOf(System.currentTimeMillis(),currentOrder.updatedAt))
                val cancelPendingId = if(sales!=null) enqueueSale(canceled) else enqueue(PendingSyncType.CANCEL_ORDER,PendingCancelOrderSyncPayload(orderNumber))

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

                if(sales!=null) upsertOrder(canceled) else updateOrderStatus(orderNumber, OrderStatus.CANCELED)

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
        ensureSecureOrder(orderNumber)
        val pendingId = local.transaction {
            requireNoLegacyCommerce()
            val old = getOrderByNumber(orderNumber) ?: error("주문을 찾을 수 없습니다.")
            check(old.status !in listOf(OrderStatus.CANCELED,OrderStatus.COMPLETED)) { "종료된 주문의 배송정보는 변경할 수 없습니다." }
            if(sales != null) {
                val updated=old.copy(receiverName=receiverName,zipNo=zipNo,roadAddress=roadAddress,detailAddress=detailAddress,note=note,
                    revision=Math.addExact(old.revision,1),updatedAt=maxOf(System.currentTimeMillis(),old.updatedAt))
                val id=enqueueSale(updated); upsertOrder(updated); id
            } else {
                updateOrderDeliveryInfo(orderNumber,receiverName,zipNo,roadAddress,detailAddress,note)
                enqueue(PendingSyncType.DELIVERY,PendingDeliverySyncPayload(orderNumber,receiverName,zipNo,roadAddress,detailAddress,note))
            }
        }

        return flushThrough(pendingId)
    }

    private fun enqueueSale(order: OrderEntity, document: String? = local.getQuoteDocument(order.sourceQuoteNumber)?.snapshotJson): Int =
        enqueue(PendingSyncType.SALE, checkNotNull(sales).prepare(order,java.util.UUID.randomUUID().toString(),document))

    /** First migrate a legacy local order without guessing any new payment/delivery confirmation. */
    private suspend fun ensureSecureOrder(number: String) {
        if(sales==null) return
        val id=local.transaction {
            requireNoLegacyCommerce()
            val old=getOrderByNumber(number) ?: error("주문을 찾을 수 없습니다.")
            if(old.revision>0) return@transaction null
            val initial=com.example.namufarm.sales.SalePolicy.legacy(old)
            val pending=enqueueSale(initial); upsertOrder(initial); pending
        }
        if(id!=null) flushThrough(id)
    }
    suspend fun updateSaleStatus(number: String, expectedRevision: Long, target: String): CloudWriteResult {
        ensureSecureOrder(number)
        val id=local.transaction {
            requireNoLegacyCommerce()
            val old=getOrderByNumber(number) ?: error("주문을 찾을 수 없습니다.")
            check(old.revision==expectedRevision || expectedRevision==0L && old.revision==1L) { "다른 변경이 반영됐습니다. 주문을 다시 열어주세요." }
            val updated=com.example.namufarm.sales.SalePolicy.transition(old,target,maxOf(System.currentTimeMillis(),old.updatedAt))
            val pending=enqueueSale(updated); upsertOrder(updated); pending
        }
        return flushThrough(id)
    }
    suspend fun updateSaleCosts(number: String, expectedRevision: Long, cost: Long, expense: Long, mode: String, basisPoints: Int, confirmed: Boolean): CloudWriteResult {
        ensureSecureOrder(number)
        val id=local.transaction {
            requireNoLegacyCommerce()
            val old=getOrderByNumber(number) ?: error("주문을 찾을 수 없습니다.")
            check(old.revision==expectedRevision || expectedRevision==0L && old.revision==1L) { "주문을 다시 열어주세요." }
            check(OrderStatus.next(old.status)!=null) { "종료된 주문의 금액은 변경할 수 없습니다." }
            val updated=old.copy(costTotal=cost,expenseTotal=expense,profitMode=mode,commissionBps=basisPoints,costConfirmed=confirmed,
                revision=Math.addExact(old.revision,1),updatedAt=maxOf(System.currentTimeMillis(),old.updatedAt))
            com.example.namufarm.sales.SalePolicy.metadata(updated)
            val pending=enqueueSale(updated); upsertOrder(updated); pending
        }
        return flushThrough(id)
    }
    suspend fun salesReport(granularity: String, from: String, to: String) = local.serializedSync {
        val drained=drainPending(salesOnly = true); check(drained.error==null) { drained.error ?: "전송 대기 중입니다." }
        check(local.getAllPendingSync().none { it.syncType == PendingSyncType.SALE }) { "주문 전송 대기 중입니다." }
        checkNotNull(sales) { "보안 판매 연결을 먼저 설정해주세요." }.report(granularity,from,to)
    }

    private data class DrainResult(val sent: Int, val error: String? = null)

    // Called only while holding the database's shared network mutex. New local writes
    // can commit immediately; each invocation processes a bounded snapshot of the queue.
    private suspend fun drainPending(throughId: Int = Int.MAX_VALUE, salesOnly: Boolean = false): DrainResult {
        var sent = 0
        for (pending in local.getAllPendingSync().filter { checkNotNull(it.syncId) <= throughId && (!salesOnly || it.syncType == PendingSyncType.SALE) }) {
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
            val result = drainPending(pendingId, local.getPendingSyncById(pendingId)?.syncType == PendingSyncType.SALE)
            CloudWriteResult(local.getPendingSyncById(pendingId) == null, result.error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The local transaction has already committed. Keep its outbox for retry.
            CloudWriteResult(false, e.message ?: "동기화 대기")
        }
    }

    fun getWebRequests() = local.getWebRequests()
    suspend fun markWebRequestRead(id: String) = local.transaction { markWebRequestRead(id, System.currentTimeMillis()) }

    /** Immutable enquiries are independent of the legacy inventory outbox. Commit rows and cursor together. */
    suspend fun syncWebRequests(api: WebQuoteApi, url: String, deviceToken: String, decrypt: ((com.example.namufarm.data.remote.WebQuoteRemote) -> com.example.namufarm.data.remote.WebQuoteRemote)? = null): Int = local.serializedSync {
        var imported = 0
        repeat(20) {
            val cursor = local.webRequestCursor()
            val response = api.request(url, mapOf("action" to "list_requests", "deviceToken" to deviceToken, "afterSequence" to cursor))
            val page = WebQuoteValidator.checkedPage(response, cursor, decrypt)
            local.transaction {
                insertWebRequests(page.rows)
                page.expiredIds.forEach { purgeRequestCopies(it) }
                advanceWebCursor(page.cursor)
            }
            imported += page.rows.size
            if (response.hasMore == false) return@serializedSync imported
        }
        imported // Bounded work; the next refresh resumes from the committed Room cursor.
    }

    suspend fun syncAll(): GeneralSyncResult = local.serializedSync {
        val drained = drainPending()
        val remaining = local.getAllPendingSync().size
        if (drained.error != null || remaining > 0) {
            return@serializedSync GeneralSyncResult(
                drained.sent, remaining.coerceAtLeast(1),
                errorMessage = drained.error ?: "동기화 중 새 로컬 변경이 생겼습니다. 다시 동기화해주세요."
            )
        }
        val snapshot = syncManager.syncSnapshot()
        GeneralSyncResult(drained.sent, 0, snapshot.inventoryResult, snapshot.orderCount)
    }

    suspend fun syncCommerce(): GeneralSyncResult = local.serializedSync {
        val drained = drainPending(salesOnly = true)
        val remaining = local.getAllPendingSync().count { it.syncType == PendingSyncType.SALE }
        if (drained.error != null || remaining > 0) GeneralSyncResult(drained.sent, remaining.coerceAtLeast(1), errorMessage = drained.error)
        else GeneralSyncResult(drained.sent, 0, orderSyncCount = syncManager.syncSecureOrders())
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
