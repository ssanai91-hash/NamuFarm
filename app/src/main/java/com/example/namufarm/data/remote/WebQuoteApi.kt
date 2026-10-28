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
    val authMode: String? = null,
    val requests: List<WebQuoteRemote?>? = null, val hasMore: Boolean? = null,
    val inquiries: List<CustomerInquiryRemote?>? = null, val inquiry: CustomerInquiryRemote? = null,
    val nextSequence: Long? = null,
    val saved: Boolean? = null, val orderNumber: String? = null, val revision: Long? = null, val fingerprint: String? = null,
    val keyRegistered: Boolean? = null, val keyId: String? = null, val encryptionReady: Boolean? = null,
    val closed: Boolean? = null, val updated: Boolean? = null, val hidden: Boolean? = null,
    val requestId: String? = null, val status: String? = null,
    val snapshotId: String? = null, val total: Int? = null, val watermark: Long? = null,
    val sent: Boolean? = null, val recipientPhone: String? = null, val recipientEmail: String? = null,
    val messageId: String? = null, val checked: Boolean? = null, val state: String? = null,
    val statusCode: String? = null, val statusMessage: String? = null,
    val speciesId: Int? = null,
    val deleted: Boolean? = null, val farmId: Int? = null, val operationId: String? = null
)
data class WebQuoteRemote(
    val requestId: String? = null, val sequence: Long? = null, val createdAt: String? = null,
    val customerName: String? = null, val customerPhone: String? = null,
    val region: String? = null, val items: List<WebQuoteItemRemote?>? = null, val message: String? = null,
    val customerEmail: String? = null, val formalQuoteRequested: Boolean? = null,
    val recipientName: String? = null, val replyChannel: String? = null,
    val encryptedRequest: com.example.namufarm.privacy.PrivacyEnvelope? = null,
    val expired: Boolean? = null, val expiresAt: Long? = null, val status: String? = null,
    val receiptNumber: String? = null,
    val quotePricing: com.google.gson.JsonObject? = null
)
data class WebQuoteItemRemote(val species: String? = null, val specification: String? = null, val quantity: Int? = null)

/** Only a missing legacy key is recoverable; malformed or tampered data still fails validation. */
class LegacyRequestKeyUnavailable : IllegalStateException("이전 요청을 여는 백업이 이 기기에 없습니다.")

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

class WebQuoteResponseException(val responseCode: String?, message: String) : IllegalStateException(message)

