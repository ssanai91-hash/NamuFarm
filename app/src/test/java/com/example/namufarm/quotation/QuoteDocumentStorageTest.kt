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
    @Test fun issuedSnapshotCannotBeOverwrittenAndSurvivesQuoteDeletion() = runBlocking {
        val quote = QuoteTestData.quote(); repo.insertQuoteWithContact(quote, QuoteTestData.recipient)
        val first = repo.issueQuoteDocument(quote.quoteNumber, QuoteTestData.issuer, 0, "7일", "", "")
        val retry = repo.issueQuoteDocument(quote.quoteNumber, QuoteTestData.issuer.copy(businessName = "변경된 농원"), 9000, "1일", "변경", "")
        assertEquals(first, retry); assertEquals(1, repo.getQuoteDocuments().size)
        repo.deleteQuote(db.managementDao().getQuoteByNumber(quote.quoteNumber).single())
        assertEquals(first, repo.getQuoteDocument(quote.quoteNumber))
        assertEquals(60000L, QuoteDocument.read(first.snapshotJson).lines.first().unitPrice)
        assertTrue(db.managementDao().getAllPendingSync().isEmpty()); assertTrue(db.managementDao().getAllInventoryEntities().isEmpty())
    }
    @Test fun missingOrInvalidQuoteNeverCreatesIssuedRecord() = runBlocking {
        assertTrue(runCatching { repo.issueQuoteDocument("missing", QuoteTestData.issuer, 0, "7일", "", "") }.isFailure)
        repo.insertQuoteWithContact(QuoteTestData.quote(), QuoteTestData.recipient)
        assertTrue(runCatching { repo.issueQuoteDocument("QUOTE_TEST", QuoteTestData.issuer, -1, "7일", "", "") }.isFailure)
        assertTrue(repo.getQuoteDocuments().isEmpty())
    }
}
