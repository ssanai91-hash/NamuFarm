package com.example.namufarm.data.remote

import com.example.namufarm.data.OrderEntity
import com.example.namufarm.data.OrderStatus
import com.example.namufarm.domain.order.OrderSummaryCodec
import com.example.namufarm.domain.order.OrderAmounts

/** Missing optional fields mean legacy server; an explicit empty address means clear it. */
object OrderRemoteMapper {
    private fun parsed(value: String?) = value?.let {
        runCatching { OrderSummaryCodec.parseStrict(it) }.getOrNull()
    }

    fun requireSameOrder(local: OrderEntity, remote: SheetOrderRemoteDto) {
        val raw = remote.raw_summary?.takeIf { it.isNotBlank() } ?: remote.summary
        check(remote.order_number?.trim() == local.orderNumber &&
            remote.customer_phone == local.customerPhone &&
            remote.total_qty == local.totalQty && remote.total_price == local.totalPrice &&
            parsed(raw) != null && parsed(raw) == parsed(local.itemSummary)) {
            "주문번호 충돌 또는 서버 주문 원본 누락: ${local.orderNumber}. pending을 보존했습니다."
        }
    }

    fun merge(existing: OrderEntity?, dto: SheetOrderRemoteDto): OrderEntity {
        val number = dto.order_number?.trim().orEmpty()
        require(number.isNotBlank()) { "원격 주문번호가 비어 있습니다." }
        val remoteStatus = when (val status = dto.status?.trim()) {
            null, "" -> existing?.status ?: OrderStatus.CONFIRMED
            "입금대기", "입금완료" -> OrderStatus.CONFIRMED
            else -> status
        }
        require(OrderStatus.isValid(remoteStatus)) { "원격 주문 상태 오류: $number" }
        check(existing?.status != OrderStatus.CANCELED || remoteStatus == OrderStatus.CANCELED) {
            "취소된 주문을 원격에서 다시 활성화할 수 없습니다: $number"
        }
        val raw = dto.raw_summary?.takeIf { it.isNotBlank() }
        if (raw != null) OrderSummaryCodec.parseStrict(raw)
        val existingLines = parsed(existing?.itemSummary)
        val incoming = raw ?: dto.summary.orEmpty()
        val incomingLines = parsed(incoming)
        if (existingLines != null && incomingLines != null) {
            check(existingLines == incomingLines) { "원격 주문 품목 충돌: $number" }
        }
        if (existing != null) {
            check(dto.customer_phone == null || dto.customer_phone == existing.customerPhone) { "원격 주문 고객 충돌: $number" }
            check(dto.total_qty == null || dto.total_qty == existing.totalQty) { "원격 주문 수량 충돌: $number" }
            check(dto.total_price == null || dto.total_price == existing.totalPrice) { "원격 주문 금액 충돌: $number" }
        }
        val base = existing ?: OrderEntity(
            orderNumber = number, orderDate = dto.date.orEmpty(),
            customerPhone = dto.customer_phone.orEmpty(), totalQty = dto.total_qty ?: 0,
            totalPrice = dto.total_price ?: 0, itemSummary = incoming
        )
        val merged = base.copy(
            itemSummary = if (existingLines != null) base.itemSummary else if (incomingLines != null) incoming else base.itemSummary,
            orderDate = dto.date ?: base.orderDate,
            customerPhone = dto.customer_phone ?: base.customerPhone,
            totalQty = dto.total_qty ?: base.totalQty,
            totalPrice = dto.total_price ?: base.totalPrice,
            receiverName = dto.receiver_name ?: base.receiverName,
            zipNo = dto.zip_no ?: base.zipNo,
            roadAddress = dto.road_address ?: if (existing == null) dto.address.orEmpty() else base.roadAddress,
            detailAddress = dto.detail_address ?: base.detailAddress,
            note = dto.note ?: base.note,
            status = remoteStatus,
            isPrepaid = dto.is_prepaid ?: base.isPrepaid,
            shippingFee = dto.shipping_fee ?: base.shippingFee
        )
        require(merged.totalQty >= 0 && merged.totalPrice >= 0 && merged.shippingFee >= 0) {
            "원격 주문 수량/금액 오류: $number"
        }
        if (incomingLines != null && dto.is_prepaid != null && dto.shipping_fee != null) {
            val amounts = OrderAmounts.calculate(incomingLines, merged.isPrepaid, merged.shippingFee)
            require(merged.totalQty == amounts.quantity && merged.totalPrice == amounts.price) {
                "원격 주문 합계 불일치: $number"
            }
        }
        return merged
    }
}
