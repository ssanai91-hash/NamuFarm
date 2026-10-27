package com.example.namufarm.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface OperationsMasterApi {
    @POST suspend fun request(@Url url: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>): JsonObject
}

object OperationsMasterTransport {
    const val ENDPOINT = "https://script.google.com/macros/s/AKfycbzY7JKzXYmS9APLNFDt15k8dv09zb4EXKFHOOv_T1vpFmkr6TLwbqGznGLgpd2cIVgD/exec"
    val api: OperationsMasterApi by lazy {
        Retrofit.Builder().baseUrl("https://script.google.com/")
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS)
                .callTimeout(100, TimeUnit.SECONDS)
                .addInterceptor(GoogleContentResponseInterceptor()).build())
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(OperationsMasterApi::class.java)
    }
}

/** Device token survives retries; it is never stored or backed up in plaintext. */
class MasterDeviceCredentials(context: Context) : com.example.namufarm.data.InventoryLinkSettings {
    private val prefs = context.applicationContext.getSharedPreferences("operations_master_device", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("namufarm_master_device_v1", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("namufarm_master_device_v1",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }
    @Synchronized fun token(): String {
        val old = prefs.getString("token", null)
        if (old != null) {
            val value = runCatching {
                val bytes = Base64.decode(old, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            }.getOrNull()
            if (value != null && Regex("[a-f0-9]{64}").matches(value)) return value
        }
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit().putString("token", Base64.encodeToString(
            cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .putBoolean("paired", false).commit()) { "기기 연결 정보를 저장하지 못했습니다." }
        return token
    }
    override var inventoryLinked: Boolean
        get() = prefs.getBoolean("inventoryLinked", false)
        set(value) { check(prefs.edit().putBoolean("inventoryLinked", value).commit()) { "재고 연결 상태 저장 실패" } }
    var downloaded: Boolean
        get() = prefs.getBoolean("downloaded", false)
        set(value) { check(prefs.edit().putBoolean("downloaded", value).commit()) }
    var paired: Boolean
        get() = prefs.getBoolean("paired", false)
        set(value) { check(prefs.edit().putBoolean("paired", value).commit()) }
}

class MasterServerException(val code: String) : IllegalStateException(when (code) {
    "CONFLICT" -> "운영 시트와 변경 내용이 충돌합니다. 로컬 수정 내용은 전송 대기로 보관했습니다."
    "UNAUTHORIZED", "DEVICE_LIMIT" -> "운영 서버의 기기 연결을 확인해주세요."
    "NOT_READY", "INVALID_ACTION", "UPGRADE_REQUIRED" -> "운영 서버의 농원·묘목 관리 설정을 확인해주세요."
    "BUSY" -> "서버가 사용 중입니다. 잠시 후 다시 동기화해주세요."
    else -> "운영 시트 반영을 확인하지 못했습니다. 다시 동기화해주세요."
})

interface OperationsMasterGateway {
    var downloaded: Boolean
    suspend fun request(action: String, fields: Map<String, Any> = emptyMap()): JsonObject
}

class OperationsMasterClient(
    private val credentials: MasterDeviceCredentials,
    private val api: OperationsMasterApi = OperationsMasterTransport.api
) : OperationsMasterGateway {
    override var downloaded: Boolean
        get() = credentials.downloaded
        set(value) { credentials.downloaded = value }
    private fun checked(response: JsonObject): JsonObject {
        if (response.get("ok")?.asBoolean != true)
            throw MasterServerException(response.get("code")?.asString ?: "RESPONSE")
        return response
    }
    override suspend fun request(action: String, fields: Map<String, Any>): JsonObject {
        val token = credentials.token()
        if (!credentials.paired) {
            val response = checked(api.request(OperationsMasterTransport.ENDPOINT,
                mapOf("action" to "connect_app", "authMode" to "auto-v1", "deviceToken" to token,
                    "label" to "나무팜 초기버전")))
            check(response.get("paired")?.asBoolean == true && response.get("authMode")?.asString == "auto-v1")
            credentials.paired = true
        }
        val response = api.request(OperationsMasterTransport.ENDPOINT,
            fields + mapOf("action" to action, "deviceToken" to token))
        if (response.get("code")?.asString == "UNAUTHORIZED") credentials.paired = false
        return checked(response)
    }
}
