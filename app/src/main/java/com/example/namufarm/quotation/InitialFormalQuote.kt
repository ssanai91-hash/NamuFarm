package com.example.namufarm.quotation

import android.content.Context
import com.example.namufarm.data.WebQuoteRequestEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Build a document from the saved quote, never from the customer's free-form message. */
fun initialFormalQuote(request: WebQuoteRequestEntity, draft: SellerQuoteDraft,
    now: Long = System.currentTimeMillis()): QuoteDocument {
    val formal = draft.copy(recipient = draft.recipient.copy(formalQuoteRequested = true))
    formal.validate(QuoteDelivery.COMPLETE)
    val lines = formal.rows.map { row ->
        QuoteDocumentLine(row.name.trim(), row.spec.trim(), row.quantity.toInt(),
            row.price.toLong(), remarks = row.remarks.trim())
    }.toMutableList()
    if (formal.prepaid) lines.add(QuoteDocumentLine("택배비", "선불", 1, formal.shipping().toLong(), "건"))
    val supply = lines.fold(0L) { sum, line -> Math.addExact(sum, line.amount) }
    val tax = formal.options.taxAmount()
    val total = Math.addExact(supply, tax)
    require(total <= Int.MAX_VALUE.toLong()) { "세액을 포함한 견적 총금액이 저장 범위를 넘습니다." }
    val id = UUID.randomUUID().toString()
    val date = SimpleDateFormat("yyyyMMdd", Locale.KOREA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }.format(Date(now))
    return QuoteDocument(id = id, number = "NQ-$date-${id.take(8).uppercase(Locale.US)}",
        sourceQuoteNumber = request.receiptNumber.ifBlank { request.requestId }, issuedAt = now,
        customerPhone = formal.phone.filter(Char::isDigit), recipient = formal.recipient.checked(),
        issuer = formal.options.issuer.checked(), lines = lines, supplyAmount = supply, taxAmount = tax,
        totalAmount = total,
        shippingNote = if (formal.prepaid) "배송비 선불 · 합계에 포함" else "배송비 후불 · 합계에 미포함",
        validUntil = formal.options.validUntil.trim(), notes = formal.options.notes.trim(),
        paymentAccount = formal.options.account.trim()).validate()
}

/** Keep the issued snapshot before export; retries regenerate the same document number. */
class InitialFormalQuoteStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "initial_formal_quotes_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun issue(request: WebQuoteRequestEntity, draft: SellerQuoteDraft): QuoteDocument {
        val key = request.requestId + ":" + quoteWorkflowFingerprint(draft)
        val existing = preferences.getString(key, null)
        if (existing != null) return QuoteDocument.read(existing)
        val document = initialFormalQuote(request, draft)
        check(preferences.edit().putString(key, document.json()).commit()) {
            "공식견적서를 휴대폰에 저장하지 못했습니다. 다시 시도해주세요."
        }
        return document
    }
}
