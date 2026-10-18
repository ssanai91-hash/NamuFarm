package com.example.namufarm.privacy

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

data class PrivacyEnvelope(
    val version: Int = 1,
    val algorithm: String = "RSA-OAEP-256+A256GCM",
    val keyId: String,
    val wrappedKey: String,
    val iv: String,
    val ciphertext: String
)

/** The server only receives a public key. Only the seller's app can open stored enquiries/orders. */
object HybridCrypto {
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decode(value: String): ByteArray {
        require(value.length <= 60000 && Regex("[A-Za-z0-9+/]*={0,2}").matches(value))
        return Base64.decode(value, Base64.NO_WRAP).also { require(encode(it) == value) }
    }
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    fun publicKey(encoded: String): PublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decode(encoded)))
        .also { require((it as java.security.interfaces.RSAPublicKey).modulus.bitLength() == 3072) }
    fun keyId(key: PublicKey): String = digest(encode(key.encoded))
    private fun aad(kind: String, reference: String): ByteArray {
        require(kind in listOf("quote", "order") && reference.length in 1..120 && reference.none { it.code < 32 })
        return "namufarm:v1:$kind:$reference".toByteArray(Charsets.UTF_8)
    }
    private fun rsa(mode: Int, key: java.security.Key): Cipher = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
        init(mode, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
    }
    fun seal(plain: String, public: PublicKey, kind: String, reference: String): PrivacyEnvelope {
        val data = plain.toByteArray(Charsets.UTF_8)
        require(data.size in 1..30000)
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(kind, reference))
            return PrivacyEnvelope(keyId = keyId(public), wrappedKey = encode(rsa(Cipher.ENCRYPT_MODE, public).doFinal(secret)),
                iv = encode(nonce), ciphertext = encode(cipher.doFinal(data)))
        } finally { secret.fill(0) }
    }
    fun open(envelope: PrivacyEnvelope, private: PrivateKey, public: PublicKey, kind: String, reference: String): String {
        require(envelope.version == 1 && envelope.algorithm == "RSA-OAEP-256+A256GCM" && envelope.keyId == keyId(public)) {
            "이 고객정보의 보안 백업키를 가져와주세요."
        }
        val nonce = decode(envelope.iv); val wrapped = decode(envelope.wrappedKey); val data = decode(envelope.ciphertext)
        require(nonce.size == 12 && wrapped.size == 384 && data.size in 17..30016)
        val secret = rsa(Cipher.DECRYPT_MODE, private).doFinal(wrapped)
        try {
            require(secret.size == 32)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(kind, reference))
            return cipher.doFinal(data).toString(Charsets.UTF_8)
        } finally { secret.fill(0) }
    }
}

