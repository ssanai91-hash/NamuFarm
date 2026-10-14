package com.example.namufarm.webrequests

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebRequestSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val key = SecretKeySpec(ByteArray(32) { 4 }, "AES")
    private val oldUrl = "https://script.google.com/macros/s/AKfycbw8uqBWveIhqmXielALjSLnY1yDenKAwl070FT6Lsxsj7Xiod1C5JSBDZGfQtlrl6A-CQ/exec"
    private val newUrl = "https://script.google.com/macros/s/recovered/exec"
    @Before fun reset() { app.getSharedPreferences("web_request_connection", 0).edit().clear().commit() }
    private fun settings(url: String) = WebRequestSettings(app, url) { key }

    @Test fun importedConnectionFilePersistsEndpointWithoutSavingSharedKey() {
        val value = JSONObject().put("format", "namufarm-connection").put("version", 1)
            .put("url", newUrl).put("connectionKey", "a".repeat(64))
        val profile = AppConnectionFile.parse(value.toString().toByteArray())
        val original = settings(oldUrl); val token = original.prepareToken()
        original.markConnected(profile.url)
        val reopened = settings(oldUrl)
        assertTrue(reopened.paired); assertEquals(newUrl, reopened.url); assertEquals(token, reopened.deviceToken)
        value.put("url", "https://example.com/exec")
        assertTrue(runCatching { AppConnectionFile.parse(value.toString().toByteArray()) }.isFailure)
        assertTrue(runCatching { AppConnectionFile.parse(ByteArray(8193)) }.isFailure)
    }

    @Test fun ordinaryUpdateRetainsPairingAndTokens() {
        val original = settings(newUrl)
        val token = original.prepareToken()
        original.saveFcmToken("push-token"); original.markPaired()
        val reopened = settings(newUrl)
        assertTrue(reopened.paired)
        assertEquals(token, reopened.deviceToken)
        assertEquals("push-token", reopened.fcmToken)
    }

    @Test fun sharedKeyPersistsEncryptedAndReplacesOnlyRequestAuthentication() {
        val original = settings(newUrl)
        val token = original.prepareToken()
        original.markHiddenOrder("existing-order")
        original.markConnected(newUrl, "b".repeat(64))
        val reopened = settings(oldUrl)
        assertTrue(reopened.paired)
        assertEquals(token, reopened.deviceToken)
        assertEquals(setOf("existing-order"), reopened.hiddenOrderNumbers)
        val body = mapOf<String, Any>("action" to "snapshot_requests", "deviceToken" to token, "afterSequence" to 27L)
        val authorized = reopened.authorizeRequest(newUrl, body)
        assertEquals("b".repeat(64), authorized["connectionKey"])
        assertFalse(authorized.containsKey("deviceToken"))
        assertEquals(27L, authorized["afterSequence"])
        assertEquals(token, body["deviceToken"])
        val payload = app.getSharedPreferences("web_request_connection", 0).getString("payload", "")!!
        assertFalse(String(Base64.decode(payload, Base64.NO_WRAP), Charsets.UTF_8).contains("b".repeat(64)))
        assertTrue(runCatching { reopened.authorizeRequest(oldUrl, body) }.isFailure)
    }

    @Test fun legacyReconnectionClearsSharedKeyAndPreservesDeviceToken() {
        val original = settings(newUrl)
        val token = original.prepareToken()
        original.markConnected(newUrl, "b".repeat(64))
        original.markConnected(newUrl) // Old server returned paired=true without authMode.
        assertEquals("", original.connectionKey)
        val body = mapOf<String, Any>("action" to "list_requests", "deviceToken" to token)
        assertEquals(body, original.authorizeRequest(newUrl, body))
    }

    @Test fun changedBackendRequiresPairingOnceWithoutReplacingDeviceIdentity() {
        val old = settings(oldUrl); val token = old.prepareToken(); old.markPaired()
        val replacement = settings(newUrl)
        assertFalse(replacement.paired)
        assertEquals(token, replacement.prepareToken())
        replacement.markPaired()
        assertTrue(settings(newUrl).paired)
        assertEquals(token, settings(newUrl).deviceToken)
    }

    @Test fun legacyCredentialWithoutEndpointBelongsToRetiredServer() {
        val token = "a".repeat(64)
        val value = JSONObject().put("paired", true).put("token", token).put("fcm", "push-token")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        app.getSharedPreferences("web_request_connection", 0).edit()
            .putString("payload", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        assertTrue(settings(oldUrl).paired)
        assertFalse(settings(newUrl).paired)
        assertEquals(token, settings(newUrl).deviceToken)
    }
}


