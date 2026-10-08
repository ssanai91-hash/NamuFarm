package com.example.namufarm.data.remote

import com.example.namufarm.BuildConfig
import com.example.namufarm.data.WebQuoteItem
import com.example.namufarm.data.WebQuoteRequestEntity
import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

interface WebQuoteApi {
    @POST suspend fun request(@Url url: String, @Body body: Map<String, @JvmSuppressWildcards Any>): WebQuoteEnvelope
}

data class WebQuoteEnvelope(
    val ok: Boolean? = null, val code: String? = null,
    val paired: Boolean? = null, val registered: Boolean? = null,
    val requests: List<WebQuoteRemote?>? = null, val hasMore: Boolean? = null,
    val saved: Boolean? = null, val orderNumber: String? = null, val revision: Long? = null, val fingerprint: String? = null,
    val sales: List<com.example.namufarm.sales.SaleRemote>? = null, val report: com.example.namufarm.sales.SalesReport? = null,
    val keyRegistered: Boolean? = null, val keyId: String? = null, val encryptionReady: Boolean? = null,
    val closed: Boolean? = null
)
data class WebQuoteRemote(
    val requestId: String? = null, val sequence: Long? = null, val createdAt: String? = null,
    val customerName: String? = null, val customerPhone: String? = null,
    val region: String? = null, val items: List<WebQuoteItemRemote?>? = null, val message: String? = null,
    val customerEmail: String? = null, val formalQuoteRequested: Boolean? = null,
    val recipientName: String? = null, val replyChannel: String? = null,
    val encryptedRequest: com.example.namufarm.privacy.PrivacyEnvelope? = null,
    val expired: Boolean? = null, val expiresAt: Long? = null
)
data class WebQuoteItemRemote(val species: String? = null, val specification: String? = null, val quantity: Int? = null)

object WebQuoteTransport {
    val configured: Boolean get() = Regex("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec").matches(BuildConfig.WEB_REQUEST_SCRIPT_URL)
    internal fun clientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .addInterceptor(GoogleContentResponseInterceptor())
    val api: WebQuoteApi by lazy {
        Retrofit.Builder().baseUrl("https://script.google.com/")
            .client(clientBuilder().build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(WebQuoteApi::class.java)
    }
}

object WebQuoteValidator {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    fun validId(value: String) = uuid.matches(value)
    fun checkResponse(response: WebQuoteEnvelope) {
        check(response.ok == true) { if (response.code == "UNAUTHORIZED") "요청함 연결 코드를 다시 등록해주세요." else "홈페이지 요청을 가져오지 못했습니다. 잠시 후 다시 시도해주세요." }
    }
    data class Page(val rows: List<WebQuoteRequestEntity>, val cursor: Long, val expiredIds: List<String>)
    fun page(response: WebQuoteEnvelope, after: Long): List<WebQuoteRequestEntity> = checkedPage(response, after).rows
    fun checkedPage(response: WebQuoteEnvelope, after: Long, decrypt: ((WebQuoteRemote) -> WebQuoteRemote)? = null): Page {
        checkResponse(response)
        val rows = checkNotNull(response.requests) { "요청 목록이 누락되었습니다." }
        check(rows.size <= 100 && response.hasMore != null) { "요청 목록 형식을 확인해주세요." }
        check(rows.isNotEmpty() || response.hasMore == false) { "다음 요청 목록이 비어 있습니다." }
        var previous = after
        val seen = mutableSetOf<String>()
        val expiredIds = mutableListOf<String>()
        val checked = rows.mapNotNull { nullable ->
            val outer = checkNotNull(nullable)
            var row = outer
            val id = checkNotNull(row.requestId)
            check(validId(id) && seen.add(id)) { "요청번호를 확인해주세요." }
            val sequence = checkNotNull(row.sequence)
            check(sequence > previous && sequence <= 9_007_199_254_740_991L) { "요청 순번이 올바르지 않습니다." }
            previous = sequence
            val created = checkNotNull(row.createdAt)
            check(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(created))
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(created)
            if (outer.expired == true) { expiredIds += id; return@mapNotNull null }
            if (decrypt != null) {
                row = decrypt(outer)
                check(row.requestId == id) { "복호화한 고객 요청번호가 다릅니다." }
            }
            val phone = text(row.customerPhone, 11, 9)
            check(Regex("0[0-9]{8,10}").matches(phone))
            val items = checkNotNull(row.items)
            check(items.size in 1..20)
            val checkedItems = items.map { item ->
                checkNotNull(item)
                val quantity = checkNotNull(item.quantity)
                check(quantity in 1..1_000_000)
                WebQuoteItem(text(item.species, 80, 1), text(item.specification, 120, 0), quantity)
            }
            val email = text(row.customerEmail ?: "", 254, 0)
            val channel = row.replyChannel ?: "sms"
            check(com.example.namufarm.quotation.validQuoteEmail(email) && channel in listOf("sms", "email", "both"))
            check(channel == "sms" || email.isNotEmpty())
            val recipient = text(row.recipientName ?: "", 100, 0)
            check(recipient.none { it.code < 32 })
            WebQuoteRequestEntity(id, sequence, created, text(row.customerName, 50, 2), phone,
                text(row.region, 100, 2), Gson().toJson(checkedItems), text(row.message, 1000, 0), customerEmail = email, formalQuoteRequested = row.formalQuoteRequested ?: false, recipientName = recipient, replyChannel = channel)
        }
        return Page(checked, previous, expiredIds)
    }
    private fun text(value: String?, max: Int, min: Int): String = checkNotNull(value).also { check(it.length in min..max && it == it.trim()) }
}
