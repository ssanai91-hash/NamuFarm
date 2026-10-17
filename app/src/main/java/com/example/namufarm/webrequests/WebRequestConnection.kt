package com.example.namufarm.webrequests

import android.content.Context
import com.example.namufarm.BuildConfig
import com.example.namufarm.data.remote.WebQuoteTransport
import com.example.namufarm.data.remote.WebQuoteValidator
import com.example.namufarm.data.repository.NamuFarmRepository

/** Transport credentials stay outside the business tables and outside the APK. */
class WebRequestConnection(context: Context, private val repository: NamuFarmRepository) {
    private val app = context.applicationContext
    private val settings = WebRequestSettings(app)
    private val api = settings.authenticatedApi(WebQuoteTransport.api)
    private val keys = com.example.namufarm.privacy.SellerKeys(app)
    val configured get() = settings.configured
    val paired get() = settings.paired
    val pushConfigured get() = WebRequestPush.configured
    val serverUrl get() = settings.url
    fun localRequests() = repository.getWebRequests()
    val deferredCount get() = repository.deferredWebRequestCount()
    suspend fun read(id: String) = repository.markWebRequestRead(id)
    /** No file or password: new devices enroll on first manual sync or server connect. */
    suspend fun connect() {
        if (settings.paired) return
        check(settings.configured) { "나무팜 서버 주소를 확인해주세요." }
        val response = WebQuoteTransport.api.request(settings.url, mapOf(
            "action" to "connect_app", "authMode" to "auto-v1",
            "deviceToken" to settings.prepareToken(), "label" to "나무팜 Android"
        ))
        WebQuoteValidator.checkResponse(response)
        check(response.paired == true && response.authMode == "auto-v1") {
            "운영 Apps Script 배포가 이전 인증 방식입니다. Code.gs·SecureCommerce.gs를 최신 버전으로 반영하고 기존 웹앱 배포를 업데이트해주세요."
        }
        settings.markPaired()
        WebRequestPush.initialize(app)
        PushRegistrationWorker.enqueue(app)
    }
    suspend fun connectKey(key: String, url: String) {
        val profile = AppConnectionInput.fromInput(url, key)
        check(profile.url == settings.url || (!settings.paired && localRequests().isEmpty() && deferredCount == 0
                && repository.getAllOrders().isEmpty() && repository.getAllQuotes().isEmpty())) {
            "기존 자료가 있는 앱은 다른 서버로 바꿀 수 없습니다."
        }
        val response = WebQuoteTransport.api.request(profile.url, mapOf("action" to "connect_app",
            "authMode" to "shared-key-v1", "connectionKey" to profile.connectionKey,
            "deviceToken" to settings.prepareToken(), "label" to "나무팜 Android"))
        WebQuoteValidator.checkResponse(response)
        check(response.paired == true) { "연결 암호를 확인해주세요." }
        check(response.authMode == "shared-key-v1") { "공통 연결 암호를 지원하는 서버 버전으로 업데이트해주세요." }
        settings.markConnected(profile.url, profile.connectionKey)
        WebRequestPush.initialize(app)
        PushRegistrationWorker.enqueue(app)
    }
    suspend fun pair(invite: String) {
        check(configured) { "홈페이지 요청함 주소 설정이 필요합니다." }
        check(Regex("[a-f0-9]{64}").matches(invite.trim())) { "관리자가 발급한 연결 코드를 확인해주세요." }
        val result = WebQuoteTransport.api.request(settings.url, mapOf(
            "action" to "pair_device", "invite" to invite.trim(), "deviceToken" to settings.prepareToken(), "label" to "나무팜 Android"
        ))
        WebQuoteValidator.checkResponse(result)
        check(result.paired == true) { "기기 연결을 확인하지 못했습니다. 같은 코드로 다시 시도해주세요." }
        settings.markPaired()
        WebRequestPush.initialize(app)
        PushRegistrationWorker.enqueue(app)
    }
    suspend fun refresh(): Int {
        if (!paired) connect()
        check(configured && paired) { "홈페이지 요청함을 먼저 연결해주세요." }
        PushRegistrationWorker.enqueue(app)
        return repository.syncWebSnapshot(api, settings.url, settings.deviceToken, ::readRequest)
    }
    suspend fun latestRequest(id: String): com.example.namufarm.data.WebQuoteRequestEntity? {
        refresh()
        return localRequests().firstOrNull { it.requestId == id }
    }
    private fun readRequest(outer: com.example.namufarm.data.remote.WebQuoteRemote): com.example.namufarm.data.remote.WebQuoteRemote =
        readLegacyWebRequest(outer, keys)
    suspend fun restoreLegacy(): Int = repository.restoreDeferredWebRequests(::readRequest)
    fun restoreBackup(uri: android.net.Uri, password: CharArray) {
        try {
            val bytes=checkNotNull(app.contentResolver.openInputStream(uri)).use { stream ->
                val out=java.io.ByteArrayOutputStream();val buf=ByteArray(2048)
                while(true) { val n=stream.read(buf); if(n<0) break; check(out.size()+n<=20000);out.write(buf,0,n) };out.toByteArray()
            }
            keys.importBackup(bytes.toString(Charsets.UTF_8),password)
            bytes.fill(0)
        } finally { password.fill('\u0000') }
    }
    suspend fun quoteAction(action: String, fields: Map<String, Any>): com.example.namufarm.data.remote.WebQuoteEnvelope {
        check(paired) { "서버를 먼저 연결해주세요." }
        require(action in setOf("send_quote_sms", "quote_sms_status", "send_quote_email"))
        val response = api.request(settings.url, fields + mapOf("action" to action, "deviceToken" to settings.deviceToken))
        if (response.ok != true && response.code == "CONFLICT" && action in setOf("send_quote_sms", "send_quote_email")) {
            val current = latestRequest(fields["requestId"] as String)
            if (current == null || current.status != "신규") throw com.example.namufarm.quotation.ServerQuoteRequestChanged()
            throw IllegalStateException("서버가 전송을 거절했습니다. 요청은 신규 상태입니다. 운영 서버의 문자·메일 처리 버전을 확인해주세요.")
        }
        WebQuoteValidator.checkResponse(response)
        return response
    }
    suspend fun confirmQuote(id: String, draft: com.example.namufarm.quotation.SellerQuoteDraft) {
        check(paired)
        val quotePricing = mapOf<String, Any>(
            "items" to draft.rows.map { row ->
                mapOf("species" to row.name, "specification" to row.spec,
                    "quantity" to (row.quantity.toIntOrNull() ?: error("수량을 확인해주세요.")),
                    "unitPrice" to (row.price.toIntOrNull() ?: error("단가를 확인해주세요.")))
            },
            "isPrepaid" to draft.prepaid,
            "shippingFee" to draft.shipping(),
            "taxAmount" to if (draft.recipient.formalQuoteRequested) draft.options.taxAmount() else 0L
        )
        val response = api.request(settings.url, mapOf(
            "action" to "save_quote_pricing", "deviceToken" to settings.deviceToken,
            "requestId" to id, "quotePricing" to quotePricing
        ))
        WebQuoteValidator.checkResponse(response)
        check(response.updated == true && response.requestId == id && response.status == "견적확정") {
            "견적확정 상태를 확인하지 못했습니다."
        }
    }
    suspend fun markQuoteSent(id: String) {
        check(paired) { "홈페이지 요청함을 먼저 연결해주세요." }
        val response = api.request(settings.url, mapOf(
            "action" to "update_request_status", "deviceToken" to settings.deviceToken,
            "requestId" to id, "status" to "견적발송완료"
        ))
        WebQuoteValidator.checkResponse(response)
        check(response.updated == true && response.requestId == id && response.status == "견적발송완료") {
            "견적 발송완료 상태를 서버에서 확인하지 못했습니다."
        }
    }
    suspend fun close(id: String) {
        check(paired)
        val response=api.request(settings.url,mapOf("action" to "close_request","deviceToken" to settings.deviceToken,"requestId" to id))
        WebQuoteValidator.checkResponse(response);check(response.closed==true)
        repository.closeLocalRequest(id)
    }
}

internal fun readLegacyWebRequest(outer: com.example.namufarm.data.remote.WebQuoteRemote, keys: com.example.namufarm.privacy.SellerKeys): com.example.namufarm.data.remote.WebQuoteRemote {
    val envelope = outer.encryptedRequest ?: return outer
    if (!keys.initialized || keys.keyId() != envelope.keyId) throw com.example.namufarm.data.remote.LegacyRequestKeyUnavailable()
    return com.google.gson.Gson().fromJson(keys.open(envelope, "quote", checkNotNull(outer.requestId)), com.example.namufarm.data.remote.WebQuoteRemote::class.java)
}




