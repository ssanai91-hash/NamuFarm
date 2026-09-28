package com.example.namufarm.domain.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    }

    @Test
    fun malformedLineFailsFast() {
        assertThrows(IllegalStateException::class.java) {
            OrderSummaryCodec.parseStrict("사과|상묘")
        }
    }
}
