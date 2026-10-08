package com.example.namufarm.quotation

import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.domain.order.OrderLine
import com.example.namufarm.domain.order.OrderLineSource
import com.example.namufarm.domain.order.OrderSummaryCodec
import org.junit.Assert.*
import org.junit.Test

internal object QuoteTestData {
    val issuer = QuoteIssuer("나무팜 테스트", "123-45-67890", "테스트대표", "충북 옥천군 이원면 테스트로 1", "043-000-0000", "농업", "묘목")
    val recipient = QuoteRecipient("테스트고객", "buyer@example.com", "테스트건설", "both", true)
    fun quote(count: Int = 1): QuoteEntity {
        val lines = (1..count).map { OrderLine("밤나무 $it", "", "R6", "", "", 60, 60000, "수기견적", 0, OrderLineSource.MANUAL) }
        return QuoteEntity(quoteNumber = "QUOTE_TEST", saveDate = "2026-10-05 11:15", customerPhone = "01012345678", totalQty = 60 * count, totalPrice = 3600000 * count + 450000, itemSummary = OrderSummaryCodec.encode(lines), isPrepaid = true, shippingFee = 450000)
    }
    fun document(count: Int = 1) = QuoteDocument.create(quote(count), recipient, issuer, 0, "발행일로부터 7일", "테스트 자료 · 실제 발행용 아님", "테스트은행 000-000 테스트", 1791166500000L)
}

class QuoteDocumentTest {
    @Test fun snapshotUsesSavedPricesAndShippingAndSurvivesSerialization() {
        val document = QuoteTestData.document()
        assertEquals(4050000L, document.supplyAmount); assertEquals(4050000L, document.totalAmount)
        assertEquals(450000L, document.lines.last().amount)
        assertEquals(document, QuoteDocument.read(document.json()))
        assertEquals("buyer@example.com", document.recipient.customerEmail)
    }
    @Test fun taxIsExplicitAndCannotChangeSavedItems() {
        val document = QuoteDocument.create(QuoteTestData.quote(), QuoteTestData.recipient, QuoteTestData.issuer, 1000, "7일", "", "")
        assertEquals(4051000L, document.totalAmount); assertEquals(60000L, document.lines.first().unitPrice)
        assertTrue(runCatching { document.copy(totalAmount = 1).validate() }.isFailure)
    }
    @Test fun malformedEmailIssuerAndSavedTotalsPreventIssuing() {
        assertTrue(runCatching { QuoteTestData.recipient.copy(customerEmail = "x@y.com\nBcc:a@b.com").checked() }.isFailure)
        assertTrue(runCatching { QuoteTestData.recipient.copy(customerEmail = "").checked() }.isFailure)
        assertTrue(runCatching { QuoteTestData.issuer.copy(businessNumber = "").checked() }.isFailure)
        assertTrue(runCatching { QuoteDocument.create(QuoteTestData.quote().copy(totalPrice = 1), QuoteTestData.recipient, QuoteTestData.issuer, 0, "7일", "", "") }.isFailure)
    }
}
