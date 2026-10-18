package com.example.namufarm.domain.order

/** The same charge supplies the quote total, saved quote, and outgoing message. */
data class ShippingCharge(
    val isPrepaid: Boolean = true,
    val fee: Int = if (isPrepaid) DEFAULT_FEE else 0
) {
    init {
        require(fee in 0..MAX_FEE && (isPrepaid || fee == 0))
    }

    fun select(prepaid: Boolean): ShippingCharge = when {
        prepaid == isPrepaid -> this
        prepaid -> ShippingCharge()
        else -> ShippingCharge(isPrepaid = false, fee = 0)
    }

    fun increase(): ShippingCharge = step(STEP)
    fun decrease(): ShippingCharge = step(-STEP)

    private fun step(delta: Int): ShippingCharge = if (!isPrepaid) this else
        copy(fee = (fee.toLong() + delta).coerceIn(0, MAX_FEE.toLong()).toInt())

    companion object {
        const val DEFAULT_FEE = 6_000
        const val STEP = 500
        const val MAX_FEE = Int.MAX_VALUE / STEP * STEP
    }
}
