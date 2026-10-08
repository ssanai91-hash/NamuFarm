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
    private val keys = com.example.namufarm.privacy.SellerKeys(app)
    val configured get() = WebQuoteTransport.configured
    val paired get() = settings.paired
    val pushConfigured get() = WebRequestPush.configured
    fun localRequests() = repository.getWebRequests()
    val deferredCount get() = repository.deferredWebRequestCount()
    suspend fun read(id: String) = repository.markWebRequestRead(id)
    suspend fun pair(invite: String) {
        check(configured) { "홈페이지 요청함 주소 설정이 필요합니다." }
        check(Regex("[a-f0-9]{64}").matches(invite.trim())) { "관리자가 발급한 연결 코드를 확인해주세요." }
        val result = WebQuoteTransport.api.request(BuildConfig.WEB_REQUEST_SCRIPT_URL, mapOf(
            "action" to "pair_device", "invite" to invite.trim(), "deviceToken" to settings.prepareToken(), "label" to "나무팜 Android"
        ))
        WebQuoteValidator.checkResponse(result)
        check(result.paired == true) { "기기 연결을 확인하지 못했습니다. 같은 코드로 다시 시도해주세요." }
        settings.markPaired()
        WebRequestPush.initialize(app)
        PushRegistrationWorker.enqueue(app)
    }
    suspend fun refresh(): Int {
        check(configured && paired) { "홈페이지 요청함을 먼저 연결해주세요." }
        PushRegistrationWorker.enqueue(app)
        return repository.syncWebRequests(WebQuoteTransport.api, BuildConfig.WEB_REQUEST_SCRIPT_URL, settings.deviceToken, ::readRequest)
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
    suspend fun close(id: String) {
        check(paired)
        val response=WebQuoteTransport.api.request(BuildConfig.WEB_REQUEST_SCRIPT_URL,mapOf("action" to "close_request","deviceToken" to settings.deviceToken,"requestId" to id))
        WebQuoteValidator.checkResponse(response);check(response.closed==true)
        repository.closeLocalRequest(id)
    }
}

internal fun readLegacyWebRequest(outer: com.example.namufarm.data.remote.WebQuoteRemote, keys: com.example.namufarm.privacy.SellerKeys): com.example.namufarm.data.remote.WebQuoteRemote {
    val envelope = outer.encryptedRequest ?: return outer
    if (!keys.initialized || keys.keyId() != envelope.keyId) throw com.example.namufarm.data.remote.LegacyRequestKeyUnavailable()
    return com.google.gson.Gson().fromJson(keys.open(envelope, "quote", checkNotNull(outer.requestId)), com.example.namufarm.data.remote.WebQuoteRemote::class.java)
}

