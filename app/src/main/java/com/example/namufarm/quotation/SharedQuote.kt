package com.example.namufarm.quotation

import com.example.namufarm.BuildConfig
import com.example.namufarm.data.WebQuoteRequestEntity
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Server-shared confirmed web quote (Apps Script confirmedQuoteVersion:1).
 * The phone's own revision count, delivery evidence and history stay in [QuoteWorkflowRecord];
 * the server's revision/fingerprint are kept beside them and never computed locally.
 */
interface SharedQuoteGateway {
    suspend fun call(action: String, fields: Map<String, Any>): JsonObject
}

/** The connected server has no shared-quote API yet (older deployment). The legacy path stays. */
class SharedQuoteUnavailable : IllegalStateException("서버에 확정견적 공유 기능이 아직 배포되지 않았습니다.")

class SharedQuoteException(val code: String) : IllegalStateException(when (code) {
    "CONFLICT" -> "다른 기기에서 먼저 수정·발송·입금확인했습니다. 동기화 후 최신 견적을 다시 확인해주세요."
    "BUSY" -> "다른 기기에서 이 견적을 발송 처리 중입니다. 그 기기에서 결과를 확인한 뒤 다시 해주세요."
    "UPGRADE_REQUIRED" -> "이 견적은 새 공유 방식으로 저장되어 있습니다. 앱을 최신 버전으로 업데이트해주세요."
    "UNAUTHORIZED", "DEVICE_LIMIT" -> "운영 서버의 기기 연결을 확인해주세요."
    "INVALID" -> "견적 내용 형식이 서버 기준과 맞지 않습니다. 품목·메모 길이를 확인해주세요."
    else -> "서버 확인 결과를 받지 못했습니다. 저장된 기록을 유지하며 다시 시도할 수 있습니다."
})

data class SharedQuoteLink(val revision: Int = 0, val fingerprint: String? = null, val localKey: String? = null)

/** Written before revise_confirmed_quote; resent verbatim after a lost reply. */
data class PendingSharedRevision(val operationId: String? = null, val body: String? = null,
    val draft: SellerQuoteDraft? = null)

/** An external-app reservation (mode=external). Confirmed only by the seller's explicit attestation. */
data class SharedQuoteReservation(val deliveryId: String? = null, val channel: String? = null,
    val revision: Int = 0, val fingerprint: String? = null, val payloadFingerprint: String? = null)

/** Payment claim journal: the order number and operation ID survive a lost reply or app restart. */
data class SharedQuotePayment(val operationId: String? = null, val orderNumber: String? = null,
    val revision: Int = 0, val fingerprint: String? = null, val amount: Long = 0,
    val confirmed: Boolean = false, val orderCreated: Boolean = false)

