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
    val requests: List<WebQuoteRemote?>? = null, val hasMore: Boolean? = null
)
data class WebQuoteRemote(
    val requestId: String? = null, val sequence: Long? = null, val createdAt: String? = null,
    val customerName: String? = null, val customerPhone: String? = null,
    val region: String? = null, val items: List<WebQuoteItemRemote?>? = null, val message: String? = null
)
data class WebQuoteItemRemote(val species: String? = null, val specification: String? = null, val quantity: Int? = null)

object WebQuoteTransport {
    val configured: Boolean get() = Regex("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec").matches(BuildConfig.WEB_REQUEST_SCRIPT_URL)
    val api: WebQuoteApi by lazy {
        Retrofit.Builder().baseUrl("https://script.google.com/")
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(WebQuoteApi::class.java)
    }
}

object WebQuoteValidator {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    fun validId(value: String) = uuid.matches(value)
    fun checkResponse(response: WebQuoteEnvelope) {
        check(response.ok == true) { if (response.code == "UNAUTHORIZED") "요청함 연결 코드를 다시 등록해주세요." else "홈페이지 요청을 가져오지 못했습니다. 잠시 후 다시 시도해주세요." }
    }
    fun page(response: WebQuoteEnvelope, after: Long): List<WebQuoteRequestEntity> {
        checkResponse(response)
        val rows = checkNotNull(response.requests) { "요청 목록이 누락되었습니다." }
        check(rows.size <= 100 && response.hasMore != null) { "요청 목록 형식을 확인해주세요." }
        check(rows.isNotEmpty() || response.hasMore == false) { "다음 요청 목록이 비어 있습니다." }
        var previous = after
        val seen = mutableSetOf<String>()
        return rows.map { nullable ->
            val row = checkNotNull(nullable)
            val id = checkNotNull(row.requestId)
            check(validId(id) && seen.add(id)) { "요청번호를 확인해주세요." }
            val sequence = checkNotNull(row.sequence)
            check(sequence > previous && sequence <= 9_007_199_254_740_991L) { "요청 순번이 올바르지 않습니다." }
            previous = sequence
            val created = checkNotNull(row.createdAt)
            check(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(created))
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(created)
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
            WebQuoteRequestEntity(id, sequence, created, text(row.customerName, 50, 2), phone,
                text(row.region, 100, 2), Gson().toJson(checkedItems), text(row.message, 1000, 0))
        }
    }
    private fun text(value: String?, max: Int, min: Int): String = checkNotNull(value).also { check(it.length in min..max && it == it.trim()) }
}
