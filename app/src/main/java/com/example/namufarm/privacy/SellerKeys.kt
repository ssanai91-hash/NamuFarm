package com.example.namufarm.privacy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

interface LocalPrivacyCipher {
    fun protect(value: String): String
    fun reveal(value: String): String
}

/** Software RSA private key is wrapped by a non-exportable per-device Keystore AES key.
 * Recovery export is password-encrypted; no private key is uploaded to Google or embedded in the APK. */
class SellerKeys(context: Context, private val deviceKeyProvider: (() -> SecretKey)? = null) : LocalPrivacyCipher {
    private val prefs = context.applicationContext.getSharedPreferences("seller_privacy_keys", Context.MODE_PRIVATE)
    private val gson = Gson()
    private fun deviceKey(): SecretKey = synchronized(KEY_LOCK) {
        deviceKeyProvider?.let { return@synchronized it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun protect(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, deviceKey()) }
        cipher.updateAAD("namufarm-local-v1".toByteArray())
        return PREFIX + HybridCrypto.encode(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
    override fun reveal(value: String): String {
        if (!value.startsWith(PREFIX)) return value // Legacy data is rewritten by the privacy migration worker.
        val bytes = HybridCrypto.decode(value.removePrefix(PREFIX)); require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("namufarm-local-v1".toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    val initialized: Boolean get() = prefs.contains("private")
    val backedUp: Boolean get() = prefs.getBoolean("backed_up", false)
    val registered: Boolean get() = prefs.getBoolean("registered", false)
    fun markRegistered() { check(prefs.edit().putBoolean("registered", true).commit()) }
    fun ensureCreated() = synchronized(KEY_LOCK) {
        if (initialized) return@synchronized
        val pair = HybridCrypto.generate()
        check(prefs.edit().putString("private", protect(HybridCrypto.encode(pair.private.encoded)))
            .putString("public", HybridCrypto.encode(pair.public.encoded)).putBoolean("backed_up", false).commit())
    }
    fun pair(): KeyPair {
        check(initialized) { "고객정보 보안 설정을 먼저 완료해주세요." }
        val privateBytes = HybridCrypto.decode(reveal(checkNotNull(prefs.getString("private", null))))
        try { return KeyPair(HybridCrypto.publicKey(checkNotNull(prefs.getString("public", null))),
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateBytes))) }
        finally { privateBytes.fill(0) }
    }
    fun publicKey(): String = HybridCrypto.encode(pair().public.encoded)
    fun keyId(): String = HybridCrypto.keyId(pair().public)
    fun seal(plain: String, kind: String, reference: String) = HybridCrypto.seal(plain, pair().public, kind, reference)
    fun open(envelope: PrivacyEnvelope, kind: String, reference: String): String = pair().let {
        HybridCrypto.open(envelope, it.private, it.public, kind, reference)
    }
    private data class Backup(val version: Int = 1, val kdf: String = "PBKDF2-HMAC-SHA1", val iterations: Int = 1300000,
        val salt: String, val iv: String, val ciphertext: String)
    private data class Material(val privateKey: String, val publicKey: String)
    private fun passwordKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        require(password.size >= 12) { "보안 백업 비밀번호는 12자 이상 입력해주세요." }
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    fun exportBackup(password: CharArray): String {
        ensureCreated()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, passwordKey(password, salt, 1300000)) }
        cipher.updateAAD("namufarm-seller-backup-v1".toByteArray())
        val keys = pair()
        val plain = gson.toJson(Material(HybridCrypto.encode(keys.private.encoded), HybridCrypto.encode(keys.public.encoded))).toByteArray()
        return try { gson.toJson(Backup(salt = HybridCrypto.encode(salt), iv = HybridCrypto.encode(cipher.iv), ciphertext = HybridCrypto.encode(cipher.doFinal(plain)))) }
        finally { plain.fill(0) }
    }
    fun markBackupSaved() { check(prefs.edit().putBoolean("backed_up", true).commit()) }
    fun importBackup(json: String, password: CharArray, requiredKeyId: String? = null) {
        require(json.length < 20000)
        val backup = gson.fromJson(json, Backup::class.java)
        require(backup.version == 1 && backup.kdf == "PBKDF2-HMAC-SHA1" && backup.iterations == 1300000)
        val salt = HybridCrypto.decode(backup.salt); val iv = HybridCrypto.decode(backup.iv)
        require(salt.size == 16 && iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, passwordKey(password, salt, backup.iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD("namufarm-seller-backup-v1".toByteArray())
        val plain = cipher.doFinal(HybridCrypto.decode(backup.ciphertext))
        try {
            val material = gson.fromJson(plain.toString(Charsets.UTF_8), Material::class.java)
            val public = HybridCrypto.publicKey(material.publicKey)
            val private = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(HybridCrypto.decode(material.privateKey)))
            val id = HybridCrypto.keyId(public)
            require(requiredKeyId == null || requiredKeyId == id) { "현재 고객정보와 다른 보안 백업입니다." }
            require(!initialized || keyId() == id || !registered) { "다른 보안키로 기존 고객정보를 덮어쓸 수 없습니다." }
            val test = HybridCrypto.seal("backup-check", public, "quote", "check")
            require(HybridCrypto.open(test, private, public, "quote", "check") == "backup-check")
            check(prefs.edit().putString("private", protect(material.privateKey)).putString("public", material.publicKey)
                .putBoolean("backed_up", true).commit())
        } finally { plain.fill(0) }
    }
    companion object { private val KEY_LOCK=Any(); private const val ALIAS = "namufarm-privacy-wrap-v1"; private const val PREFIX = "NFENC1:" }
}
