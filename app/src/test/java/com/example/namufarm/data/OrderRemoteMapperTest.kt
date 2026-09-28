package com.example.namufarm.data

import com.example.namufarm.data.remote.OrderRemoteMapper
import com.example.namufarm.data.remote.SheetOrderRemoteDto
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class OrderRemoteMapperTest {
    private val raw = "감나무|||||2|1000|수기견적|0"
    private val order = OrderEntity(
        orderNumber = "ORDER", orderDate = "2026-09-29", customerPhone = "010",
        totalQty = 2, totalPrice = 2500, itemSummary = raw, isPrepaid = true, shippingFee = 500,
        zipNo = "01234", roadAddress = "서울", detailAddress = "101호"
    )
    private fun dto() = SheetOrderRemoteDto(
        "ORDER", "2026-09-29", "010", 2, 2500, "사람용 요약", "받는사람", "합친 주소", "메모",
        OrderStatus.CONFIRMED, raw, true, 500, "01234", "서울", "101호"
    )
    @Test fun newDeviceRestoresAllStructuredFields() {
        val restored = OrderRemoteMapper.merge(null, dto())
        assertEquals(raw, restored.itemSummary)
        assertTrue(restored.isPrepaid)
        assertEquals(500, restored.shippingFee)
        assertEquals("01234", restored.zipNo)
        assertEquals("서울", restored.roadAddress)
        assertEquals("101호", restored.detailAddress)
    }
    @Test fun explicitEmptyAddressFieldsClearPreviousValues() {
        val merged = OrderRemoteMapper.merge(order, dto().copy(zip_no = "", road_address = "", detail_address = ""))
        assertEquals("", merged.zipNo)
        assertEquals("", merged.roadAddress)
        assertEquals("", merged.detailAddress)
    }
    @Test fun absentLegacyFieldsPreserveLocalStructuredInformation() {
        val merged = OrderRemoteMapper.merge(order, dto().copy(raw_summary = null, is_prepaid = null,
            shipping_fee = null, zip_no = null, road_address = null, detail_address = null))
        assertEquals(order.itemSummary, merged.itemSummary)
        assertEquals(order.zipNo, merged.zipNo)
        assertEquals(order.shippingFee, merged.shippingFee)
        assertEquals(order.isPrepaid, merged.isPrepaid)
    }
    @Test fun structuredRawCanRepairPreviouslyImportedDisplaySummary() {
        assertEquals(raw, OrderRemoteMapper.merge(order.copy(itemSummary = "사람용 요약"), dto()).itemSummary)
    }
    @Test fun canceledOrderCannotBeResurrectedByPull() {
        assertTrue(runCatching { OrderRemoteMapper.merge(order.copy(status = OrderStatus.CANCELED), dto()) }.isFailure)
    }
    @Test fun sameNumberWithDifferentItemsCannotBeMergedOrAcknowledged() {
        val different = dto().copy(raw_summary = raw.replace("감나무", "사과"))
        assertTrue(runCatching { OrderRemoteMapper.merge(order, different) }.isFailure)
        assertTrue(runCatching { OrderRemoteMapper.requireSameOrder(order, different) }.isFailure)
    }
    @Test fun rawSummaryAcceptsItemSummaryWireAlias() {
        val dto = Gson().fromJson("""{"order_number":"ORDER","item_summary":"$raw"}""", SheetOrderRemoteDto::class.java)
        assertEquals(raw, dto.raw_summary)
    }
}
