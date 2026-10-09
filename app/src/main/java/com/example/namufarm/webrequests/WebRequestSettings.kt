package com.example.namufarm.webrequests

import android.content.Context
import com.example.namufarm.BuildConfig
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-device credentials, encrypted with a non-exportable Android Keystore key. */
class WebRequestSettings(context: Context, private val deviceKeyProvider: (() -> SecretKey)? = null) {
    private var endpoint: String = BuildConfig.WEB_REQUEST_SCRIPT_URL
    internal constructor(context: Context, endpoint: String, deviceKeyProvider: () -> SecretKey) : this(context, deviceKeyProvider) {
        this.endpoint = endpoint
    }
    private val prefs = context.applicationContext.getSharedPreferences("web_request_connection", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        deviceKeyProvider?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): JSONObject {
        val encrypted = prefs.getString("payload", null) ?: return JSONObject()
        return runCatching {
            val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)), Charsets.UTF_8))
        }.getOrElse { JSONObject() } // Missing/restored key requires pairing again, never a plaintext fallback.
    }
    private fun update(block: (JSONObject) -> Unit) = synchronized(LOCK) {
        val value = read(); block(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("payload", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "기기 연결 정보를 저장하지 못했습니다." }
    }
    val paired: Boolean get() = synchronized(LOCK) {
        val value = read()
        value.optBoolean("paired", false) && value.optString("endpoint", RETIRED_ENDPOINT) == url
    }
    val url: String get() = synchronized(LOCK) { read().optString("profileEndpoint", endpoint) }
    val configured: Boolean get() = AppConnectionFile.validUrl(url)
    val deviceToken: String get() = synchronized(LOCK) { read().optString("token", "") }
    val fcmToken: String get() = synchronized(LOCK) { read().optString("fcm", "") }
    fun prepareToken(): String = synchronized(LOCK) {
        deviceToken.ifBlank {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val value = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
            update { it.put("token", value) }; value
        }
    }
    fun markPaired() = update { it.put("paired", true); it.put("endpoint", url) }
    fun markConnected(url: String) {
        require(AppConnectionFile.validUrl(url))
        update { it.put("profileEndpoint", url); it.put("endpoint", url); it.put("paired", true) }
    }
    fun saveFcmToken(value: String) = update { it.put("fcm", value) }
    companion object {
        private val LOCK = Any()
        private const val KEY_ALIAS = "namufarm_web_request_key_v1"
        private const val RETIRED_ENDPOINT = "https://script.google.com/macros/s/AKfycbw8uqBWveIhqmXielALjSLnY1yDenKAwl070FT6Lsxsj7Xiod1C5JSBDZGfQtlrl6A-CQ/exec"
    }
}


