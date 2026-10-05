package com.example.namufarm.quotation

import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.domain.order.OrderAmounts
import com.example.namufarm.domain.order.OrderSummaryCodec
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private val emailPattern = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
fun validQuoteEmail(value: String): Boolean = value.length <= 254 && value.none { it.code < 33 || it.code == 127 } && (value.isEmpty() || emailPattern.matches(value))
private fun cleanText(value: String, maximum: Int, required: Boolean = false): String {
    val text = value.trim()
    require(text.length <= maximum && (!required || text.isNotEmpty()) && text.none { it.code < 32 }) { "입력 내용을 확인해주세요." }
    return text
}

data class QuoteRecipient(
    val customerName: String = "", val customerEmail: String = "", val recipientName: String = "",
    val replyChannel: String = "sms", val formalQuoteRequested: Boolean = false, val sourceRequestId: String? = null
) {
    fun checked(): QuoteRecipient {
        val email = customerEmail.trim()
        require(validQuoteEmail(email)) { "이메일 주소를 확인해주세요." }
        require(replyChannel in listOf("sms", "email", "both"))
        require(replyChannel == "sms" || email.isNotEmpty()) { "이메일로 받을 고객의 주소를 입력해주세요." }
        return copy(customerName = cleanText(customerName, 50), customerEmail = email, recipientName = cleanText(recipientName, 100))
    }
    companion object {
        fun from(request: WebQuoteRequestEntity) = QuoteRecipient(request.customerName, request.customerEmail, request.recipientName, request.replyChannel, request.formalQuoteRequested, request.requestId)
    }
}

data class QuoteIssuer(
    val businessName: String = "나무팜", val businessNumber: String = "", val representative: String = "",
    val address: String = "", val phone: String = "", val businessType: String = "", val category: String = ""
) {
    fun checked() = copy(
        businessName = cleanText(businessName, 50, true), businessNumber = cleanText(businessNumber, 20, true),
        representative = cleanText(representative, 50, true), address = cleanText(address, 150, true),
        phone = cleanText(phone, 30, true), businessType = cleanText(businessType, 50), category = cleanText(category, 50)
    ).also { require(Regex("[0-9]{3}-?[0-9]{2}-?[0-9]{5}").matches(it.businessNumber)) { "사업자등록번호를 확인해주세요." } }
}

data class QuoteDocumentLine(val name: String, val specification: String, val quantity: Int, val unitPrice: Long, val unit: String = "주") {
    val amount: Long get() = Math.multiplyExact(quantity.toLong(), unitPrice)
}

data class QuoteDocument(
    val schemaVersion: Int = 1, val id: String, val number: String, val sourceQuoteNumber: String,
    val issuedAt: Long, val customerPhone: String, val recipient: QuoteRecipient, val issuer: QuoteIssuer,
    val lines: List<QuoteDocumentLine>, val supplyAmount: Long, val taxAmount: Long, val totalAmount: Long,
    val shippingNote: String, val validUntil: String, val notes: String, val paymentAccount: String
) {
    fun validate(): QuoteDocument {
        require(schemaVersion == 1 && Regex("[0-9a-f-]{36}").matches(id) && number.matches(Regex("NQ-[0-9]{8}-[A-F0-9]{8}")))
        require(sourceQuoteNumber.isNotBlank() && issuedAt > 0 && customerPhone.matches(Regex("0[0-9]{8,10}")))
        recipient.checked(); issuer.checked()
        require(recipient.recipientName.isNotBlank() || recipient.customerName.isNotBlank()) { "견적서 수신인 또는 고객 성함을 입력해주세요." }
        require(lines.isNotEmpty() && lines.size <= 201)
        lines.forEach { require(it.name.isNotBlank() && it.name.length <= 200 && it.specification.length <= 500 && it.quantity > 0 && it.unitPrice >= 0) }
        val sum = lines.fold(0L) { total, line -> Math.addExact(total, line.amount) }
        require(supplyAmount == sum && taxAmount in 0..Int.MAX_VALUE.toLong() && totalAmount == Math.addExact(sum, taxAmount)) { "견적 합계를 확인해주세요." }
        cleanText(validUntil, 100, true)
        require(notes.length <= 500 && paymentAccount.length <= 500 && notes.none { it.code < 32 && it != '\n' } && paymentAccount.none { it.code < 32 && it != '\n' })
        return this
    }
    fun json(): String = Gson().toJson(validate())
    companion object {
        fun read(json: String) = Gson().fromJson(json, QuoteDocument::class.java).validate()
        fun create(quote: QuoteEntity, recipient: QuoteRecipient, issuer: QuoteIssuer, taxAmount: Long, validUntil: String, notes: String, paymentAccount: String, now: Long = System.currentTimeMillis()): QuoteDocument {
            val items = OrderSummaryCodec.parseStrict(quote.itemSummary)
            val amounts = OrderAmounts.calculate(items, quote.isPrepaid, quote.shippingFee)
            require(quote.totalPrice == amounts.price && quote.totalQty == amounts.quantity) { "저장된 견적 합계가 품목과 다릅니다." }
            val lines = items.map { QuoteDocumentLine(it.speciesName, listOf(it.quality, it.age, it.size, it.plantType).filter(String::isNotBlank).joinToString(" / "), it.orderQty, it.retailPrice.toLong()) }.toMutableList()
            if (quote.isPrepaid) lines.add(QuoteDocumentLine("운반비", "선불", 1, quote.shippingFee.toLong(), "건"))
            val id = UUID.randomUUID().toString()
            val date = SimpleDateFormat("yyyyMMdd", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date(now))
            return QuoteDocument(id = id, number = "NQ-$date-${id.take(8).uppercase(Locale.US)}", sourceQuoteNumber = quote.quoteNumber, issuedAt = now,
                customerPhone = quote.customerPhone.replace(Regex("[\\s()-]"), ""), recipient = recipient.checked(), issuer = issuer.checked(), lines = lines,
                supplyAmount = quote.totalPrice.toLong(), taxAmount = taxAmount, totalAmount = Math.addExact(quote.totalPrice.toLong(), taxAmount),
                shippingNote = if (quote.isPrepaid) "배송비 선불 · 합계에 포함" else "배송비 착불 · 합계에 미포함",
                validUntil = cleanText(validUntil, 100, true), notes = notes.trim(), paymentAccount = paymentAccount.trim()).validate()
        }
    }
}
