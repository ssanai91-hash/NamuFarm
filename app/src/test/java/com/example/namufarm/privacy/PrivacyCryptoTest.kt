package com.example.namufarm.privacy

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PrivacyCryptoTest {
    companion object { private val pair=HybridCrypto.generate() }
    private data class Fixture(val publicKey:String,val privateKey:String,val reference:String,val plaintext:String,val encrypted:PrivacyEnvelope)
    @Test fun phoneAndKoreanNameRoundTripWithoutPlaintextStorage() {
        val plain="{\"customerName\":\"김나무\",\"customerPhone\":\"01012345678\"}"
        val envelope=HybridCrypto.seal(plain,pair.public,"quote","request")
        assertEquals(plain,HybridCrypto.open(envelope,pair.private,pair.public,"quote","request"))
        assertFalse(Gson().toJson(envelope).contains("김나무"));assertEquals(384,HybridCrypto.decode(envelope.wrappedKey).size)
    }
    @Test fun movingCiphertextToAnotherRequestOrOrderFailsAuthentication() {
        val e=HybridCrypto.seal("고객",pair.public,"quote","one")
        assertTrue(runCatching {HybridCrypto.open(e,pair.private,pair.public,"quote","two")}.isFailure)
        assertTrue(runCatching {HybridCrypto.open(e,pair.private,pair.public,"order","one")}.isFailure)
    }
    @Test fun tamperingAndWrongKeysCannotReturnCustomerData() {
        val e=HybridCrypto.seal("고객",pair.public,"quote","one")
        val bytes=HybridCrypto.decode(e.ciphertext);bytes[0]=(bytes[0].toInt() xor 1).toByte()
        assertTrue(runCatching {HybridCrypto.open(e.copy(ciphertext=HybridCrypto.encode(bytes)),pair.private,pair.public,"quote","one")}.isFailure)
        val other=HybridCrypto.generate()
        assertTrue(runCatching {HybridCrypto.open(e,other.private,other.public,"quote","one")}.isFailure)
    }
    @Test fun websiteWebCryptoFixtureDecryptsInActualAndroidProtocol() {
        val f=Gson().fromJson(javaClass.classLoader!!.getResourceAsStream("website-crypto-test.json")!!.bufferedReader().use {it.readText()},Fixture::class.java)
        val public=HybridCrypto.publicKey(f.publicKey)
        val private=KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(HybridCrypto.decode(f.privateKey)))
        assertEquals(f.plaintext,HybridCrypto.open(f.encrypted,private,public,"quote",f.reference))
    }
    @Test fun recoveryExportIsPasswordEncryptedAndRestoresOnAnotherDeviceKey() {
        val context=RuntimeEnvironment.getApplication();val prefs=context.getSharedPreferences("seller_privacy_keys",0);prefs.edit().clear().commit()
        val keys=SellerKeys(context) {SecretKeySpec(ByteArray(32){1},"AES")}
        val backup=keys.exportBackup("test-password-12345".toCharArray());val id=keys.keyId();val e=keys.seal("고객","quote","one")
        assertFalse(backup.contains(keys.publicKey()));assertFalse(keys.backedUp)
        keys.markBackupSaved();assertTrue(keys.backedUp)
        prefs.edit().clear().commit()
        val replacement=SellerKeys(context) {SecretKeySpec(ByteArray(32){2},"AES")}
        assertTrue(runCatching {replacement.importBackup(backup,"wrong-password-1234".toCharArray(),id)}.isFailure)
        assertFalse(replacement.initialized)
        replacement.importBackup(backup,"test-password-12345".toCharArray(),id)
        assertEquals("고객",replacement.open(e,"quote","one"));assertTrue(replacement.backedUp)
        prefs.edit().clear().commit()
    }
    @Test fun localPrefixIsEncryptedAsCustomerTextRatherThanMistakenForExistingCiphertext() {
        val keys=SellerKeys(RuntimeEnvironment.getApplication()) {SecretKeySpec(ByteArray(32){3},"AES")}
        val text="NFENC1:고객 입력";assertEquals(text,keys.reveal(keys.protect(text)))
    }
}
