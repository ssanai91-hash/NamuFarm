package com.example.namufarm.data

import com.example.namufarm.domain.order.OrderAmounts
import com.example.namufarm.domain.order.OrderSummaryCodec
import com.example.namufarm.quotation.QuoteWorkflowRecord
import com.example.namufarm.quotation.sharedPricing
import com.google.gson.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The existing protocol-v5 sale contract. No legacy order/stock packet is sent. */
internal object OrderProtocol {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private const val MAX_SAFE = 9_007_199_254_740_991L
    private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
    fun id(value: String) = uuid.matches(value)
    fun obj(value: JsonObject, key: String): JsonObject = value.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        ?: error("주문 $key 정보가 누락되었습니다.")
    fun text(value: JsonObject, key: String): String = value.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: error("주문 $key 문자열 형식을 확인해주세요.")
    fun number(value: JsonObject, key: String, minimum: Long = 0): Long {
        val raw = value.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?: error("주문 $key 숫자 형식을 확인해주세요.")
        val n = raw.asString.toLongOrNull() ?: error("주문 $key 정수 형식을 확인해주세요.")
        check(n in minimum..MAX_SAFE) { "주문 $key 범위를 확인해주세요." }
        return n
    }
    fun bool(value: JsonObject, key: String): Boolean = value.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        ?: error("주문 $key 상태 형식을 확인해주세요.")
    private fun format(pattern: String, zone: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone(zone); isLenient = false
    }
    fun iso(time: Long): String = format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "UTC").format(Date(time))
    fun instant(value: String): Long {
        check(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(value)) { "서버 입금시각 형식을 확인해주세요." }
        return checkNotNull(format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "UTC").parse(value)).time
    }
    fun day(time: Long): String = format("yyyyMMdd", "Asia/Seoul").format(Date(time))
    fun orderDate(time: Long): String = format("yyyy-MM-dd HH:mm", "Asia/Seoul").format(Date(time))
    fun status(value: String): String = when (value) {
        "구매확정", "입금확인" -> "입금확인"
        "배송확인", "판매완료", "배송완료" -> "배송완료"
        "주문취소", "구매취소" -> "구매취소"
        else -> error("주문 상태를 확인해주세요: $value")
    }
    private fun canonical(value: JsonElement): String = when {
        value.isJsonNull -> "null"
        value.isJsonObject -> value.asJsonObject.keySet().sorted().joinToString(",", "{", "}") {
            gson.toJson(it) + ":" + canonical(value.asJsonObject.get(it))
        }
        value.isJsonArray -> value.asJsonArray.joinToString(",", "[", "]") { canonical(it) }
        value.asJsonPrimitive.isNumber -> value.asBigDecimal.stripTrailingZeros().toPlainString()
        else -> gson.toJson(value)
    }
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun fingerprint(meta: JsonObject, personal: JsonObject): String {
        val normalized = personal.deepCopy()
        obj(normalized, "order").remove("orderId")
        if (normalized.get("quoteDocumentJson")?.isJsonNull == true) normalized.remove("quoteDocumentJson")
        val root = JsonObject().apply { add("metadata", meta); add("personalData", normalized) }
        // Matches Apps Script canonical_: no trimming or HTML escaping, including separators.
        return hash(canonical(root).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"))
    }
    fun seed(order: OrderEntity, at: Long, web: QuoteWorkflowRecord? = null): JsonObject {
        val lines = OrderSummaryCodec.parseStrict(order.itemSummary)
        val totals = OrderAmounts.calculate(lines, order.isPrepaid, order.shippingFee)
        check(totals.quantity == order.totalQty) { "주문 품목 수량과 합계가 다릅니다." }
        val pricing = web?.let { sharedPricing(it.draft) }
        val tax = pricing?.let { number(it, "taxAmount") } ?: 0L
        check(totals.price.toLong() + tax == order.totalPrice.toLong()) { "공급가·배송비·세액 합계를 확인해주세요." }
        val paidAt = web?.sharedPayment?.confirmedAt?.takeIf { it > 0 } ?: at
        check(paidAt > 0 && paidAt <= at + 300_000) { "입금확인 시각을 확인해주세요." }
        val meta = JsonObject().apply {
            addProperty("orderNumber", order.orderNumber); addProperty("orderDate", order.orderDate)
            addProperty("status", "입금확인"); addProperty("revision", 1L)
            addProperty("totalQty", order.totalQty); addProperty("totalPrice", totals.price)
            addProperty("isPrepaid", order.isPrepaid); addProperty("shippingFee", order.shippingFee)
            addProperty("purchasedAt", paidAt); addProperty("paidAt", paidAt)
            addProperty("deliveredAt", 0L); addProperty("completedAt", 0L); addProperty("updatedAt", maxOf(at, paidAt))
            addProperty("costTotal", lines.fold(0L) { cost, line -> Math.addExact(cost, Math.multiplyExact(line.wholesalePrice.toLong(), line.orderQty.toLong())) })
            addProperty("expenseTotal", 0L); addProperty("costConfirmed", false)
            addProperty("profitMode", "resale"); addProperty("commissionBps", 0L); addProperty("taxAmount", tax)
            add("items", JsonArray().apply { lines.forEach { line -> add(JsonObject().apply {
                addProperty("species", line.speciesName.trim()); addProperty("quantity", line.orderQty); addProperty("unitPrice", line.retailPrice)
            }) } })
        }
        val personalOrder = meta.deepCopy().apply {
            remove("items")
            addProperty("customerName", web?.draft?.recipient?.customerName.orEmpty())
            addProperty("customerPhone", order.customerPhone)
            addProperty("customerEmail", web?.draft?.recipient?.customerEmail.orEmpty())
            addProperty("receiverName", order.receiverName); addProperty("zipNo", order.zipNo)
            addProperty("roadAddress", order.roadAddress); addProperty("detailAddress", order.detailAddress); addProperty("note", order.note)
            addProperty("itemSummary", order.itemSummary)
            addProperty("sourceRequestId", web?.request?.requestId.orEmpty())
            addProperty("sourceQuoteNumber", web?.request?.let { it.receiptNumber.ifBlank { it.requestId } }.orEmpty())
        }
        return JsonObject().apply {
            add("metadata", meta); add("personalData", JsonObject().apply { add("order", personalOrder) })
            addProperty("sourceRequestId", web?.request?.requestId.orEmpty())
            web?.sharedPayment?.let {
                addProperty("expectedRevision", it.revision); addProperty("quoteFingerprint", checkNotNull(it.fingerprint))
                addProperty("amount", it.amount)
            }
        }.also(::validateSale)
    }
    fun saveBody(seed: JsonObject, serverNumber: String, eventId: String, revision: Long): JsonObject {
        val body = seed.deepCopy().apply {
            addProperty("action", "save_sale"); addProperty("protocolVersion", 5); addProperty("eventId", eventId)
        }
        val meta = obj(body, "metadata")
        meta.addProperty("orderNumber", serverNumber); meta.addProperty("revision", revision)
        val personal = obj(body, "personalData")
        val order = obj(personal, "order")
        meta.entrySet().filter { it.key != "items" }.forEach { order.add(it.key, it.value.deepCopy()) }
        order.remove("orderId")
        body.addProperty("fingerprint", fingerprint(meta, personal))
        validateSale(body)
        return body
    }
    fun editSeed(row: JsonObject, before: OrderEntity, after: OrderEntity, kind: String, at: Long): JsonObject {
        check(matches(row, before)) { "CONFLICT: 서버 주문이 이 기기의 수정 전 내용과 다릅니다. 서버 주문을 확인해주세요." }
        val meta = obj(row, "metadata").deepCopy()
        val personal = obj(row, "personalData").deepCopy()
        val p = obj(personal, "order")
        check(at >= number(meta, "updatedAt")) { "기기 시각이 서버의 마지막 변경시각보다 이전입니다." }
        if (kind == "status") {
            val next = status(after.status)
            meta.addProperty("status", next)
            if (next == "배송완료" && number(meta, "deliveredAt") == 0L) {
                meta.addProperty("deliveredAt", at); meta.addProperty("completedAt", at)
            }
        } else {
            check(kind == "delivery")
            p.addProperty("receiverName", after.receiverName); p.addProperty("zipNo", after.zipNo)
            p.addProperty("roadAddress", after.roadAddress); p.addProperty("detailAddress", after.detailAddress); p.addProperty("note", after.note)
        }
        meta.addProperty("updatedAt", at)
        return JsonObject().apply {
            add("metadata", meta); add("personalData", personal)
            addProperty("sourceRequestId", text(p, "sourceRequestId"))
        }
    }
    fun validateSale(body: JsonObject) {
        val meta = obj(body, "metadata"); val personal = obj(body, "personalData"); val order = obj(personal, "order")
        val orderNumber = text(meta, "orderNumber")
        check(Regex("[A-Za-z0-9_-]{1,120}").matches(orderNumber)) { "서버 주문번호 형식을 확인해주세요." }
        check(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(text(meta, "orderDate")))
        status(text(meta, "status")); number(meta, "revision", 1); bool(meta, "isPrepaid"); bool(meta, "costConfirmed")
        check(text(meta, "profitMode") in setOf("resale", "commission")); check(number(meta, "commissionBps") <= 10000)
        listOf("purchasedAt", "updatedAt").forEach { number(meta, it, 1) }
        listOf("paidAt", "deliveredAt", "completedAt", "costTotal", "expenseTotal", "taxAmount", "shippingFee").forEach { number(meta, it) }
        val items = meta.getAsJsonArray("items") ?: error("주문 품목이 없습니다.")
        check(items.size() in 1..100)
        var qty = 0L; var price = if (bool(meta, "isPrepaid")) number(meta, "shippingFee") else 0L
        items.forEach { value -> val item = value.asJsonObject
            check(text(item, "species").trim().length in 1..200)
            qty = Math.addExact(qty, number(item, "quantity", 1))
            price = Math.addExact(price, Math.multiplyExact(number(item, "quantity", 1), number(item, "unitPrice")))
        }
        check(qty == number(meta, "totalQty", 1) && qty <= Int.MAX_VALUE)
        check(price == number(meta, "totalPrice") && price + number(meta, "taxAmount") <= Int.MAX_VALUE)
        check(number(meta, "shippingFee") <= Int.MAX_VALUE)
        val phone = text(order, "customerPhone").replace(Regex("[\\s()-]"), "")
        check(Regex("0[0-9]{8,10}").matches(phone)) { "주문 고객 전화번호를 확인해주세요." }
        val limits = mapOf("customerName" to 100, "customerPhone" to 30, "customerEmail" to 254, "receiverName" to 100,
            "zipNo" to 10, "roadAddress" to 300, "detailAddress" to 300, "note" to 3000, "itemSummary" to 20000,
            "sourceQuoteNumber" to 120, "sourceRequestId" to 36)
        limits.forEach { (key, max) -> val value = text(order, key)
            check(value.length <= max && value.none { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 })
        }
        val source = text(order, "sourceRequestId")
        check(source.isEmpty() || id(source)); check(source == text(body, "sourceRequestId"))
        meta.entrySet().filter { it.key != "items" }.forEach { check(canonical(it.value) == canonical(checkNotNull(order.get(it.key)))) }
        // Incoming PC summaries need only the server's 7/9/10-column contract, not local stock linkage.
        val lines = text(order, "itemSummary").lines().filter { it.isNotBlank() }.map { it.split('|').map { field -> field.trim() } }
        check(lines.size == items.size())
        lines.forEachIndexed { index, line -> val item = items[index].asJsonObject
            check(line.size in setOf(7, 9, 10))
            val q = if (line.size == 7) 3 else 5
            check(line[0] == text(item, "species") && line[q].toLongOrNull() == number(item, "quantity") && line[q + 1].toLongOrNull() == number(item, "unitPrice"))
        }
        check(gson.toJson(personal).length <= 40000)
    }
    fun validateRow(row: JsonObject) {
        number(row, "sequence", 1); check(id(text(row, "eventId")))
        val meta = obj(row, "metadata"); val personal = obj(row, "personalData")
        validateSale(JsonObject().apply {
            add("metadata", meta); add("personalData", personal); addProperty("sourceRequestId", text(obj(personal, "order"), "sourceRequestId"))
        })
        check(text(row, "fingerprint") == fingerprint(meta, personal)) { "서버 주문의 내용 지문이 맞지 않습니다. 기존 자료를 유지합니다." }
    }
    fun local(row: JsonObject, localNumber: String, orderId: Int? = null): OrderEntity {
        val meta = obj(row, "metadata"); val p = obj(obj(row, "personalData"), "order")
        return OrderEntity(orderId = orderId, orderNumber = localNumber, orderDate = text(meta, "orderDate"),
            status = status(text(meta, "status")), isPrepaid = bool(meta, "isPrepaid"), shippingFee = number(meta, "shippingFee").toInt(),
            customerPhone = text(p, "customerPhone"), totalQty = number(meta, "totalQty").toInt(),
            totalPrice = (number(meta, "totalPrice") + number(meta, "taxAmount")).toInt(), itemSummary = text(p, "itemSummary"),
            receiverName = text(p, "receiverName"), zipNo = text(p, "zipNo"), roadAddress = text(p, "roadAddress"),
            detailAddress = text(p, "detailAddress"), note = text(p, "note"))
    }
    fun matches(row: JsonObject, current: OrderEntity): Boolean = local(row, current.orderNumber, current.orderId).let { remote ->
        remote == current.copy(status = status(current.status))
    }
}
