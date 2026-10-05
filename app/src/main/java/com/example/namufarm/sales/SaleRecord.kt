package com.example.namufarm.sales

import com.example.namufarm.data.OrderEntity
import com.example.namufarm.data.OrderStatus
import com.example.namufarm.domain.order.OrderSummaryCodec
import com.example.namufarm.domain.order.OrderAmounts
import com.example.namufarm.privacy.PrivacyEnvelope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SaleItem(val species: String, val quantity: Int, val unitPrice: Int)
data class SaleMetadata(
    val orderNumber: String, val orderDate: String, val status: String, val revision: Long,
    val totalQty: Int, val totalPrice: Int, val isPrepaid: Boolean, val shippingFee: Int,
    val purchasedAt: Long, val paidAt: Long, val deliveredAt: Long, val completedAt: Long, val updatedAt: Long,
    val costTotal: Long, val expenseTotal: Long, val costConfirmed: Boolean,
    val profitMode: String, val commissionBps: Int, val taxAmount: Long, val items: List<SaleItem>
)
data class SalePrivateData(val order: OrderEntity, val quoteDocumentJson: String? = null)
data class PendingSaleSyncPayload(val eventId: String, val metadata: SaleMetadata, val encryptedPersonal: PrivacyEnvelope,
    val fingerprint: String, val sourceRequestId: String = "")
data class SaleRemote(val sequence: Long, val eventId: String, val metadata: SaleMetadata?, val encryptedPersonal: PrivacyEnvelope?,
    val fingerprint: String?, val expired: Boolean = false)
data class SalesBucket(val period: String, val orders: Long, val quantity: Long, val sales: Long, val tax: Long,
    val cost: Long, val expenses: Long, val knownProfit: Long, val unknownProfitOrders: Long)
data class SalesReport(val granularity: String, val from: String, val to: String, val buckets: List<SalesBucket>)

object SalePolicy {
    const val MAX_SAFE = 9007199254740991L
    fun metadata(order: OrderEntity): SaleMetadata {
        require(Regex("[A-Za-z0-9_-]{1,120}").matches(order.orderNumber) && OrderStatus.isValid(order.status) && order.revision > 0)
        val lines = OrderSummaryCodec.parseStrict(order.itemSummary)
        val totals = OrderAmounts.calculate(lines, order.isPrepaid, order.shippingFee)
        require(order.totalQty == totals.quantity && order.totalPrice == totals.price)
        listOf(order.costTotal, order.expenseTotal, order.taxAmount).forEach { require(it in 0..MAX_SAFE) }
        require(Math.addExact(order.totalPrice.toLong(), order.taxAmount) <= MAX_SAFE)
        require(Math.addExact(order.costTotal,order.expenseTotal)<=MAX_SAFE)
        require(order.profitMode in listOf("resale", "commission") && order.commissionBps in 0..10000)
        require(order.purchasedAt > 0 && order.updatedAt in order.purchasedAt..MAX_SAFE)
        require(listOf(order.paidAt, order.deliveredAt, order.completedAt).all { it in 0..order.updatedAt })
        val rank = listOf(OrderStatus.PURCHASED, OrderStatus.CONFIRMED, OrderStatus.DELIVERED, OrderStatus.COMPLETED).indexOf(order.status)
        if (rank >= 1) require(order.paidAt >= order.purchasedAt)
        if (rank >= 2) require(order.deliveredAt >= order.paidAt)
        if (rank >= 3) require(order.completedAt >= order.deliveredAt && order.costConfirmed)
        return SaleMetadata(order.orderNumber, order.orderDate, order.status, order.revision, order.totalQty, order.totalPrice,
            order.isPrepaid, order.shippingFee, order.purchasedAt, order.paidAt, order.deliveredAt, order.completedAt, order.updatedAt,
            order.costTotal, order.expenseTotal, order.costConfirmed, order.profitMode, order.commissionBps, order.taxAmount,
            lines.map { SaleItem(it.speciesName, it.orderQty, it.retailPrice) })
    }
    fun profit(meta: SaleMetadata): Long? = if (!meta.costConfirmed) null else {
        val income = if (meta.profitMode == "commission") Math.multiplyExact(meta.totalPrice.toLong(), meta.commissionBps.toLong()) / 10000
            else meta.totalPrice.toLong()
        Math.subtractExact(Math.subtractExact(income, if (meta.profitMode == "commission") 0 else meta.costTotal), meta.expenseTotal)
    }
    fun transition(order: OrderEntity, target: String, now: Long): OrderEntity {
        require(OrderStatus.next(order.status) == target) { "입금확인 → 배송확인 → 판매완료 순서로 처리해주세요." }
        require(now >= order.updatedAt && now >= order.purchasedAt)
        require(target != OrderStatus.COMPLETED || order.costConfirmed) { "원가와 비용을 먼저 확인해주세요." }
        return order.copy(status = target, revision = Math.addExact(order.revision, 1), updatedAt = now,
            paidAt = if (target == OrderStatus.CONFIRMED) now else order.paidAt,
            deliveredAt = if (target == OrderStatus.DELIVERED) now else order.deliveredAt,
            completedAt = if (target == OrderStatus.COMPLETED) now else order.completedAt).also { metadata(it) }
    }
    fun legacy(order: OrderEntity, now: Long = System.currentTimeMillis()): OrderEntity {
        if (order.revision > 0) return order
        val time = SimpleDateFormat(if(order.orderDate.length==10) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul"); isLenient = false }.parse(order.orderDate)?.time ?: error("이전 주문 날짜를 확인해주세요.")
        require(time in 1..now)
        return order.copy(orderDate=if(order.orderDate.length==10) order.orderDate+" 00:00" else order.orderDate,revision=1, purchasedAt=time, paidAt=if(order.status==OrderStatus.CONFIRMED) time else 0,
            updatedAt=now, customerName=order.customerName.orEmpty(), customerEmail=order.customerEmail.orEmpty(),
            sourceRequestId=order.sourceRequestId.orEmpty(), sourceQuoteNumber=order.sourceQuoteNumber.orEmpty(), profitMode="resale")
    }
    fun period(timestamp: Long, granularity: String): String = SimpleDateFormat(when(granularity) {
        "day" -> "yyyy-MM-dd"; "month" -> "yyyy-MM"; "year" -> "yyyy"; else -> error("집계 단위를 확인해주세요.")
    }, Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date(timestamp))
}
