package com.example.namufarm.quotemode

import androidx.room.Room
import com.example.namufarm.data.WebQuoteItem
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.data.remote.MasterServerException
import com.example.namufarm.data.remote.OperationsMasterGateway
import com.example.namufarm.quotation.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
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
class InitialQuoteConnectionTest {
    private lateinit var db: InitialQuoteDatabase
    private lateinit var server: Server
    private lateinit var connection: InitialQuoteConnection
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), InitialQuoteDatabase::class.java)
            .allowMainThreadQueries().build()
        server = Server()
        connection = InitialQuoteConnection(db, server)
    }
    @After fun close() = db.close()
    private fun local(id: Int = 9) = WebQuoteRequestEntity(
        "00000000-0000-4000-8000-%012d".format(id), id.toLong(),
        "2026-10-18T00:00:00.000Z", "기존 고객", "01000000000", "옥천",
        Gson().toJson(listOf(WebQuoteItem("가죽나무", "", 2))), "", readAt = 123)
    @Test fun interruptedDownloadPreservesEntirePreviousCache() = runBlocking {
        db.inbox().insertRequests(listOf(local()))
        server.rows = listOf(server.row(1), server.row(2))
        server.failSecondPage = true
        assertThrows(IllegalStateException::class.java) { runBlocking { connection.refresh() } }
        assertEquals(listOf(local()), connection.localRequests())
    }
    @Test fun busySnapshotRestartsBeforeAtomicReplace() = runBlocking {
        db.inbox().insertRequests(listOf(local()))
        server.rows = listOf(server.row(1), server.row(2))
        server.busyOnce = true
        assertEquals(2, connection.refresh())
        assertEquals(2, server.firstPageReads)
        assertEquals(listOf(2L, 1L), connection.localRequests().map { it.sequence })
    }
    @Test fun incompleteCountCannotRemoveCachedCustomers() = runBlocking {
        db.inbox().insertRequests(listOf(local()))
        server.rows = listOf(server.row(1)); server.extraTotal = 1
        assertThrows(IllegalStateException::class.java) { runBlocking { connection.refresh() } }
        assertEquals(local(), connection.localRequests().single())
    }
    @Test fun existingReadTimeSurvivesSnapshot() = runBlocking {
        db.inbox().insertRequests(listOf(local(1)))
        server.rows = listOf(server.row(1))
        connection.refresh()
        assertEquals(123L, connection.localRequests().single().readAt)
    }
    @Test fun serverConfirmedPricesAreAvailableOnAnotherDevice() = runBlocking {
        val row = server.row(1).apply {
            addProperty("status", "견적확정")
            add("quotePricing", Gson().toJsonTree(mapOf("items" to listOf(
                mapOf("species" to "가죽나무", "specification" to "", "quantity" to 2, "unitPrice" to 6000)),
                "isPrepaid" to true, "shippingFee" to 6000, "taxAmount" to 0)))
        }
        server.rows = listOf(row); connection.refresh()
        val draft = serverConfirmedDraft(connection.localRequests().single())!!
        assertEquals("6000", draft.rows.single().price)
        assertEquals(18000, draft.rows.single().line().let { it.orderQty * it.retailPrice } + draft.shipping())
    }
    @Test fun wrongConfirmationAcknowledgementNeverAdvancesLocalStatus() = runBlocking {
        db.inbox().insertRequests(listOf(local(1)))
        val draft = SellerQuoteDraft.from(local(1), QuoteIssuer())
        server.invalidConfirmation = true
        assertThrows(IllegalStateException::class.java) { runBlocking { connection.confirmQuote(local(1).requestId, draft) } }
        assertEquals("신규", connection.localRequests().single().status)
    }
    @Test fun confirmedCacheRetainsTheExactPricingInput() = runBlocking {
        db.inbox().insertRequests(listOf(local(1)))
        val draft = SellerQuoteDraft.from(local(1), QuoteIssuer()).copy(
            rows = listOf(SellerQuoteRow("가죽나무", "", "2", "6000")))
        connection.confirmQuote(local(1).requestId, draft)
        val saved = connection.localRequests().single()
        assertEquals("견적확정", saved.status)
        assertEquals("6000", serverConfirmedDraft(saved)!!.rows.single().price)
    }
    private class Server : OperationsMasterGateway {
        override var downloaded = false
        var rows = emptyList<JsonObject>()
        var failSecondPage = false
        var busyOnce = false
        var extraTotal = 0
        var firstPageReads = 0
        var invalidConfirmation = false
        fun row(id: Int) = Gson().toJsonTree(mapOf("requestId" to
            "00000000-0000-4000-8000-%012d".format(id), "sequence" to id,
            "createdAt" to "2026-10-18T00:00:00.000Z", "customerName" to "견적 고객",
            "customerPhone" to "01000000000", "region" to "옥천", "message" to "",
            "status" to "신규", "receiptNumber" to "NAMU-20261018-%04d".format(id),
            "items" to listOf(mapOf("species" to "가죽나무", "specification" to "", "quantity" to 2)))).asJsonObject
        override suspend fun request(action: String, fields: Map<String, Any>): JsonObject {
            if (action == "save_quote_pricing") return Gson().toJsonTree(mapOf("ok" to true,
                "updated" to true, "requestId" to if (invalidConfirmation) "wrong-id" else fields["requestId"],
                "status" to "견적확정")).asJsonObject
            check(action == "snapshot_requests")
            val after = (fields["afterSequence"] as Number).toLong()
            if (after == 0L) firstPageReads++
            if (after > 0 && failSecondPage) error("offline")
            if (after > 0 && busyOnce) { busyOnce = false; throw MasterServerException("BUSY") }
            val rest = rows.filter { it.get("sequence").asLong > after }
            return Gson().toJsonTree(mapOf("ok" to true, "requests" to rest.take(1),
                "hasMore" to (rest.size > 1), "snapshotId" to "a".repeat(64),
                "total" to rows.size + extraTotal, "watermark" to rows.size)).asJsonObject
        }
    }
}
