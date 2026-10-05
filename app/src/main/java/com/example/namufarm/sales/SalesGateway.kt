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
    fun fingerprint(metadata: SaleMetadata, envelope: com.example.namufarm.privacy.PrivacyEnvelope): String =
        HybridCrypto.digest(canonical(mapOf("metadata" to metadata, "encryptedPersonal" to envelope)))
}

class EncryptedSalesGateway(private val settings: WebRequestSettings, private val keys: SellerKeys,
    private val api: WebQuoteApi = WebQuoteTransport.api, private val url: String = BuildConfig.WEB_REQUEST_SCRIPT_URL) : SalesGateway {
    private fun token(): String {
        check(WebQuoteTransport.configured && settings.paired && keys.backedUp && keys.registered) { "홈페이지 요청함 연결과 고객정보 보안 설정을 먼저 완료해주세요." }
        return settings.deviceToken
    }
    override fun prepare(order: OrderEntity, eventId: String, quoteDocumentJson: String?): PendingSaleSyncPayload {
        token()
        val meta = SalePolicy.metadata(order)
        val envelope = keys.seal(PrivacyWire.gson.toJson(SalePrivateData(order.copy(orderId = null), quoteDocumentJson)), "order", eventId)
        val payload=PendingSaleSyncPayload(eventId, meta, envelope, PrivacyWire.fingerprint(meta, envelope), order.sourceRequestId)
        require(PrivacyWire.gson.toJson(payload).length<=63000) { "품목이 많아 보안 기록이 너무 큽니다. 견적을 나누어 작성해주세요." }
        return payload
    }
    override suspend fun save(payload: PendingSaleSyncPayload): Boolean {
        val response = api.request(url, mapOf("action" to "save_sale", "deviceToken" to token(), "eventId" to payload.eventId,
            "metadata" to payload.metadata, "encryptedPersonal" to payload.encryptedPersonal, "fingerprint" to payload.fingerprint,
            "sourceRequestId" to payload.sourceRequestId))
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
            val response = api.request(url, mapOf("action" to "list_sales", "deviceToken" to token(), "afterSequence" to cursor))
            WebQuoteValidator.checkResponse(response)
            val rows = checkNotNull(response.sales); check(rows.size <= 100 && response.hasMore != null)
            check(rows.isNotEmpty() || response.hasMore == false)
            rows.forEach { row ->
                check(row.sequence > cursor && row.sequence <= SalePolicy.MAX_SAFE); cursor = row.sequence
                if (!row.expired) {
                    val meta = checkNotNull(row.metadata); val encrypted = checkNotNull(row.encryptedPersonal)
                    check(PrivacyWire.fingerprint(meta, encrypted) == row.fingerprint && numbers.add(meta.orderNumber))
                    val plain = PrivacyWire.gson.fromJson(keys.open(encrypted, "order", row.eventId), SalePrivateData::class.java)
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
        val response = api.request(url, mapOf("action" to "sales_report", "deviceToken" to token(), "granularity" to granularity, "from" to from, "to" to to))
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
