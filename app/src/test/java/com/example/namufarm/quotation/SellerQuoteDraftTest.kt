package com.example.namufarm.quotation

import org.junit.Assert.*
import org.junit.Test

class SellerQuoteDraftTest {
    private fun draft() = SellerQuoteDraft(rows = listOf(SellerQuoteRow("감나무", "2년생", "30", "1500")), phone = "010-1234-5678", recipient = QuoteRecipient(customerName = "김나무"))
    @Test fun stepperClampsAtZeroAndIntegerMaximum() {
        assertEquals("0", adjustedQuotePrice("100", -1000))
        assertEquals("1000", adjustedQuotePrice("", 1000))
        assertEquals(Int.MAX_VALUE.toString(), adjustedQuotePrice(Int.MAX_VALUE.toString(), 10000))
    }
    @Test fun incompleteOrOverflowingAmountsCannotBeSent() {
        assertEquals(1500, draft().validate(QuoteDelivery.SMS).single().retailPrice)
        assertTrue(runCatching { draft().copy(rows = listOf(SellerQuoteRow("감나무", "", "30", ""))).validate(QuoteDelivery.SMS) }.isFailure)
        assertTrue(runCatching { draft().copy(rows = listOf(SellerQuoteRow("감나무", "", Int.MAX_VALUE.toString(), "1500"))).validate(QuoteDelivery.SMS) }.isFailure)
        assertTrue(runCatching { draft().validate(QuoteDelivery.EMAIL) }.isFailure)
    }
    @Test fun freePrepaidAndFormalTaxRemainExplicit() {
        val valid = draft().copy(prepaid = true, fee = "0", recipient = draft().recipient.copy(formalQuoteRequested = true), options = QuoteIssueOptions(tax = "500"))
        valid.validate(QuoteDelivery.SAVE)
        assertTrue(valid.prepaid)
        assertEquals(0, valid.shipping())
        assertEquals(500L, valid.options.taxAmount())
        assertTrue(runCatching { valid.copy(options = valid.options.copy(tax = "-1")).validate(QuoteDelivery.SAVE) }.isFailure)
    }
}
