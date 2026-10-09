package com.example.namufarm.sales

import com.example.namufarm.BuildConfig
import com.example.namufarm.data.OrderEntity
import com.example.namufarm.data.remote.WebQuoteApi
import com.example.namufarm.data.remote.WebQuoteTransport
import com.example.namufarm.data.remote.WebQuoteValidator
import com.example.namufarm.privacy.HybridCrypto
import com.example.namufarm.privacy.SellerKeys
import com.example.namufarm.webrequests.WebRequestSettings
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject

interface SalesGateway {
    fun prepare(order: OrderEntity, eventId: String, quoteDocumentJson: String? = null): PendingSaleSyncPayload
    suspend fun save(payload: PendingSaleSyncPayload): Boolean
    fun issuedDocuments(): List<com.example.namufarm.data.QuoteDocumentEntity> = emptyList()
    suspend fun orders(): List<OrderEntity>
    suspend fun report(granularity: String, from: String, to: String): SalesReport
}

object PrivacyWire {
    val gson = GsonBuilder().disableHtmlEscaping().create()
    private fun sort(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().apply { element.asJsonObject.entrySet().sortedBy { it.key }.forEach { add(it.key, sort(it.value)) } }
        element.isJsonArray -> com.google.gson.JsonArray().apply { element.asJsonArray.forEach { add(sort(it)) } }
        else -> element
    }
    fun canonical(value: Any): String = gson.toJson(sort(gson.toJsonTree(value)))
    fun customerFingerprint(metadata: SaleMetadata, personal: SalePrivateData): String =
        HybridCrypto.digest(canonical(mapOf("metadata" to metadata, "personalData" to personal)))
    fun fingerprint(metadata: SaleMetadata, envelope: com.example.namufarm.privacy.PrivacyEnvelope): String =
        HybridCrypto.digest(canonical(mapOf("metadata" to metadata, "encryptedPersonal" to envelope)))
}

