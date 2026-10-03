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
        PushRegistrationWorker.enqueue(app)
        return repository.syncWebRequests(WebQuoteTransport.api, BuildConfig.WEB_REQUEST_SCRIPT_URL, settings.deviceToken)
    }
}
