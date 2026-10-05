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
    val securityReady get() = keys.backedUp && keys.registered
    val backedUp get() = keys.backedUp
    val configured get() = WebQuoteTransport.configured
    val paired get() = settings.paired
    val pushConfigured get() = WebRequestPush.configured
    fun localRequests() = repository.getWebRequests()
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
        check(securityReady) { "고객정보 보안 백업과 연결을 먼저 완료해주세요." }
        PushRegistrationWorker.enqueue(app)
        return repository.syncWebRequests(WebQuoteTransport.api, BuildConfig.WEB_REQUEST_SCRIPT_URL, settings.deviceToken) { outer ->
            val envelope=checkNotNull(outer.encryptedRequest) { "암호화되지 않은 고객 요청은 가져오지 않습니다." }
            com.google.gson.Gson().fromJson(keys.open(envelope,"quote",checkNotNull(outer.requestId)),com.example.namufarm.data.remote.WebQuoteRemote::class.java)
        }
    }
    suspend fun configureSecurity() {
        check(paired && keys.backedUp) { "기기 연결 후 보안 백업을 먼저 저장해주세요." }
        val response=WebQuoteTransport.api.request(BuildConfig.WEB_REQUEST_SCRIPT_URL,mapOf("action" to "configure_encryption","deviceToken" to settings.deviceToken,"publicKey" to keys.publicKey()))
        WebQuoteValidator.checkResponse(response)
        check(response.keyRegistered==true && response.keyId==keys.keyId()) { "서버의 보안키를 확인하지 못했습니다." }
        keys.markRegistered()
        check(response.encryptionReady==true) { "이전 고객 요청을 변환 중입니다. 보안 연결을 다시 눌러주세요." }
    }
    fun saveBackup(uri: android.net.Uri, password: CharArray) {
        try {
            val json=keys.exportBackup(password)
            checkNotNull(app.contentResolver.openOutputStream(uri,"wt")).use { it.write(json.toByteArray(Charsets.UTF_8)); it.flush() }
            keys.markBackupSaved()
        } finally { password.fill('\u0000') }
    }
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
        check(securityReady && paired)
        val response=WebQuoteTransport.api.request(BuildConfig.WEB_REQUEST_SCRIPT_URL,mapOf("action" to "close_request","deviceToken" to settings.deviceToken,"requestId" to id))
        WebQuoteValidator.checkResponse(response);check(response.closed==true)
        repository.closeLocalRequest(id)
    }
}
