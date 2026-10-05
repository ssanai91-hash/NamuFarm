package com.example.namufarm.privacy

import com.example.namufarm.data.OrderEntity
import com.example.namufarm.data.OrderStatus
import com.example.namufarm.sales.SalePolicy
import org.junit.Assert.*
import org.junit.Test

class SalePolicyTest {
    private fun order()=OrderEntity(orderNumber="ORDER_TEST",orderDate="2026-10-05 10:00",customerPhone="01012345678",totalQty=2,totalPrice=2100,itemSummary="소나무|||||2|1000|수기견적|500",isPrepaid=true,shippingFee=100,status=OrderStatus.PURCHASED,revision=1,purchasedAt=1000,updatedAt=1000,costTotal=1000,expenseTotal=100)
    @Test fun purchasePaymentDeliveryAndCompletionAreSeparateMonotonicSteps() {
        val first=order();assertTrue(runCatching {SalePolicy.transition(first,OrderStatus.DELIVERED,2000)}.isFailure)
        val paid=SalePolicy.transition(first,OrderStatus.CONFIRMED,2000);assertEquals(2000L,paid.paidAt)
        val delivered=SalePolicy.transition(paid,OrderStatus.DELIVERED,3000)
        assertTrue(runCatching {SalePolicy.transition(delivered,OrderStatus.COMPLETED,4000)}.isFailure)
        val done=SalePolicy.transition(delivered.copy(costConfirmed=true),OrderStatus.COMPLETED,4000)
        assertEquals(4L,done.revision);assertEquals(4000L,done.completedAt)
        assertNull(OrderStatus.next(done.status));assertFalse(OrderStatus.canCancel(done.status))
    }
    @Test fun profitIsUnknownUntilCostsAreReviewedAndCommissionUsesItsOwnFormula() {
        assertNull(SalePolicy.profit(SalePolicy.metadata(order())))
        assertEquals(1000L,SalePolicy.profit(SalePolicy.metadata(order().copy(costConfirmed=true))))
        assertEquals(530L,SalePolicy.profit(SalePolicy.metadata(order().copy(costConfirmed=true,profitMode="commission",commissionBps=3000))))
    }
    @Test fun monetaryOverflowNegativeCostAndTimeReversalAreRejected() {
        assertTrue(runCatching {SalePolicy.metadata(order().copy(costTotal=-1))}.isFailure)
        assertTrue(runCatching {SalePolicy.metadata(order().copy(costTotal=SalePolicy.MAX_SAFE,expenseTotal=1))}.isFailure)
        assertTrue(runCatching {SalePolicy.transition(order(),OrderStatus.CONFIRMED,999)}.isFailure)
    }
    @Test fun dailyMonthlyAndYearlyReportsUseKoreanDayBoundary() {
        val time=java.time.Instant.parse("2026-01-01T15:01:00Z").toEpochMilli()
        assertEquals("2026-01-02",SalePolicy.period(time,"day"));assertEquals("2026-01",SalePolicy.period(time,"month"));assertEquals("2026",SalePolicy.period(time,"year"))
    }
    @Test fun transactionDeadlineIsFiveCalendarYearsAndOpenOrdersDoNotExpire() {
        assertNull(PrivacyRetention.saleExpires(order()))
        val time=java.time.Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()
        val done=order().copy(status=OrderStatus.COMPLETED,completedAt=time)
        assertEquals(java.time.Instant.parse("2031-10-05T12:00:00Z").toEpochMilli(),PrivacyRetention.saleExpires(done))
    }
}
