package com.example.namufarm.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.namufarm.data.*
import com.example.namufarm.data.remote.OperationsMasterGateway
import com.google.gson.JsonObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.1.21 transaction regression cases, written only. Not executed for this change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManagementOrderDeductionTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: ManagementDao
    private lateinit var viewModel: ManagementViewModel
    @Before fun setUp(): Unit = runBlocking {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.managementDao()
        dao.upsertFarm(FarmEntity(1, "기존 농원"))
        dao.upsertSpecies(SpeciesEntity(1, "정상 묘목", isActive = 1))
        dao.upsertSpecies(SpeciesEntity(2, "숨긴 묘목", isActive = 0))
        dao.upsertInventory(stock(1, 40))
        dao.upsertInventory(stock(2, 30))
        val offline = object : OperationsMasterGateway {
            override var downloaded = true
            override suspend fun request(action: String, fields: Map<String, Any>): JsonObject =
                error("Offline fake: production transport is never used")
        }
        viewModel = ManagementViewModel(dao, SyncManager(dao, OperationsMasterSync(db, offline)))
        awaitWork()
    }
    @After fun tearDown() = runBlocking {
        val job = viewModel.viewModelScope.coroutineContext[Job]
        viewModel.viewModelScope.cancel()
        withTimeout(5000) { job?.join() }
        db.close()
    }
    private suspend fun awaitWork() = withTimeout(5000) {
        while (true) {
            val jobs = viewModel.viewModelScope.coroutineContext[Job]!!.children
                .filterNot { it == viewModel.pendingSyncObserver }.toList()
            if (jobs.isEmpty()) break
            jobs.joinAll()
        }
    }
    private fun stock(species: Int, quantity: Int) =
        FarmInventoryEntity(1, species, "상묘", "1년생", "1.0m", "실생", quantity, 1000, 2000)
    private fun line(name: String, farm: String = "기존 농원", quantity: Int = 5) =
        "$name|상묘|1년생|1.0m|실생|$quantity|2000|$farm|1000"
    private fun quote(summary: String): QuoteEntity {
        dao.insertQuote(QuoteEntity(quoteNumber = "TEST-QUOTE", saveDate = "2026-10-27 10:00",
            customerPhone = "", totalQty = 17, totalPrice = 40000, itemSummary = summary,
            isPrepaid = true, shippingFee = 6000))
        return dao.getTodayQuotes().single()
    }
    private fun remaining(species: Int) = dao.getInventoryEntity(1, species, "상묘", "1년생", "1.0m", "실생")!!.quantity

    @Test fun hiddenSpeciesAndDeletedFarmDoNotRollBackTheOtherOrderItems() = runBlocking {
        val source = quote(listOf(line("정상 묘목", quantity = 7), line("숨긴 묘목"),
            line("정상 묘목", farm = "삭제된 농원")).joinToString("\n"))
        viewModel.convertQuoteToOrder(source, context)
        awaitWork()
        val order = dao.getTodayOrders().single()
        assertTrue(dao.getTodayQuotes().isEmpty())
        assertEquals(source.totalPrice, order.totalPrice)
        assertEquals(source.totalQty, order.totalQty)
        assertEquals(source.shippingFee, order.shippingFee)
        assertEquals(source.isPrepaid, order.isPrepaid)
        assertEquals("입금확인", order.status)
        assertEquals(33, remaining(1))
        assertEquals(30, remaining(2))
        assertEquals(1, dao.getAllPendingSync().count { it.syncType == PendingSyncType.INVENTORY_SAVE })
        assertEquals(1, dao.getAllPendingSync().count { it.syncType == PendingSyncType.ORDER })
        val message = requireNotNull(viewModel.uiState.value.message)
        assertTrue(message.contains("재고 차감을 건너뛴 품목:"))
        assertTrue(message.contains("숨긴 묘목"))
        assertTrue(message.contains("정상 묘목"))
    }

    @Test fun orderStillCommitsWhenEveryStockDeductionIsSkipped() = runBlocking {
        val source = quote(line("숨긴 묘목"))
        viewModel.convertQuoteToOrder(source, context)
        awaitWork()
        assertEquals(1, dao.getTodayOrders().size)
        assertTrue(dao.getTodayQuotes().isEmpty())
        assertEquals(30, remaining(2))
        assertFalse(dao.getAllPendingSync().any { it.syncType == PendingSyncType.INVENTORY_SAVE })
        assertTrue(requireNotNull(viewModel.uiState.value.message).contains("재고 차감을 건너뛴 품목: 숨긴 묘목"))
    }
}
