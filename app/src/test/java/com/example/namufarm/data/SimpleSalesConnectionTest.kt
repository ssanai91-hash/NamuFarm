package com.example.namufarm.data

import com.example.namufarm.data.remote.*
import com.example.namufarm.privacy.SellerKeys
import com.example.namufarm.sales.*
import com.example.namufarm.webrequests.WebRequestSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SimpleSalesConnectionTest {
    private lateinit var keys: SellerKeys
    private lateinit var settings: WebRequestSettings
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("seller_privacy_keys",0).edit().clear().commit()
        app.getSharedPreferences("web_request_connection",0).edit().clear().commit()
        keys = SellerKeys(app) { SecretKeySpec(ByteArray(32){3},"AES") }
        settings = WebRequestSettings(app) { SecretKeySpec(ByteArray(32){4},"AES") }
        settings.prepareToken(); settings.markPaired()
    }
    private fun order() = OrderEntity(orderNumber="ORDER_SIMPLE",orderDate="2026-10-08 14:00",status="구매확정",
        customerPhone="01012345678",customerName="나무 고객",totalQty=2,totalPrice=2000,
        itemSummary="소나무|상묘|1년생|1.0m|실생|2|1000|수기견적|0",revision=1,purchasedAt=1000,updatedAt=1000)

    @Test fun pairedPhoneSavesAndReloadsWithoutCreatingCustomerKeys() = runBlocking {
        var stored: Map<String, Any>? = null
        val api = object : WebQuoteApi {
            override suspend fun request(url:String,body:Map<String,Any>):WebQuoteEnvelope {
                if(body["action"]=="save_sale") {
                    stored=body
                    return WebQuoteEnvelope(ok=true,saved=true,orderNumber="ORDER_SIMPLE",revision=1,fingerprint=body["fingerprint"] as String)
                }
                val data=checkNotNull(stored)
                return WebQuoteEnvelope(ok=true,hasMore=false,sales=listOf(SaleRemote(1,data["eventId"] as String,
                    data["metadata"] as SaleMetadata,null,data["fingerprint"] as String,personalData=data["personalData"] as SalePrivateData)))
            }
        }
        val gateway=DeviceSalesGateway(settings,keys,api)
        val payload=gateway.prepare(order(),"11111111-1111-4111-8111-111111111111")
        assertNull(payload.encryptedPersonal)
        assertTrue(gateway.save(payload))
        assertEquals(5,stored!!["protocolVersion"])
        assertFalse(stored!!.containsKey("encryptedPersonal"))
        assertEquals(listOf(order()),gateway.orders())
        assertFalse(keys.initialized);assertFalse(keys.backedUp);assertFalse(keys.registered)
    }

    @Test fun newRecordDoesNotNeedLegacyKeyAndTamperingFails() {
        val meta=SalePolicy.metadata(order()); val personal=SalePrivateData(order())
        val row=SaleRemote(1,"event",meta,null,PrivacyWire.customerFingerprint(meta,personal),personalData=personal)
        assertEquals(personal,SaleWire.decode(row,keys))
        assertTrue(runCatching {SaleWire.decode(row.copy(personalData=personal.copy(order=order().copy(customerName="변조"))),keys)}.isFailure)
        assertFalse(keys.initialized)
    }

    @Test fun plainRequestUsesSameValidationAndCursorRules() {
        val row=WebQuoteRemote(requestId="11111111-1111-4111-8111-111111111111",sequence=1,
            createdAt="2026-10-08T00:00:00.000Z",customerName="나무 고객",customerPhone="01012345678",
            region="충북 옥천",items=listOf(WebQuoteItemRemote("소나무","",2)),message="")
        val page=WebQuoteValidator.checkedPage(WebQuoteEnvelope(ok=true,hasMore=false,requests=listOf(row)),0)
        assertEquals(1L,page.cursor);assertEquals("나무 고객",page.rows.single().customerName)
        assertTrue(runCatching {WebQuoteValidator.checkedPage(WebQuoteEnvelope(ok=true,hasMore=false,requests=listOf(row.copy(customerPhone="invalid"))),0)}.isFailure)
    }
}
