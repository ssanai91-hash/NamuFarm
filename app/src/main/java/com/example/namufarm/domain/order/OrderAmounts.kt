package com.example.namufarm.domain.order

/** Persisted amounts are Int for compatibility; check in Long before narrowing. */
data class OrderAmounts(val quantity: Int, val price: Int) {
    companion object {
        fun calculate(lines: List<OrderLine>, isPrepaid: Boolean, shippingFee: Int): OrderAmounts {
            require(lines.isNotEmpty()) { "주문 품목 정보가 비어 있습니다." }
            require(shippingFee >= 0) { "배송비는 음수일 수 없습니다." }
            var quantity = 0L
            var price = if (isPrepaid) shippingFee.toLong() else 0L
            lines.forEach {
                require(it.orderQty > 0 && it.retailPrice >= 0) { "수량과 단가를 확인해주세요." }
                quantity += it.orderQty
                price += it.orderQty.toLong() * it.retailPrice
                require(quantity <= Int.MAX_VALUE && price <= Int.MAX_VALUE) {
                    "총 수량 또는 금액이 저장 가능한 범위를 초과했습니다. 견적을 나누어주세요."
                }
            }
            return OrderAmounts(quantity.toInt(), price.toInt())
        }
    }
}