internal fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.text(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

class SharedQuoteState(val quote: JsonObject) {
    val version: JsonObject? = quote.obj("quoteVersion")
    val pricing: JsonObject? = quote.obj("quotePricing")
    val revision: Int = version?.get("revision")?.asInt ?: if (pricing != null) 1 else 0
    val fingerprint: String = version?.text("fingerprint").orEmpty()
    val status: String = quote.text("status") ?: "신규"
    val details: JsonObject = version?.obj("quoteDetails") ?: JsonObject()
    val delivery: JsonObject? = version?.obj("delivery")
    val payment: JsonObject? = version?.obj("payment")
    val deliveredCurrent: Boolean get() = version?.obj("lastDelivered")?.let {
        it.get("revision")?.asInt == revision && it.text("fingerprint") == fingerprint
    } == true
    fun sameContent(draft: SellerQuoteDraft): Boolean = pricing != null && version != null &&
        sharedContentKey(pricing, details) == sharedContentKey(sharedPricing(draft), sharedDetails(draft, details))
    fun link(draft: SellerQuoteDraft) = SharedQuoteLink(revision, fingerprint, sharedLocalKey(draft))
}

/** Same quote pricing shape as the existing save_quote_pricing call. */
fun sharedPricing(draft: SellerQuoteDraft): JsonObject {
    val lines = draft.validate(QuoteDelivery.COMPLETE)
    return JsonObject().apply {
        add("items", JsonArray().apply {
            lines.forEach { line ->
                add(JsonObject().apply {
                    addProperty("species", line.speciesName); addProperty("specification", line.quality)
                    addProperty("quantity", line.orderQty); addProperty("unitPrice", line.retailPrice)
                })
            }
        })
        addProperty("isPrepaid", draft.prepaid)
        // Like Napi: the fee is kept for 후불 display; the server adds it to the total only when prepaid.
        addProperty("shippingFee", draft.fee.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0)
        addProperty("taxAmount", if (draft.recipient.formalQuoteRequested) draft.options.taxAmount() else 0L)
    }
}

/** Seller-edited fields only. Fields stored by another device are kept; a new field is added only with a value. */
fun sharedDetails(draft: SellerQuoteDraft, base: JsonObject?, issuedAt: Long = System.currentTimeMillis()): JsonObject {
    val details = base?.deepCopy() ?: JsonObject()
    fun put(target: JsonObject, key: String, value: String, default: String = "") {
        if (target.has(key) || value != default) target.addProperty(key, value)
    }
    put(details, "customerName", draft.recipient.customerName)
    put(details, "customerPhone", draft.phone.filter(Char::isDigit))
    put(details, "customerEmail", draft.recipient.customerEmail.trim())
    put(details, "recipientName", draft.recipient.recipientName)
    put(details, "notes", draft.options.notes)
    val notes = draft.rows.map { it.remarks }
    if (details.has("itemNotes") || notes.any { it.isNotBlank() })
        details.add("itemNotes", JsonArray().apply { notes.forEach { add(it) } })
    val issuer = details.obj("issuer")?.deepCopy() ?: JsonObject()
    val i = draft.options.issuer
    listOf("businessName" to i.businessName, "businessNumber" to i.businessNumber, "representative" to i.representative,
        "address" to i.address, "phone" to i.phone, "businessType" to i.businessType, "category" to i.category)
        .forEach { (key, value) -> put(issuer, key, value) }
    if (issuer.size() > 0 || details.has("issuer")) details.add("issuer", issuer)
    put(details, "validUntil", draft.options.validUntil)
    // This phone's built-in account text is the default, like Napi's empty account field.
    put(details, "paymentAccount", draft.options.account, BuildConfig.PAYMENT_ACCOUNT_TEXT)
    put(details, "replyChannel", draft.recipient.replyChannel, "sms")
    if (details.has("formalQuoteRequested") || draft.recipient.formalQuoteRequested)
        details.addProperty("formalQuoteRequested", draft.recipient.formalQuoteRequested)
    if (!details.has("issuedAt")) details.addProperty("issuedAt", issuedAt)
    return details
}

private fun canonicalJson(element: JsonElement, trim: Boolean): JsonElement = when {
    element.isJsonObject -> JsonObject().also { out ->
        element.asJsonObject.keySet().sorted().forEach { key -> out.add(key, canonicalJson(element.asJsonObject.get(key), trim)) }
    }
    element.isJsonArray -> JsonArray().also { out -> element.asJsonArray.forEach { out.add(canonicalJson(it, trim)) } }
    element.isJsonPrimitive && element.asJsonPrimitive.isString -> JsonPrimitive(if (trim) element.asString.trim() else element.asString)
    element.isJsonPrimitive && element.asJsonPrimitive.isNumber ->
        JsonPrimitive(BigDecimal(element.asString).stripTrailingZeros().toPlainString())
    else -> element
}

fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** The server trims detail strings; both sides are compared the same way. */
fun sharedContentKey(pricing: JsonObject, details: JsonObject): String = sha256Hex(JsonObject().apply {
    add("quotePricing", canonicalJson(pricing, false)); add("quoteDetails", canonicalJson(details, true))
}.toString())

/** This phone's own fields only, independent of fields another device added. */
fun sharedLocalKey(draft: SellerQuoteDraft): String =
    sharedContentKey(sharedPricing(draft), sharedDetails(draft, null, issuedAt = 0L))

/** The shared state the last sync embedded in the request row (no network). */
fun embeddedSharedState(request: WebQuoteRequestEntity): SharedQuoteState? {
    if (request.quotePricingJson.isBlank()) return null
    val pricing = runCatching { Gson().fromJson(request.quotePricingJson, JsonObject::class.java) }.getOrNull() ?: return null
    val version = pricing.remove("quoteVersion")?.takeIf { it.isJsonObject } ?: return null
    return SharedQuoteState(JsonObject().apply {
        addProperty("requestId", request.requestId); addProperty("status", request.status)
        add("quotePricing", pricing); add("quoteVersion", version)
    })
}

/** Server pricing and the seller's shared details on top of an existing draft (or the request). */
fun draftFromShared(request: WebQuoteRequestEntity, pricing: JsonObject, details: JsonObject,
    base: SellerQuoteDraft?): SellerQuoteDraft {
    val items = pricing.getAsJsonArray("items")
    val notes = details.get("itemNotes")?.takeIf { it.isJsonArray }?.asJsonArray?.takeIf { it.size() == items.size() }
    val rows = items.mapIndexed { index, element ->
        val item = element.asJsonObject
        SellerQuoteRow(item.get("species").asString, item.text("specification").orEmpty(),
            item.get("quantity").asInt.toString(), item.get("unitPrice").asInt.toString(),
            notes?.get(index)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
    }
    val tax = pricing.get("taxAmount").asLong
    require(tax in 0..Int.MAX_VALUE.toLong())
    val baseRecipient = base?.recipient ?: QuoteRecipient.from(request)
    val formal = details.get("formalQuoteRequested")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        ?: (baseRecipient.formalQuoteRequested || request.formalQuoteRequested)
    val recipient = baseRecipient.copy(
        customerName = details.text("customerName") ?: baseRecipient.customerName,
        customerEmail = details.text("customerEmail") ?: baseRecipient.customerEmail,
        recipientName = details.text("recipientName") ?: baseRecipient.recipientName,
        replyChannel = details.text("replyChannel") ?: baseRecipient.replyChannel,
        formalQuoteRequested = formal || tax > 0)
    val baseOptions = base?.options ?: QuoteIssueOptions()
    val issuerJson = details.obj("issuer")
    val b = baseOptions.issuer
    fun issuer(key: String, fallback: String) = issuerJson?.text(key) ?: fallback
    val options = baseOptions.copy(
        issuer = b.copy(businessName = issuer("businessName", b.businessName), businessNumber = issuer("businessNumber", b.businessNumber),
            representative = issuer("representative", b.representative), address = issuer("address", b.address),
            phone = issuer("phone", b.phone), businessType = issuer("businessType", b.businessType),
            category = issuer("category", b.category)),
        tax = tax.toString(), validUntil = details.text("validUntil") ?: baseOptions.validUntil,
        notes = details.text("notes") ?: baseOptions.notes, account = details.text("paymentAccount") ?: baseOptions.account)
    val phone = details.text("customerPhone")?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }
        ?: base?.phone ?: request.customerPhone
    return SellerQuoteDraft(rows, phone, recipient, pricing.get("isPrepaid").asBoolean,
        pricing.get("shippingFee").asInt.toString(), options)
}

/** Σ(수량×단가) + 선불 배송비 + 세액, the amount the server checks for a payment claim. */
fun sharedAmount(draft: SellerQuoteDraft): Long {
    val lines = draft.validate(QuoteDelivery.COMPLETE)
    var total = if (draft.recipient.formalQuoteRequested) draft.options.taxAmount() else 0L
    lines.forEach { total = Math.addExact(total, Math.multiplyExact(it.orderQty.toLong(), it.retailPrice.toLong())) }
    if (draft.prepaid) total = Math.addExact(total, draft.shipping().toLong())
    require(total in 0..Int.MAX_VALUE.toLong()) { "총금액이 저장 범위를 넘습니다." }
    return total
}
