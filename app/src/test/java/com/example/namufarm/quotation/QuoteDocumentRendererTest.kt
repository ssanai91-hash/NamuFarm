package com.example.namufarm.quotation

import android.content.Context
import com.example.namufarm.data.WebQuoteRequestEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Authored only. Do not run as part of the 1.1.23 source change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuoteDocumentRendererTest {
    private val request = WebQuoteRequestEntity("00000000-0000-4000-8000-000000000023", 1,
        "2026-10-27", "견적 고객", "01000000000", "옥천", "[]", "고객 원문",
        receiptNumber = "NAMU-20261027-0023")
    private fun draft(rows: Int = 1) = SellerQuoteDraft(
        (1..rows).map { SellerQuoteRow("묘목 $it", "2년생", "3", "2000", "설명 $it") },
        request.customerPhone, QuoteRecipient.from(request), true, "6000")

    @Test fun allRowsAndRemarksSurvivePageBreaksWithTheSameSevenColumns() {
        val doc = initialFormalQuote(request, draft(50))
        val plan = QuoteDocumentRenderer.pagePlan(doc)
        assertTrue(plan.size > 1)
        val rows = plan.flatMap { it.rows }
        assertEquals(51, rows.size)
        assertEquals((1..50).map { "묘목 $it" }, rows.take(50).map { it.values[0] })
        assertEquals((1..50).map { "설명 $it" }, rows.take(50).map { it.values[6] })
        assertTrue(plan.all { it.rows.sumOf { row -> row.height } <= 743 })
        assertEquals(listOf("운반비", "선불", "1", "6,000", "6,000", "", "택배비"), rows.last().values)
        assertEquals((1..plan.size).toList(), plan.map { it.number })
    }

    @Test fun longSellerNotesContinueWithoutDroppingAnyCharacter() {
        val note = "요청사항 확인\n".repeat(50).take(500)
        val doc = initialFormalQuote(request, draft().copy(options = QuoteIssueOptions(notes = note)))
        val pages = QuoteDocumentRenderer.pagePlan(doc)
        assertTrue(pages.any { it.continuation.isNotEmpty() })
        val expected = QuoteDocumentRenderer.wrappedLines("요청사항 : " + note.trim(), 664f, 12)
        val actual = pages.first().note.split('\n') + pages.filter { it.continuation.isNotEmpty() }.flatMap { it.continuation.split('\n') }
        assertEquals(expected, actual)
    }

    @Test fun changedDraftGetsANewDocumentAndUnchangedDraftKeepsItsNumber() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("initial_formal_quotes_v1", 0).edit().clear().commit()
        val store = InitialFormalQuoteStore(context)
        val first = store.issue(request, draft())
        assertEquals(first.number, store.issue(request, draft()).number)
        val revised = store.issue(request, draft().copy(fee = "7000"))
        assertNotEquals(first.number, revised.number)
        assertEquals(7000L, checkNotNull(revised.quotedShippingFee))
        assertEquals(13000L, revised.totalAmount)
    }

    @Test fun postpaidTermsRemainInTheDocumentWithoutAddingToTheTotal() {
        val doc = initialFormalQuote(request, draft().copy(prepaid = false, fee = "6000"))
        assertEquals(false, doc.shippingPrepaid)
        assertEquals(6000L, checkNotNull(doc.quotedShippingFee))
        assertEquals(6000L, doc.totalAmount)
        assertEquals(1, QuoteDocumentRenderer.pagePlan(doc).flatMap { it.rows }.size)
    }

    @Test fun pdfCentersTheOriginalImageRatioOnA4InsteadOfStretchingIt() {
        val context: Context = RuntimeEnvironment.getApplication()
        val folder = File(context.cacheDir, "pdf-placement-test").apply { mkdirs() }
        val image = File(folder, "page.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -39)) }
        val pdf = File(folder, "quote.pdf")
        QuotePdfWriter.write(pdf, listOf(image), 955 * 3, 1342 * 3)
        val matrix = Regex("([0-9.]+) 0 0 ([0-9.]+) ([0-9.]+) ([0-9.]+) cm").find(pdf.readText(Charsets.ISO_8859_1))!!.groupValues.drop(1).map { it.toDouble() }
        assertEquals(955.0 / 1342, matrix[0] / matrix[1], 0.000001)
        assertEquals(595.0, matrix[0] + 2 * matrix[2], 0.000001)
        assertEquals(842.0, matrix[1] + 2 * matrix[3], 0.000001)
    }
}
