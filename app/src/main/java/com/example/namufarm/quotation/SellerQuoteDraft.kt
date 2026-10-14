package com.example.namufarm.quotation

import com.example.namufarm.BuildConfig
import com.example.namufarm.data.WebQuoteItem
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.domain.order.OrderSummaryCodec
import com.example.namufarm.domain.order.OrderAmounts
import com.example.namufarm.domain.order.OrderLine
import com.example.namufarm.domain.order.OrderLineSource
import com.google.gson.Gson

enum class QuoteDelivery { SAVE, SMS, EMAIL, COMPLETE }

data class SellerQuoteRow(val name: String = "", val spec: String = "", val quantity: String = "", val price: String = "2000") {
    fun line(): OrderLine {
        require(name.isNotBlank()) { "묘목명을 입력해주세요." }
        val qty = quantity.toIntOrNull()?.takeIf { it > 0 } ?: error("수량은 1주 이상 입력해주세요.")
        val unit = price.toIntOrNull()?.takeIf { it >= 0 } ?: error("모든 품목의 판매 단가를 입력해주세요.")
        return OrderLine(name.trim(), spec.trim(), "", "", "", qty, unit, "수기견적", 0, OrderLineSource.MANUAL)
    }
}

/** Long arithmetic prevents the price buttons from wrapping into negative prices. */
fun adjustedQuotePrice(value: String, delta: Int): String =
    ((value.toLongOrNull() ?: 0L).coerceIn(0, Int.MAX_VALUE.toLong()) + delta.toLong())
        .coerceIn(0, Int.MAX_VALUE.toLong()).toString()

data class QuoteIssueOptions(
    val issuer: QuoteIssuer = QuoteIssuer(), val tax: String = "0",
    val validUntil: String = "발행일로부터 7일",
    val notes: String = "견적 유효기간 경과 후에는 가격과 묘목 확보 가능 여부를 다시 확인해 주세요.",
    val account: String = BuildConfig.PAYMENT_ACCOUNT_TEXT
) {
    fun taxAmount(): Long = tax.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
        ?: error("추가 세액을 확인해주세요.")
}

data class SellerQuoteDraft(
    val rows: List<SellerQuoteRow> = listOf(SellerQuoteRow()), val phone: String = "",
    val recipient: QuoteRecipient = QuoteRecipient(), val prepaid: Boolean = false, val fee: String = "0",
    val options: QuoteIssueOptions = QuoteIssueOptions()
) {
    fun shipping(): Int = if (prepaid) fee.toIntOrNull()?.takeIf { it >= 0 } ?: error("배송비를 확인해주세요.") else 0
    fun validate(action: QuoteDelivery): List<OrderLine> {
        require(phone.replace(Regex("[\\s()-]"), "").matches(Regex("0[0-9]{8,10}"))) { "고객 전화번호를 확인해주세요." }
        recipient.checked()
        require(action != QuoteDelivery.EMAIL || recipient.customerEmail.isNotBlank()) { "메일을 보낼 이메일 주소를 입력해주세요." }
        require(rows.isNotEmpty() && rows.size <= 50) { "품목은 1~50개 입력해주세요." }
        val lines = rows.map { it.line() }
        OrderAmounts.calculate(lines, prepaid, shipping())
        if (recipient.formalQuoteRequested) {
            require(recipient.customerName.isNotBlank() || recipient.recipientName.isNotBlank()) { "공식 견적서의 고객 성함 또는 수신인을 입력해주세요." }
            options.issuer.checked(); options.taxAmount()
            require(options.validUntil.isNotBlank() && options.validUntil.length <= 100) { "견적 유효기간을 확인해주세요." }
            require(options.notes.length <= 500 && options.account.length <= 500) { "안내 문구는 500자 이내로 입력해주세요." }
        }
        return lines
    }
    companion object {
        fun from(quote: QuoteEntity, recipient: QuoteRecipient, issuer: QuoteIssuer): SellerQuoteDraft {
            val lines = OrderSummaryCodec.parseStrict(quote.itemSummary)
            require(lines.all { it.source == OrderLineSource.MANUAL })
            return SellerQuoteDraft(lines.map { SellerQuoteRow(it.speciesName, listOf(it.quality, it.age, it.size, it.plantType).filter(String::isNotBlank).joinToString(" / "), it.orderQty.toString(), it.retailPrice.toString()) },
                quote.customerPhone, recipient, quote.isPrepaid, quote.shippingFee.toString(), QuoteIssueOptions(issuer))
        }
        fun from(request: WebQuoteRequestEntity?, issuer: QuoteIssuer): SellerQuoteDraft = SellerQuoteDraft(
            rows = request?.let { Gson().fromJson(it.itemsJson, Array<WebQuoteItem>::class.java).map { row ->
                SellerQuoteRow(row.species, row.specification, row.quantity.toString(), "2000")
            } } ?: listOf(SellerQuoteRow()),
            phone = request?.customerPhone.orEmpty(), recipient = request?.let(QuoteRecipient::from) ?: QuoteRecipient(),
            prepaid = request != null, fee = if (request != null) "6000" else "0",
            options = QuoteIssueOptions(issuer = issuer)
        )
    }
}