class DeviceSalesGateway(private val settings: WebRequestSettings, private val keys: SellerKeys,
    private val api: WebQuoteApi = WebQuoteTransport.api, private val url: String? = null) : SalesGateway {
    private fun token(): String {
        check(settings.configured && settings.paired) { "홈페이지 요청함을 먼저 연결해주세요." }
        return settings.deviceToken
    }
    override fun prepare(order: OrderEntity, eventId: String, quoteDocumentJson: String?): PendingSaleSyncPayload {
        token()
        val meta = SalePolicy.metadata(order)
        val personal = SalePrivateData(order.copy(orderId = null), quoteDocumentJson)
        val payload=PendingSaleSyncPayload(eventId, meta, null, PrivacyWire.customerFingerprint(meta, personal), order.sourceRequestId, personal)
        require(PrivacyWire.gson.toJson(payload).length<=63000) { "품목이 많아 전송할 기록이 너무 큽니다. 견적을 나누어 작성해주세요." }
        return payload
    }
    override suspend fun save(payload: PendingSaleSyncPayload): Boolean {
        check((payload.personalData != null) != (payload.encryptedPersonal != null)) { "전송 대기 기록 형식을 확인해주세요." }
        val body = mutableMapOf<String, Any>("action" to "save_sale", "protocolVersion" to 5, "deviceToken" to token(), "eventId" to payload.eventId,
            "metadata" to payload.metadata, "fingerprint" to payload.fingerprint, "sourceRequestId" to payload.sourceRequestId)
        payload.personalData?.let { body["personalData"] = it }
        payload.encryptedPersonal?.let { body["encryptedPersonal"] = it } // Retry frozen v4 events unchanged.
        val response = api.request(url ?: settings.url, body)
        WebQuoteValidator.checkResponse(response)
        check(response.saved == true && response.orderNumber == payload.metadata.orderNumber && response.revision == payload.metadata.revision
            && response.fingerprint == payload.fingerprint) { "판매 기록 저장을 확인하지 못했습니다. 전송 대기 기록을 유지합니다." }
        return true
    }
    private var documents: List<com.example.namufarm.data.QuoteDocumentEntity> = emptyList()
    override fun issuedDocuments() = documents
    override suspend fun orders(): List<OrderEntity> {
        val docs=linkedMapOf<String,com.example.namufarm.data.QuoteDocumentEntity>()
        val result = mutableListOf<OrderEntity>(); val numbers = mutableSetOf<String>(); var cursor = 0L
        repeat(100) {
            val response = api.request(url ?: settings.url, mapOf("action" to "list_sales", "deviceToken" to token(), "afterSequence" to cursor))
            WebQuoteValidator.checkResponse(response)
            val rows = checkNotNull(response.sales); check(rows.size <= 100 && response.hasMore != null)
            check(rows.isNotEmpty() || response.hasMore == false)
            rows.forEach { row ->
                check(row.sequence > cursor && row.sequence <= SalePolicy.MAX_SAFE); cursor = row.sequence
                if (!row.expired) {
                    val meta = checkNotNull(row.metadata)
                    val plain = SaleWire.decode(row, keys)
                    check(numbers.add(meta.orderNumber))
                    check(PrivacyWire.canonical(SalePolicy.metadata(plain.order)) == PrivacyWire.canonical(meta)) { "판매 기록과 고객정보가 일치하지 않습니다." }
                    plain.quoteDocumentJson?.let { json ->
                        val document=PrivacyWire.gson.fromJson(json,com.example.namufarm.quotation.QuoteDocument::class.java).validate()
                        check(document.sourceQuoteNumber==plain.order.sourceQuoteNumber && document.customerPhone==plain.order.customerPhone && document.supplyAmount==plain.order.totalPrice.toLong() && document.taxAmount==plain.order.taxAmount)
                        val row=com.example.namufarm.data.QuoteDocumentEntity(document.id,document.sourceQuoteNumber,document.number,document.issuedAt,json)
                        val prior=docs.put(document.sourceQuoteNumber,row);check(prior==null || prior==row)
                    }
                    result.add(plain.order)
                }
            }
            if (response.hasMore == false) { documents=docs.values.toList();return result }
        }
        error("판매 기록이 많아 나누어 확인해야 합니다. 기존 기록은 유지됩니다.")
    }
    override suspend fun report(granularity: String, from: String, to: String): SalesReport {
        require(granularity in listOf("day", "month", "year"))
        val response = api.request(url ?: settings.url, mapOf("action" to "sales_report", "deviceToken" to token(), "granularity" to granularity, "from" to from, "to" to to))
        WebQuoteValidator.checkResponse(response)
        val report = checkNotNull(response.report)
        check(report.granularity == granularity && report.from == from && report.to == to)
        val seen = mutableSetOf<String>()
        report.buckets.forEach { b ->
            check(seen.add(b.period) && b.orders >= 0 && b.quantity >= 0 && b.sales >= 0 && b.tax >= 0 && b.cost >= 0 && b.expenses >= 0
                && b.unknownProfitOrders in 0..b.orders)
        }
        return report
    }
}

/** One decoder for live records and old encrypted records; never silently skips a customer. */
object SaleWire {
    fun decode(row: SaleRemote, keys: SellerKeys): SalePrivateData {
        val meta = checkNotNull(row.metadata)
        check((row.personalData != null) != (row.encryptedPersonal != null)) { "고객정보 형식을 확인해주세요." }
        row.personalData?.let {
            check(PrivacyWire.customerFingerprint(meta, it) == row.fingerprint) { "판매 기록이 일치하지 않습니다." }
            return it
        }
        val encrypted = checkNotNull(row.encryptedPersonal)
        check(PrivacyWire.fingerprint(meta, encrypted) == row.fingerprint)
        check(keys.initialized && keys.keyId() == encrypted.keyId) { "이전 버전의 판매 자료가 있습니다. 요청함의 ‘이전 자료 복원’에서 기존 백업을 불러와주세요." }
        return PrivacyWire.gson.fromJson(keys.open(encrypted, "order", row.eventId), SalePrivateData::class.java)
    }
}