object WebQuoteValidator {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    fun validId(value: String) = uuid.matches(value)
    fun checkResponse(response: WebQuoteEnvelope) {
        if (response.ok != true) throw WebQuoteResponseException(response.code, when (response.code) {
            "UNAUTHORIZED" -> "서버 연결이 유효하지 않습니다. 서버 연결을 다시 시도해주세요."
            "CONFLICT" -> "서버의 현재 요청 상태와 맞지 않습니다. 최신 요청을 다시 가져와 확인해주세요."
            "UPGRADE_REQUIRED", "INVALID_ACTION" -> "앱과 구글시트 서버를 최신 버전으로 업데이트해주세요."
            "NOT_READY" -> "구글시트 서버 준비가 필요합니다. 서버 배포와 시트 설정을 확인해주세요."
            "DEVICE_LIMIT" -> "등록 가능한 기기 수가 찼습니다. 서버 관리자에게 등록 기기 정리를 요청해주세요."
            "BUSY" -> "서버가 다른 요청을 처리 중입니다. 잠시 후 다시 동기화해주세요."
            "INVALID" -> "요청 형식이 맞지 않습니다. 앱과 서버 버전을 확인해주세요."
            "SMS_BLOCKED" -> CustomerInquiryRemote.SMS_BLOCKED_MESSAGE
            else -> "서버 응답을 확인하지 못했어요. 저장된 기록을 유지하며 다시 시도할 수 있습니다."
        })
    }
    data class Page(val rows: List<WebQuoteRequestEntity>, val cursor: Long, val expiredIds: List<String>,
        val deferred: List<com.example.namufarm.data.DeferredWebRequestEntity> = emptyList())
    fun page(response: WebQuoteEnvelope, after: Long): List<WebQuoteRequestEntity> = checkedPage(response, after).rows
    fun checkedPage(response: WebQuoteEnvelope, after: Long, decrypt: ((WebQuoteRemote) -> WebQuoteRemote)? = null): Page {
        checkResponse(response)
        val rows = checkNotNull(response.requests) { "요청 목록이 누락되었습니다." }
        check(rows.size <= 100 && response.hasMore != null) { "요청 목록 형식을 확인해주세요." }
        check(rows.isNotEmpty() || response.hasMore == false) { "다음 요청 목록이 비어 있습니다." }
        var previous = after
        val seen = mutableSetOf<String>()
        val expiredIds = mutableListOf<String>()
        val deferred = mutableListOf<com.example.namufarm.data.DeferredWebRequestEntity>()
        val checked = rows.mapNotNull { nullable ->
            val outer = checkNotNull(nullable)
            var row = outer
            val id = checkNotNull(row.requestId)
            check(validId(id) && seen.add(id)) { "요청번호를 확인해주세요." }
            val sequence = checkNotNull(row.sequence)
            check(sequence > previous && sequence <= 9_007_199_254_740_991L) { "요청 순번이 올바르지 않습니다." }
            previous = sequence
            val created = checkNotNull(row.createdAt)
            check(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(created)) { "견적 접수시각 형식을 확인해주세요." }
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(created)
            // Archived rows use status="보관종료" and expired=true. They must be
            // removed from the local inbox, not rejected as an unknown active status.
            if (outer.expired == true) { expiredIds += id; return@mapNotNull null }
            val requestStatus = outer.status ?: "신규"
            check(requestStatus in setOf("신규", "견적확정", "견적발송완료", "입금확인", "배송완료", "구매취소")) {
                "견적 처리 상태가 맞지 않아 가져오지 못했습니다."
            }
            if (decrypt != null) {
                try {
                    row = decrypt(outer)
                } catch (_: LegacyRequestKeyUnavailable) {
                    val envelope = checkNotNull(outer.encryptedRequest)
                    check(envelope.version == 1 && envelope.algorithm == "RSA-OAEP-256+A256GCM") { "이전 견적의 암호화 형식이 맞지 않습니다." }
                    check(Regex("[a-f0-9]{64}").matches(envelope.keyId)) { "이전 견적의 보안키 형식이 맞지 않습니다." }
                    check(com.example.namufarm.privacy.HybridCrypto.decode(envelope.wrappedKey).size == 384) { "이전 견적의 보안 정보가 올바르지 않습니다." }
                    check(com.example.namufarm.privacy.HybridCrypto.decode(envelope.iv).size == 12) { "이전 견적의 보안 정보가 올바르지 않습니다." }
                    check(com.example.namufarm.privacy.HybridCrypto.decode(envelope.ciphertext).size in 17..30016) { "이전 견적의 암호화 자료가 올바르지 않습니다." }
                    deferred += com.example.namufarm.data.DeferredWebRequestEntity(id, sequence, created, Gson().toJson(outer))
                    return@mapNotNull null
                }
                check(row.requestId == id) { "복호화한 고객 요청번호가 다릅니다." }
            }
            val phone = text(row.customerPhone, 11, 9)
            check(Regex("0[0-9]{8,10}").matches(phone)) { "견적에 저장된 전화번호 형식을 확인해주세요." }
            val items = checkNotNull(row.items)
            check(items.size in 1..20) { "견적 묘목 품목 수를 확인해주세요." }
            val checkedItems = items.map { item ->
                checkNotNull(item)
                val quantity = checkNotNull(item.quantity)
                check(quantity in 1..1_000_000) { "견적 묘목 수량을 확인해주세요." }
                WebQuoteItem(text(item.species, 80, 1), text(item.specification, 120, 0), quantity)
            }
            val email = text(row.customerEmail ?: "", 254, 0)
            val channel = row.replyChannel ?: "sms"
            check(com.example.namufarm.quotation.validQuoteEmail(email) && channel in listOf("sms", "email", "both")) { "견적 메일 주소 또는 회신 방식을 확인해주세요." }
            check(channel == "sms" || email.isNotEmpty()) { "메일 견적 요청에 이메일 주소가 없습니다." }
            val recipient = text(row.recipientName ?: "", 100, 0)
            check(recipient.none { it.code < 32 }) { "견적서 수신인 형식을 확인해주세요." }
            WebQuoteRequestEntity(id, sequence, created, text(row.customerName, 50, 2), phone,
                text(row.region, 100, 0), Gson().toJson(checkedItems), text(row.message, 1000, 0), customerEmail = email,
                formalQuoteRequested = row.formalQuoteRequested ?: false, recipientName = recipient, replyChannel = channel, status = requestStatus,
                receiptNumber = row.receiptNumber?.also {
                    check(Regex("(NAMU|NFQ)-[0-9]{8}-[0-9]{4,16}").matches(it)) { "견적접수번호를 확인해주세요." }
                }.orEmpty(), quotePricingJson = outer.quotePricing?.toString().orEmpty())
        }
        return Page(checked, previous, expiredIds, deferred)
    }
    private fun text(value: String?, max: Int, min: Int): String = checkNotNull(value).also { check(it.length in min..max && it == it.trim()) { "견적에 저장된 글자 형식을 확인해주세요." } }
}





