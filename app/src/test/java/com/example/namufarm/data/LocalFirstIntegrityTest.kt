package com.example.namufarm.data

import androidx.room.Room
import com.example.namufarm.data.local.LocalDataSource
import com.example.namufarm.data.remote.*
import com.example.namufarm.data.remote.SheetInventoryDto
import com.example.namufarm.data.repository.NamuFarmRepository
import com.example.namufarm.domain.order.OrderAmounts
import com.example.namufarm.domain.order.OrderSummaryCodec
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalFirstIntegrityTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ManagementDao
    private lateinit var local: LocalDataSource
    private lateinit var api: FakeSheetApi
    private lateinit var repository: NamuFarmRepository
    private val stock = FarmInventoryEntity(1, 1, "상묘", "1년생", "1.0m", "실생", 10, 500, 1000)
    private val inventoryLine = "소나무|상묘|1년생|1.0m|실생|3|1000|농원|500"
    private val manualLine = "감나무|||||2|2000|수기견적|0"

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.managementDao()
        local = LocalDataSource(db, dao)
        api = FakeSheetApi()
        val juso = object : JusoApi {
            override suspend fun searchAddress(apiKey: String, keyword: String, resultType: String, count: Int): JusoResponse =
                error("not used")
        }
        repository = NamuFarmRepository(local, SyncManager(local, api), juso)
        dao.upsertFarm(FarmEntity(1, "농원"))
        dao.upsertSpecies(SpeciesEntity(1, "소나무"))
        dao.upsertInventory(stock)
        api.inventory = listOf(remoteStock(10))
    }
    @After fun close() { db.close() }

    private fun remoteStock(qty: Int) = SheetInventoryDto("농원", "소나무", "상묘", "1년생", "1.0m", "실생", qty, 500, 1000)
    private fun quantity() = dao.getInventoryEntity(1, 1, "상묘", "1년생", "1.0m", "실생")?.quantity ?: 0
    private fun quote(summary: String = inventoryLine): QuoteEntity {
        val amounts = OrderAmounts.calculate(OrderSummaryCodec.parseStrict(summary), true, 100)
        repository.insertQuote(QuoteEntity(
            isPrepaid = true, shippingFee = 100, quoteNumber = "Q", saveDate = "2026-09-29",
            customerPhone = "01000000000", totalQty = amounts.quantity, totalPrice = amounts.price, itemSummary = summary
        ))
        return dao.getQuotesForDate("2026-09-29").first()
    }
    private fun order(q: QuoteEntity, number: String = "O") = OrderEntity(
        orderNumber = number, orderDate = "2026-09-29", customerPhone = q.customerPhone,
        totalQty = q.totalQty, totalPrice = q.totalPrice, itemSummary = q.itemSummary,
        isPrepaid = q.isPrepaid, shippingFee = q.shippingFee
    )

    @Test fun failureAfterStockDeductionRollsBackOrderQuoteStockAndOutbox() = runBlocking {
        val q = quote()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_pending BEFORE INSERT ON pending_sync BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertTrue(runCatching { repository.confirmOrder(q, order(q)) }.isFailure)
        assertEquals(10, quantity())
        assertNotNull(dao.getQuoteById(q.quoteId!!))
        assertNull(dao.getOrderByNumber("O"))
        assertTrue(dao.getAllPendingSync().isEmpty())
    }

    @Test fun insufficientStockRollsBackAnEarlierLineDeduction() = runBlocking {
        val q = quote("$inventoryLine\n${inventoryLine.replace("|3|", "|9|")}")
        assertTrue(runCatching { repository.confirmOrder(q, order(q)) }.isFailure)
        assertEquals(10, quantity())
        assertNotNull(dao.getQuoteById(q.quoteId!!))
        assertNull(dao.getOrderByNumber("O"))
        assertTrue(dao.getAllPendingSync().isEmpty())
    }

    @Test fun sameQuoteCannotDeductTwiceEvenWithDifferentOrderNumbers() = runBlocking {
        val q = quote()
        repository.confirmOrder(q, order(q))
        assertTrue(runCatching { repository.confirmOrder(q, order(q, "O2")) }.isFailure)
        assertEquals(7, quantity())
        assertEquals(1, dao.getOrdersForDate("2026-09-29").size)
        assertEquals(1, dao.getAllPendingSync().size)
    }

    @Test fun sameOrderNumberCannotConsumeASecondQuote() = runBlocking {
        val first = quote()
        repository.confirmOrder(first, order(first))
        val second = quote()
        assertTrue(runCatching { repository.confirmOrder(second, order(second)) }.isFailure)
        assertEquals(7, quantity())
        assertNotNull(dao.getQuoteById(second.quoteId!!))
    }

    @Test fun manualAndInventoryLinesRemainSeparateOnConfirmAndRepeatedCancel() = runBlocking {
        val q = quote("$inventoryLine\n$manualLine")
        repository.confirmOrder(q, order(q).copy(itemSummary = "untrusted UI summary", totalQty = 999))
        assertEquals(7, quantity())
        val pending = Gson().fromJson(dao.getAllPendingSync().first().payloadJson, PendingOrderSyncPayload::class.java)
        assertEquals(q.itemSummary, pending.order.itemSummary)
        assertEquals(1, pending.items.size)
        assertEquals("농원", pending.items.single().farm_name)
        val canceled = repository.cancelOrder("O")
        assertTrue(canceled.success)
        assertEquals(3, canceled.restoredQuantity)
        assertEquals(10, quantity())
        val count = dao.getAllPendingSync().size
        assertTrue(repository.cancelOrder("O").alreadyCanceled)
        assertEquals(count, dao.getAllPendingSync().size)
        assertEquals(10, quantity())
        assertEquals(OrderStatus.CANCELED, dao.getOrderByNumber("O")!!.status)
    }

    @Test fun manualOnlyOrderCreatesNoInventoryRestorationTask() = runBlocking {
        val q = quote(manualLine)
        repository.confirmOrder(q, order(q))
        assertEquals(10, quantity())
        assertEquals(0, repository.cancelOrder("O").restoredQuantity)
        assertEquals(listOf(PendingSyncType.ORDER, PendingSyncType.CANCEL_ORDER), dao.getAllPendingSync().map { it.syncType })
    }

    @Test fun cancelRecreatesSoldOutStock() = runBlocking {
        val q = quote(inventoryLine.replace("|3|", "|10|"))
        repository.confirmOrder(q, order(q))
        assertEquals(0, quantity())
        assertTrue(repository.cancelOrder("O").success)
        assertEquals(10, quantity())
    }

    @Test fun failedCancellationRollsBackAllRestorationAndPending() = runBlocking {
        val q = quote()
        repository.confirmOrder(q, order(q))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_restore BEFORE INSERT ON pending_sync WHEN NEW.sync_type = 'inventory' BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertFalse(repository.cancelOrder("O").success)
        assertEquals(7, quantity())
        assertEquals(OrderStatus.CONFIRMED, dao.getOrderByNumber("O")!!.status)
        assertEquals(listOf(PendingSyncType.ORDER), dao.getAllPendingSync().map { it.syncType })
    }

    @Test fun restorationOverflowFailsWithoutChangingOrderOrPending() = runBlocking {
        val q = quote()
        repository.confirmOrder(q, order(q))
        dao.upsertInventory(stock.copy(quantity = Int.MAX_VALUE))
        assertFalse(repository.cancelOrder("O").success)
        assertEquals(Int.MAX_VALUE, quantity())
        assertEquals(OrderStatus.CONFIRMED, dao.getOrderByNumber("O")!!.status)
        assertEquals(1, dao.getAllPendingSync().size)
    }

    @Test fun newWritesNeverBypassAnOlderFailureAndPullStops() = runBlocking {
        repository.saveFarm(null, "새농원", "", "", "")
        api.online = true
        api.failAction = "save_farm"
        api.events.clear()
        assertFalse(repository.saveInventory(stock.copy(quantity = 8), "농원", "소나무").cloudSynced)
        assertEquals(listOf("save_farm"), api.events.toList())
        api.events.clear()
        assertTrue(repository.syncAll().pendingFailureCount > 0)
        assertEquals(listOf("save_farm"), api.events.toList())
        assertEquals(8, quantity())
        assertEquals(2, dao.getAllPendingSync().size)
    }

    @Test fun cancellationCannotOvertakeItsUnsentOrder() = runBlocking {
        val q = quote()
        repository.confirmOrder(q, order(q))
        api.online = true
        api.failAction = "order"
        api.events.clear()
        assertTrue(repository.cancelOrder("O").success)
        assertFalse(api.events.contains("cancel_order"))
        assertFalse(api.events.contains("save"))
        assertEquals(listOf("order", "cancel_order", "inventory"), dao.getAllPendingSync().map { it.syncType })
    }

    @Test fun offlineConfirmCancelReplaysInOrderBeforeAtomicPull() = runBlocking {
        val q = quote()
        repository.confirmOrder(q, order(q))
        repository.cancelOrder("O")
        api.online = true
        api.events.clear()
        val result = repository.syncAll()
        assertEquals(3, result.resentSuccess)
        assertEquals(listOf("get_orders", "order", "cancel_order", "save", "get_inventory", "get_orders"), api.events.toList())
        assertEquals(10, quantity())
        assertTrue(dao.getAllPendingSync().isEmpty())
        assertEquals(OrderStatus.CANCELED, dao.getOrderByNumber("O")!!.status)
    }

    @Test fun serverCommittedOrderWithLostResponseIsNotPostedAgain() = runBlocking {
        api.online = true
        api.loseOrderResponse = true
        val q = quote()
        assertFalse(repository.confirmOrder(q, order(q)).cloudSynced)
        assertEquals(1, dao.getAllPendingSync().size)
        repository.syncAll()
        assertEquals(1, api.orderUploads.size)
        assertEquals(7, quantity())
        assertTrue(dao.getAllPendingSync().isEmpty())
    }

    @Test fun differentRemoteOrderWithSameNumberKeepsPendingAndDoesNotPull() = runBlocking {
        val q = quote()
        repository.confirmOrder(q, order(q))
        api.online = true
        api.orders = listOf(remoteOrder(order(q)).copy(customer_phone = "different"))
        api.events.clear()
        assertTrue(repository.syncAll().pendingFailureCount > 0)
        assertEquals(listOf("get_orders"), api.events.toList())
        assertEquals(1, dao.getAllPendingSync().size)
        assertEquals(7, quantity())
    }

    @Test fun unknownMasterRejectsWholeSnapshotInsteadOfDeletingLocalStock() = runBlocking {
        api.online = true
        api.inventory = listOf(remoteStock(2).copy(species_name = "없는묘목"))
        assertNotNull(repository.syncAll().inventoryResult!!.errorMessage)
        assertEquals(10, quantity())
    }

    @Test fun failedOrderPullCannotPartiallyReplaceInventory() = runBlocking {
        api.online = true
        api.inventory = listOf(remoteStock(2))
        api.failOrderPull = true
        assertNotNull(repository.syncAll().inventoryResult!!.errorMessage)
        assertEquals(10, quantity())
    }

    @Test fun duplicateRemoteInventoryRejectsWholeSnapshot() = runBlocking {
        api.online = true
        api.inventory = listOf(remoteStock(2), remoteStock(4))
        assertNotNull(repository.syncAll().inventoryResult!!.errorMessage)
        assertEquals(10, quantity())
    }

    @Test fun inactiveSpeciesStockSurvivesPullButIsHiddenFromSelling() = runBlocking {
        dao.deactivateSpecies(1)
        api.online = true
        assertNull(repository.syncAll().inventoryResult!!.errorMessage)
        assertEquals(10, quantity())
        assertTrue(dao.getAllSellingInventory().isEmpty())
    }

    @Test fun writesCommitDuringSlowPullAndCannotBeOverwritten() = runBlocking {
        api.online = true
        api.inventory = listOf(remoteStock(2))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforeInventoryPull = { entered.complete(Unit); release.await() }
        val pull = async(Dispatchers.IO) { repository.syncAll() }
        withTimeout(5000) { entered.await() }
        val save = async(Dispatchers.IO) { repository.saveInventory(stock.copy(quantity = 9), "농원", "소나무") }
        withTimeout(5000) { while (dao.getAllPendingSync().isEmpty()) delay(5) }
        assertEquals(9, quantity())
        release.complete(Unit)
        assertNotNull(pull.await().inventoryResult!!.errorMessage)
        save.await()
        assertEquals(9, quantity())
    }

    @Test fun concurrentSyncRequestsSendEachPendingOnce() = runBlocking {
        val q = quote()
        repository.confirmOrder(q, order(q))
        api.online = true
        coroutineScope { (1..4).map { async(Dispatchers.IO) { repository.syncAll() } }.awaitAll() }
        assertEquals(1, api.orderUploads.size)
        assertEquals(7, quantity())
    }

    @Test fun coroutineCancellationLeavesOutboxForRetry() = runBlocking {
        api.online = true
        api.beforeOrderPost = { throw CancellationException("stopped") }
        val q = quote()
        val result = runCatching { repository.confirmOrder(q, order(q)) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(7, quantity())
        assertEquals(1, dao.getAllPendingSync().size)
        assertEquals(0, dao.getAllPendingSync().single().retryCount)
    }

    @Test fun referencedMasterCannotBeDeletedOrRenamedAndDetailsRemainEditable() = runBlocking {
        val q = quote()
        repository.confirmOrder(q, order(q))
        assertTrue(runCatching { repository.deleteFarm(1) }.isFailure)
        assertTrue(runCatching { repository.saveFarm(1, "새이름", "", "", "") }.isFailure)
        repository.saveFarm(1, "농원", "주소 수정", "", "")
        assertEquals("주소 수정", dao.getAllFarms().single().address)
        assertEquals(7, quantity())
    }

    @Test fun missingOrderCannotCreatePhantomDeliveryPending() = runBlocking {
        assertTrue(runCatching { repository.updateOrderDeliveryInfo("missing", "이름", "", "주소", "", "") }.isFailure)
        assertTrue(dao.getAllPendingSync().isEmpty())
    }

    private fun remoteOrder(o: OrderEntity) = SheetOrderRemoteDto(
        o.orderNumber, o.orderDate, o.customerPhone, o.totalQty, o.totalPrice,
        o.itemSummary, o.receiverName, "", o.note, o.status, o.itemSummary,
        o.isPrepaid, o.shippingFee, o.zipNo, o.roadAddress, o.detailAddress
    )
}
