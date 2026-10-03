package com.example.namufarm.data

import android.content.Intent
import android.net.Uri
import androidx.room.Room
import com.example.namufarm.data.local.LocalDataSource
import com.example.namufarm.data.remote.*
import com.example.namufarm.data.repository.NamuFarmRepository
import com.example.namufarm.webrequests.WebRequestPush
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
class WebQuoteRequestTest {
    private lateinit var db: AppDatabase
    private lateinit var local: LocalDataSource
    private lateinit var repository: NamuFarmRepository
    private val id = "11111111-1111-4111-8111-111111111111"
    private val secondId = "22222222-2222-4222-8222-222222222222"
    private fun remote(number: Long = 1, requestId: String = id) = WebQuoteRemote(requestId, number, "2026-10-04T01:23:45.000Z", "김나무", "01012345678", "충북 옥천", listOf(WebQuoteItemRemote("소나무", "", 12)), "문의")
    private fun page(vararg rows: WebQuoteRemote) = WebQuoteEnvelope(ok = true, requests = rows.toList(), hasMore = false)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        local = LocalDataSource(db, db.managementDao())
        val juso = object : JusoApi {
            override suspend fun searchAddress(apiKey: String, keyword: String, resultType: String, count: Int): JusoResponse = error("unused")
        }
        repository = NamuFarmRepository(local, SyncManager(local, FakeSheetApi()), juso)
    }
    @After fun close() { db.close() }
    @Test fun malformedPageIsRejectedBeforeAnyInsert() {
        assertTrue(runCatching { WebQuoteValidator.page(page(remote(), remote(2, secondId).copy(customerPhone = "bad")), 0) }.isFailure)
        assertEquals(0L, local.webRequestCursor())
    }
    @Test fun missingFlagsAndRepeatedSequencesCannotAdvanceCursor() {
        assertTrue(runCatching { WebQuoteValidator.page(WebQuoteEnvelope(ok = true, requests = emptyList()), 0) }.isFailure)
        assertTrue(runCatching { WebQuoteValidator.page(page(remote(), remote(1, secondId)), 0) }.isFailure)
        assertTrue(runCatching { WebQuoteValidator.page(page(remote()), 1) }.isFailure)
    }
    @Test fun repeatedImportKeepsReadStateAndNeverCreatesBusinessRecords() = runBlocking {
        val rows = WebQuoteValidator.page(page(remote()), 0)
        local.transaction { insertWebRequests(rows) }
        repository.markWebRequestRead(id)
        local.transaction { insertWebRequests(rows) }
        assertNotNull(repository.getWebRequests().single().readAt)
        assertTrue(db.managementDao().getAllInventoryEntities().isEmpty())
        assertTrue(db.managementDao().getQuotesForDate("2026-10-04").isEmpty())
        assertTrue(db.managementDao().getAllPendingSync().isEmpty())
    }
    @Test fun sequenceCollisionRollsBackWholePageAndCursor() = runBlocking {
        val rows = WebQuoteValidator.page(page(remote(), remote(2, secondId)), 0)
        assertTrue(runCatching { local.transaction { insertWebRequests(listOf(rows[0], rows[1].copy(sequence = 1))) } }.isFailure)
        assertTrue(local.getWebRequests().isEmpty()); assertEquals(0L, local.webRequestCursor())
    }
    @Test fun changedImmutableRequestDoesNotOverwriteOriginal() = runBlocking {
        val row = WebQuoteValidator.page(page(remote()), 0).single()
        local.transaction { insertWebRequests(listOf(row)) }
        assertTrue(runCatching { local.transaction { insertWebRequests(listOf(row.copy(customerPhone = "01099998888"))) } }.isFailure)
        assertEquals("01012345678", local.getWebRequests().single().customerPhone)
    }
    @Test fun committedPagesResumeAfterOfflineFailure() = runBlocking {
        var calls = 0
        val api = object : WebQuoteApi {
            override suspend fun request(url: String, body: Map<String, Any>): WebQuoteEnvelope {
                calls++
                if (calls == 1) return page(remote()).copy(hasMore = true)
                error("offline")
            }
        }
        assertTrue(runCatching { repository.syncWebRequests(api, "test", "test") }.isFailure)
        assertEquals(1L, local.webRequestCursor())
        val retry = object : WebQuoteApi {
            override suspend fun request(url: String, body: Map<String, Any>): WebQuoteEnvelope {
                assertEquals(1L, body["afterSequence"]); return page(remote(2, secondId))
            }
        }
        assertEquals(1, repository.syncWebRequests(retry, "test", "test"))
        assertEquals(2, local.getWebRequests().size)
    }
    @Test fun pendingFailureBlocksRequestPull() = runBlocking {
        db.managementDao().insertPendingSync(PendingSyncEntity(syncType = "unknown", payloadJson = "{}"))
        var called = false
        val api = object : WebQuoteApi {
            override suspend fun request(url: String, body: Map<String, Any>): WebQuoteEnvelope { called = true; return page(remote()) }
        }
        assertTrue(runCatching { repository.syncWebRequests(api, "test", "test") }.isFailure)
        assertFalse(called); assertTrue(local.getWebRequests().isEmpty())
    }
    @Test fun pendingCreatedDuringPullBlocksItsCommit() = runBlocking {
        val api = object : WebQuoteApi {
            override suspend fun request(url: String, body: Map<String, Any>): WebQuoteEnvelope {
                db.managementDao().insertPendingSync(PendingSyncEntity(syncType = "unknown", payloadJson = "{}"))
                return page(remote())
            }
        }
        assertTrue(runCatching { repository.syncWebRequests(api, "test", "test") }.isFailure)
        assertEquals(0L, local.webRequestCursor())
    }
    @Test fun notificationDeepLinkOnlyAcceptsOneValidRequestId() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(id, WebRequestPush.fromIntent(WebRequestPush.intent(context, id)))
        assertNull(WebRequestPush.fromIntent(Intent(WebRequestPush.ACTION, Uri.parse("namufarm://web-request/bad"))))
        assertNull(WebRequestPush.fromIntent(Intent(WebRequestPush.ACTION, Uri.parse("https://web-request/$id"))))
    }
}
