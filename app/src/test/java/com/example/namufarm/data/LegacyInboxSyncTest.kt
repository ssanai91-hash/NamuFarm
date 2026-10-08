package com.example.namufarm.data

import android.content.Context
import androidx.room.Room
import com.example.namufarm.data.local.LocalDataSource
import com.example.namufarm.data.remote.*
import com.example.namufarm.data.repository.NamuFarmRepository
import com.example.namufarm.privacy.HybridCrypto
import com.example.namufarm.privacy.PrivacyEnvelope
import com.example.namufarm.privacy.PrivacyRetention
import com.example.namufarm.privacy.SellerKeys
import com.example.namufarm.webrequests.readLegacyWebRequest
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LegacyInboxSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var local: LocalDataSource
    private lateinit var repository: NamuFarmRepository
    private lateinit var keys: SellerKeys
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private val third = "33333333-3333-4333-8333-333333333333"
    private val created = "2026-10-04T01:23:45.000Z"
    private val now get() = PrivacyRetention.quoteTime(created) + 1_000L
    private fun row(sequence: Long, id: String) = WebQuoteRemote(id, sequence, created,
        "김나무", "01012345678", "충북 옥천", listOf(WebQuoteItemRemote("소나무", "", 12)), "문의")
    private fun legacy(envelope: PrivacyEnvelope = PrivacyEnvelope(keyId = "a".repeat(64),
        wrappedKey = HybridCrypto.encode(ByteArray(384)), iv = HybridCrypto.encode(ByteArray(12)),
        ciphertext = HybridCrypto.encode(ByteArray(17)))) = WebQuoteRemote(first, 1, created, encryptedRequest = envelope)
    private fun api(vararg rows: WebQuoteRemote) = object : WebQuoteApi {
        override suspend fun request(url: String, body: Map<String, Any>) = WebQuoteEnvelope(ok = true,
            requests = rows.filter { it.sequence!! > body["afterSequence"] as Long }, hasMore = false)
    }
    private fun decode(outer: WebQuoteRemote) = readLegacyWebRequest(outer, keys)
    private suspend fun sync(vararg rows: WebQuoteRemote) = repository.syncWebRequests(api(*rows), "test", "test", ::decode)

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("seller_privacy_keys", Context.MODE_PRIVATE).edit().clear().commit()
        keys = SellerKeys(context) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        local = LocalDataSource(db, db.managementDao(), keys)
        val juso = object : JusoApi {
            override suspend fun searchAddress(apiKey: String, keyword: String, resultType: String, count: Int): JusoResponse = error("unused")
        }
        repository = NamuFarmRepository(local, SyncManager(local, FakeSheetApi()), juso)
    }
    @After fun close() { db.close() }

    @Test fun unavailableOldKeyDoesNotBlockNewQuotesOrRequireKeyCreation() = runBlocking {
        val old = legacy()
        assertEquals(1, sync(old, row(2, second)))
        assertFalse(keys.initialized)
        assertEquals(second, repository.getWebRequests().single().requestId)
        assertEquals(1, repository.deferredWebRequestCount())
        assertEquals(old, Gson().fromJson(local.getDeferredWebRequests().single().envelopeJson, WebQuoteRemote::class.java))
        assertTrue(db.managementDao().getDeferredWebRequests().single().envelopeJson.startsWith("NFENC1:"))
        assertEquals(2L, local.webRequestCursor())
        repository.markWebRequestRead(second)
        assertEquals(1, sync(old, row(2, second), row(3, third)))
        assertEquals(0, sync(old, row(2, second), row(3, third)))
        assertNotNull(repository.getWebRequests().single { it.requestId == second }.readAt)
        assertEquals(3L, local.webRequestCursor())
        assertTrue(local.getAllPendingSync().isEmpty())
    }

    @Test fun encryptedOnlyPageContinuesToNextPage() = runBlocking {
        val cursors = mutableListOf<Long>()
        val paged = object : WebQuoteApi {
            override suspend fun request(url: String, body: Map<String, Any>): WebQuoteEnvelope {
                val after = body["afterSequence"] as Long; cursors += after
                return if (after == 0L) WebQuoteEnvelope(ok = true, requests = listOf(legacy()), hasMore = true)
                else WebQuoteEnvelope(ok = true, requests = listOf(row(2, second)), hasMore = false)
            }
        }
        assertEquals(1, repository.syncWebRequests(paged, "test", "test", ::decode))
        assertEquals(listOf(0L, 1L), cursors)
        assertEquals(1, repository.deferredWebRequestCount())
        assertEquals(2L, local.webRequestCursor())
    }

    @Test fun failedDeferredPersistenceRollsBackNewRowsAndCursor() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_deferred BEFORE INSERT ON deferred_web_request BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
        assertTrue(runCatching { sync(legacy(), row(2, second)) }.isFailure)
        assertTrue(repository.getWebRequests().isEmpty())
        assertEquals(0, repository.deferredWebRequestCount())
        assertEquals(0L, local.webRequestCursor())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_deferred")
        assertEquals(1, sync(legacy(), row(2, second)))
    }

    @Test fun invalidPlaintextStillRejectsWholePageWithoutLosingOldData() = runBlocking {
        assertTrue(runCatching { sync(legacy(), row(2, second).copy(customerPhone = "invalid")) }.isFailure)
        assertTrue(repository.getWebRequests().isEmpty())
        assertEquals(0, repository.deferredWebRequestCount())
        assertEquals(0L, local.webRequestCursor())
    }

    @Test fun malformedEncryptedEnvelopeIsNotSilentlySkipped() = runBlocking {
        val invalid = legacy().let { it.copy(encryptedRequest = it.encryptedRequest!!.copy(iv = "bad")) }
        assertTrue(runCatching { sync(invalid, row(2, second)) }.isFailure)
        assertEquals(0L, local.webRequestCursor())
        assertEquals(0, repository.deferredWebRequestCount())
    }

    @Test fun optionalRecoveryOpensSavedEnvelopeWithoutRewindingCursor() = runBlocking {
        val originalKeys = HybridCrypto.generate()
        val old = legacy(HybridCrypto.seal(Gson().toJson(row(1, first)), originalKeys.public, "quote", first))
        sync(old, row(2, second))
        keys.ensureCreated() // A different backup cannot open the saved request.
        assertEquals(0, repository.restoreDeferredWebRequests(::decode, now))
        assertEquals(1, repository.deferredWebRequestCount())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("seller_privacy_keys", Context.MODE_PRIVATE).edit()
            .putString("private", keys.protect(HybridCrypto.encode(originalKeys.private.encoded)))
            .putString("public", HybridCrypto.encode(originalKeys.public.encoded)).commit()
        assertEquals(1, repository.restoreDeferredWebRequests(::decode, now))
        assertEquals(0, repository.deferredWebRequestCount())
        assertEquals(2, repository.getWebRequests().size)
        assertEquals(2L, local.webRequestCursor())
        assertEquals(0, repository.restoreDeferredWebRequests(::decode, now))
    }

    @Test fun retentionRemovesOldEnvelopeWithoutRewindingCursor() = runBlocking {
        sync(legacy())
        local.cleanupPrivacy(PrivacyRetention.quoteTime(created) + PrivacyRetention.QUOTE_MS)
        assertEquals(0, repository.deferredWebRequestCount())
        assertEquals(1L, local.webRequestCursor())
        assertEquals(1, sync(row(2, second)))
    }

    @Test fun expiredServerRecordNeedsNoBackupAndDoesNotBlockNextQuote() = runBlocking {
        assertEquals(1, sync(legacy().copy(expired = true), row(2, second)))
        assertEquals(0, repository.deferredWebRequestCount())
        assertEquals(2L, local.webRequestCursor())
    }
}
