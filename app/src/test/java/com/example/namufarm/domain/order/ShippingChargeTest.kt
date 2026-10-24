package com.example.namufarm.domain.order

import org.junit.Assert.*
import org.junit.Test

class ShippingChargeTest {
    @Test fun newQuoteStartsPrepaidAtSixThousand() {
        val charge = ShippingCharge()
        assertTrue(charge.isPrepaid)
        assertEquals(6_000, charge.fee)
    }

    @Test fun switchingToCollectClearsChargeAndIgnoresAdjustment() {
        val collect = ShippingCharge().increase().select(false)
        assertFalse(collect.isPrepaid)
        assertEquals(0, collect.fee)
        assertEquals(collect, collect.increase())
        assertEquals(collect, collect.decrease())
    }

    @Test fun returningToPrepaidStartsAtDefaultInsteadOfPreviousCharge() {
        assertEquals(ShippingCharge(), ShippingCharge().increase().select(false).select(true))
    }

    @Test fun tappingSelectedPrepaidKeepsItsEditedAmount() {
        val charge = ShippingCharge().increase()
        assertEquals(6_500, charge.select(true).fee)
        assertEquals(6_000, charge.decrease().fee)
    }

    @Test fun decreaseStopsAtZeroAndCanIncreaseAgain() {
        var charge = ShippingCharge()
        repeat(20) { charge = charge.decrease() }
        assertEquals(0, charge.fee)
        assertTrue(charge.isPrepaid)
        assertEquals(500, charge.increase().fee)
    }

    @Test fun upperLimitNeverWrapsNegative() {
        val charge = ShippingCharge(fee = ShippingCharge.MAX_FEE)
        assertEquals(charge, charge.increase())
        assertEquals(ShippingCharge.MAX_FEE - 500, charge.decrease().fee)
    }
}
