package com.example.namufarm.domain.order

import org.junit.Assert.*
import org.junit.Test

class OrderValidationTest {
    private val raw = "소나무|상묘|1년생|1.0m|실생|3|1000|농원|500"
    @Test fun malformedNumbersNeverBecomeZeroPrice() {
        assertTrue(runCatching { OrderSummaryCodec.parseStrict(raw.replace("|1000|", "|oops|")) }.isFailure)
    }
    @Test fun zeroAndNegativeQuantitiesAreRejected() {
        listOf("0", "-1").forEach { qty ->
            assertTrue(runCatching { OrderSummaryCodec.parseStrict(raw.replace("|3|", "|$qty|")) }.isFailure)
        }
    }
    @Test fun extraOrMissingFieldsAreNotTreatedAsLegacy() {
        assertTrue(runCatching { OrderSummaryCodec.parseStrict("소나무|상묘|1년생|3|1000|농원|500|extra") }.isFailure)
        assertTrue(runCatching { OrderSummaryCodec.parseStrict("$raw|INVENTORY|extra") }.isFailure)
    }
    @Test fun unknownSourceCannotSilentlyDeductStock() {
        assertTrue(runCatching { OrderSummaryCodec.parseStrict("$raw|TYPO") }.isFailure)
    }
    @Test fun reservedManualNameCannotBeEncodedAsRealInventory() {
        val line = OrderSummaryCodec.parseStrict(raw).single().copy(farmName = OrderSummaryCodec.MANUAL_FARM_LABEL)
        assertTrue(runCatching { OrderSummaryCodec.encode(listOf(line)) }.isFailure)
    }
    @Test fun delimitersCannotCorruptAStoredLine() {
        val line = OrderSummaryCodec.parseStrict(raw).single().copy(speciesName = "소나무|감나무")
        assertTrue(runCatching { OrderSummaryCodec.encode(listOf(line)) }.isFailure)
    }
    @Test fun prepaidShippingIsAddedExactlyOnce() {
        val lines = OrderSummaryCodec.parseStrict(raw)
        assertEquals(OrderAmounts(3, 3500), OrderAmounts.calculate(lines, true, 500))
        assertEquals(OrderAmounts(3, 3000), OrderAmounts.calculate(lines, false, 500))
    }
    @Test fun lineProductAndAggregateOverflowAreRejectedBeforeSaving() {
        val line = OrderSummaryCodec.parseStrict(raw).single()
        assertTrue(runCatching { OrderAmounts.calculate(listOf(line.copy(orderQty = Int.MAX_VALUE)), false, 0) }.isFailure)
        assertTrue(runCatching { OrderAmounts.calculate(listOf(line.copy(orderQty = 1, retailPrice = Int.MAX_VALUE)), true, 1) }.isFailure)
        assertTrue(runCatching { OrderAmounts.calculate(listOf(line.copy(orderQty = Int.MAX_VALUE, retailPrice = 0), line), false, 0) }.isFailure)
    }
    @Test fun emptySpecificationsInManualQuotesRoundTrip() {
        val lines = OrderSummaryCodec.parseStrict("감나무|||||2|1000|수기견적|0")
        assertFalse(lines.single().isInventoryBacked)
        assertEquals(lines, OrderSummaryCodec.parseStrict(OrderSummaryCodec.encode(lines)))
    }
    @Test fun negativeShippingIsRejectedEvenForPostpaidOrders() {
        assertTrue(runCatching { OrderAmounts.calculate(OrderSummaryCodec.parseStrict(raw), false, -1) }.isFailure)
    }
}
