package com.example.namufarm.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.namufarm.data.*
import com.example.namufarm.data.remote.OperationsMasterGateway
import com.example.namufarm.domain.order.ShippingCharge
import com.google.gson.JsonObject
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManagementSelectionTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ManagementDao
    private lateinit var viewModel: ManagementViewModel
    private val farm = FarmEntity(1, "테스트 농원")
    private val n = SpeciesEntity(1, "능소화", chosung = "ㄴ")
    private val d = SpeciesEntity(2, "단풍나무", chosung = "ㄷ")
    @Volatile private var blockedSpeciesId: Int? = null
    private val queryStarted = CountDownLatch(1)
    private val releaseQuery = CountDownLatch(1)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.managementDao()
        dao.upsertFarm(farm)
        dao.upsertSpecies(n); dao.upsertSpecies(d)
        dao.upsertInventory(FarmInventoryEntity(1, 1, "상묘", "1년생", "", "실생", 9, 100, 200))
        dao.upsertInventory(FarmInventoryEntity(1, 2, "상묘", "1년생", "", "실생", 7, 300, 400))
        val gateway = object : OperationsMasterGateway {
            override var downloaded = true
            override suspend fun request(action: String, fields: Map<String, Any>): JsonObject =
                error("Selection must use Room without network access")
        }
        val viewDao = Proxy.newProxyInstance(ManagementDao::class.java.classLoader,
            arrayOf(ManagementDao::class.java)) { _, method, args ->
            val result = method.invoke(dao, *(args ?: emptyArray()))
            if (method.name == "getInventoryRows" && args?.get(1) == blockedSpeciesId) {
                queryStarted.countDown()
                check(releaseQuery.await(5, TimeUnit.SECONDS)) { "Timed out releasing the old inventory query" }
            }
            result
        } as ManagementDao
        viewModel = ManagementViewModel(viewDao, SyncManager(dao, OperationsMasterSync(db, gateway)))
        awaitLoads()
        viewModel.selectFarm(farm)
    }

    @After fun tearDown() = runBlocking {
        releaseQuery.countDown()
        if (::viewModel.isInitialized) {
            val job = viewModel.viewModelScope.coroutineContext[Job]
            viewModel.viewModelScope.cancel()
            withTimeout(5000) { job?.join() }
        }
        if (::db.isInitialized) db.close()
    }

    private suspend fun awaitLoads() {
        withTimeout(5000) {
            viewModel.viewModelScope.coroutineContext[Job]!!.children
                .filterNot { it == viewModel.pendingSyncObserver }.toList().joinAll()
        }
    }

    @Test fun changingInitialClearsTheOldSpeciesAndDisablesInputUntilAnotherChoice() = runBlocking {
        viewModel.selectChosung("ㄴ"); viewModel.selectSpecies(n); awaitLoads()
        viewModel.activateNewInput()
        viewModel.addQuantity(10); viewModel.addWholesale(1000); viewModel.addRetail(2000)
        viewModel.selectChosung("ㄷ"); awaitLoads()
        val state = viewModel.uiState.value
        assertNull(state.selectedSpecies)
        assertFalse(state.isInputEnabled)
        assertFalse(state.isInputActive)
        assertTrue(state.inventoryList.isEmpty())
        assertEquals(listOf(d), state.speciesList)
        assertEquals(farm, state.selectedFarm)
        assertEquals(0, state.inputQuantity)
        assertEquals(0, state.inputWholesale)
        assertEquals(0, state.inputRetail)
        viewModel.activateNewInput()
        assertFalse(viewModel.uiState.value.isInputActive)
    }

    @Test fun choosingTheNewSpeciesEnablesOnlyItsInventory() = runBlocking {
        viewModel.selectChosung("ㄴ"); viewModel.selectSpecies(n)
        viewModel.selectChosung("ㄷ"); viewModel.selectSpecies(d); awaitLoads()
        val state = viewModel.uiState.value
        assertEquals(d, state.selectedSpecies)
        assertTrue(state.isInputEnabled)
        assertFalse(state.isInputActive)
        assertEquals(listOf(2), state.inventoryList.map { it.speciesId })
        viewModel.activateNewInput()
        assertTrue(viewModel.uiState.value.isInputActive)
    }

    @Test fun aLateOldInventoryResultCannotReplaceTheNewSelection() = runBlocking {
        blockedSpeciesId = 1
        viewModel.selectChosung("ㄴ"); viewModel.selectSpecies(n)
        assertTrue(queryStarted.await(5, TimeUnit.SECONDS))
        viewModel.selectChosung("ㄷ"); viewModel.selectSpecies(d)
        releaseQuery.countDown(); awaitLoads()
        val state = viewModel.uiState.value
        assertEquals(d, state.selectedSpecies)
        assertEquals(listOf(2), state.inventoryList.map { it.speciesId })
    }

    @Test fun shippingChoiceMatchesSmsAndTheQuoteSavedInRoomIncludingZeroPrepaid() = runBlocking {
        val item = SellingDisplayRow(1, 1, "테스트 농원", "편백나무", "특묘", "2년생", "1.0m", "실생",
            10, 2_000, 5_000, "ㅍ")
        for (charge in listOf(ShippingCharge(), ShippingCharge(fee = 0), ShippingCharge(isPrepaid = false))) {
            viewModel.addToCart(item, 10)
            val text = viewModel.generateQuoteText(charge.isPrepaid, charge.fee)
            val expected = 50_000 + charge.fee
            assertTrue(text.contains("총 견적 금액: ${String.format("%,d", expected)}원"))
            if (charge.isPrepaid) {
                assertTrue(text.contains("배송비(선불): ${String.format("%,d", charge.fee)}원"))
                assertFalse(text.contains("후불(착불)"))
            } else {
                assertTrue(text.contains("배송비: 후불(착불)"))
            }
            viewModel.saveCurrentQuote(charge.isPrepaid, charge.fee)
            awaitLoads()
            val saved = dao.getTodayQuotes().first()
            assertEquals(charge.isPrepaid, saved.isPrepaid)
            assertEquals(charge.fee, saved.shippingFee)
            assertEquals(expected, saved.totalPrice)
            val savedText = viewModel.generateQuoteTextFromEntity(saved)
            assertTrue(savedText.contains("총 견적 금액: ${String.format("%,d", expected)}원"))
            assertEquals(!charge.isPrepaid, savedText.contains("후불(착불)"))
        }
    }
}
