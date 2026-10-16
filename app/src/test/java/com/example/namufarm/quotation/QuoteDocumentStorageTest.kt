package com.example.namufarm.quotation

import androidx.room.Room
import com.example.namufarm.data.*
import com.example.namufarm.data.local.LocalDataSource
import com.example.namufarm.data.remote.*
import com.example.namufarm.data.repository.NamuFarmRepository
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
class QuoteDocumentStorageTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: NamuFarmRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        val local = LocalDataSource(db, db.managementDao())
        val juso = object : JusoApi { override suspend fun searchAddress(apiKey: String, keyword: String, resultType: String, count: Int): JusoResponse = error("unused") }
        repo = NamuFarmRepository(local, SyncManager(local, FakeSheetApi()), juso)
    }
    @After fun close() { db.close() }
    @Test fun contactsCommitAtomicallyWithQuoteAndCollisionRollsBack() = runBlocking {
        val quote = QuoteTestData.quote(); repo.insertQuoteWithContact(quote, QuoteTestData.recipient)
        assertTrue(runCatching { repo.insertQuoteWithContact(quote, QuoteTestData.recipient) }.isFailure)
        assertEquals(1, db.managementDao().getQuoteByNumber(quote.quoteNumber).size)
        assertEquals(QuoteTestData.recipient, repo.getQuoteRecipient(quote.quoteNumber))
    }
    @Test fun issuedSnapshotCannotBeOverwrittenAndDraftDeletionRemovesSnapshot() = runBlocking {
        val quote = QuoteTestData.quote(); repo.insertQuoteWithContact(quote, QuoteTestData.recipient)
        val first = repo.issueQuoteDocument(quote.quoteNumber, QuoteTestData.issuer, 0, "7일", "", "")
        val retry = repo.issueQuoteDocument(quote.quoteNumber, QuoteTestData.issuer.copy(businessName = "변경된 농원"), 9000, "1일", "변경", "")
        assertEquals(first, retry); assertEquals(1, repo.getQuoteDocuments().size)
        repo.deleteQuote(db.managementDao().getQuoteByNumber(quote.quoteNumber).single())
        assertNull(repo.getQuoteDocument(quote.quoteNumber))
        assertNull(repo.getSavedQuote(quote.quoteNumber))
        assertTrue(repo.getQuoteDocuments().isEmpty())
        assertEquals(60000L, QuoteDocument.read(first.snapshotJson).lines.first().unitPrice)
        assertTrue(db.managementDao().getAllPendingSync().isEmpty()); assertTrue(db.managementDao().getAllInventoryEntities().isEmpty())
    }
    @Test fun sellerSaveIncludesOfficialSnapshotAndNeverMarksItSent() = runBlocking {
        val quote = QuoteTestData.quote()
        repo.insertQuoteWithContact(quote, QuoteTestData.recipient, QuoteIssueOptions(issuer = QuoteTestData.issuer, tax = "500"))
        val doc = QuoteDocument.read(repo.getQuoteDocument(quote.quoteNumber)!!.snapshotJson)
        assertEquals(quote.totalPrice.toLong() + 500, doc.totalAmount)
        assertEquals(0L, repo.getSavedQuote(quote.quoteNumber)!!.sentAt)
        assertEquals(doc, QuoteDocument.read(repo.issueQuoteDocument(quote.quoteNumber, QuoteTestData.issuer, 0, "changed", "", "").snapshotJson))
        assertTrue(db.managementDao().getAllPendingSync().isEmpty())
    }
    @Test fun invalidOfficialDetailsRollBackQuoteAndContactTogether() = runBlocking {
        val quote = QuoteTestData.quote()
        assertTrue(runCatching { repo.insertQuoteWithContact(quote, QuoteTestData.recipient, QuoteIssueOptions(tax = "-1")) }.isFailure)
        assertNull(repo.getSavedQuote(quote.quoteNumber))
        assertNull(repo.getQuoteDocument(quote.quoteNumber))
        assertEquals(QuoteRecipient(), repo.getQuoteRecipient(quote.quoteNumber))
    }
    @Test fun unfinishedManualQuoteResumesWithoutCreatingAnotherRecord() = runBlocking {
        val source = QuoteTestData.quote()
        val line = com.example.namufarm.domain.order.OrderLine("감나무", "2년생", "", "", "", 30, 1000, "수기견적", 0, com.example.namufarm.domain.order.OrderLineSource.MANUAL)
        val quote = source.copy(isPrepaid = false, shippingFee = 0, totalQty = 30, totalPrice = 30000, itemSummary = com.example.namufarm.domain.order.OrderSummaryCodec.encode(listOf(line)))
        repo.insertQuoteWithContact(quote, QuoteTestData.recipient)
        val changed = quote.copy(totalPrice = 45000, itemSummary = com.example.namufarm.domain.order.OrderSummaryCodec.encode(listOf(line.copy(retailPrice = 1500))))
        repo.insertQuoteWithContact(changed, QuoteTestData.recipient, replaceDraft = true)
        assertEquals(1, repo.getAllQuotes().size)
        assertEquals(45000, repo.getSavedQuote(quote.quoteNumber)!!.totalPrice)
        repo.markQuoteSent(quote.quoteNumber)
        assertTrue(runCatching { repo.insertQuoteWithContact(quote, QuoteTestData.recipient, replaceDraft = true) }.isFailure)
        assertEquals(45000, repo.getSavedQuote(quote.quoteNumber)!!.totalPrice)
    }
    @Test fun officialSnapshotPreventsDraftOverwrite() = runBlocking {
        val quote = QuoteTestData.quote()
        repo.insertQuoteWithContact(quote, QuoteTestData.recipient, QuoteIssueOptions())
        assertTrue(runCatching { repo.insertQuoteWithContact(quote, QuoteTestData.recipient, replaceDraft = true) }.isFailure)
        assertNotNull(repo.getQuoteDocument(quote.quoteNumber))
    }
    @Test fun missingOrInvalidQuoteNeverCreatesIssuedRecord() = runBlocking {
        assertTrue(runCatching { repo.issueQuoteDocument("missing", QuoteTestData.issuer, 0, "7일", "", "") }.isFailure)
        repo.insertQuoteWithContact(QuoteTestData.quote(), QuoteTestData.recipient)
        assertTrue(runCatching { repo.issueQuoteDocument("QUOTE_TEST", QuoteTestData.issuer, -1, "7일", "", "") }.isFailure)
        assertTrue(repo.getQuoteDocuments().isEmpty())
    }
}

