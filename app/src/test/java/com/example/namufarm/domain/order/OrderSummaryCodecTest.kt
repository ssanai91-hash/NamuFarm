package com.example.namufarm.domain.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderSummaryCodecTest {

    @Test
    fun parseCurrentNineFieldFormat() {
        val result = OrderSummaryCodec.parseStrict(
            "사과|상묘|1년생|1.5m|실생|12|15000|행복농원|8000"
        )

        assertEquals(1, result.size)
        assertEquals("사과", result[0].speciesName)
        assertEquals("1.5m", result[0].size)
        assertEquals("실생", result[0].plantType)
        assertEquals(12, result[0].orderQty)
        assertEquals(8000, result[0].wholesalePrice)
        assertTrue(result[0].isInventoryBacked)
    }

    @Test
    fun parseLegacySevenFieldFormat() {
        val result = OrderSummaryCodec.parseStrict(
            "배|상묘|1년생|5|12000|나무농원|7000"
        )

        assertEquals(1, result.size)
        assertEquals("0.0m", result[0].size)
        assertEquals("실생", result[0].plantType)
        assertEquals(5, result[0].orderQty)
        assertTrue(result[0].isInventoryBacked)
    }

    @Test
    fun encodeAndParseManualLineRoundTripsSource() {
        val encoded = OrderSummaryCodec.encode(
            listOf(
                OrderLine(
                    speciesName = "감나무",
                    quality = "상묘",
                    age = "2년생",
                    size = "1.2m",
                    plantType = "접목",
                    orderQty = 3,
                    retailPrice = 25000,
                    farmName = OrderSummaryCodec.MANUAL_FARM_LABEL,
                    wholesalePrice = 0,
                    source = OrderLineSource.MANUAL
                )
            )
        )

        val result = OrderSummaryCodec.parseStrict(encoded)

        assertEquals(1, result.size)
        assertEquals(OrderLineSource.MANUAL, result[0].source)
        assertFalse(result[0].isInventoryBacked)
        assertEquals(3, result[0].orderQty)
        assertEquals(25000, result[0].retailPrice)
    }

    @Test
    fun legacyNineFieldManualFarmIsRecognizedAsManual() {
        val result = OrderSummaryCodec.parseStrict(
            "감나무|상묘|2년생|1.2m|접목|3|25000|수기견적|0"
        )

        assertEquals(OrderLineSource.MANUAL, result[0].source)
        assertFalse(result[0].isInventoryBacked)
    }

    @Test
    fun explicitInventorySourceWinsForNormalFarm() {
        val result = OrderSummaryCodec.parseStrict(
            "사과|상묘|1년생|1.5m|실생|12|15000|행복농원|8000|INVENTORY"
        )

        assertEquals(OrderLineSource.INVENTORY, result[0].source)
        assertTrue(result[0].isInventoryBacked)
    }

    @Test
    fun malformedLineFailsFast() {
        assertThrows(IllegalStateException::class.java) {
            OrderSummaryCodec.parseStrict("사과|상묘")
        }
    }
}
