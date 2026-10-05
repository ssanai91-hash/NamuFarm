package com.example.namufarm.data

import androidx.room.Room
import com.example.namufarm.data.local.LocalDataSource
import com.example.namufarm.data.remote.*
import com.example.namufarm.data.remote.SheetInventoryDto
import com.example.namufarm.data.repository.NamuFarmRepository
import com.example.namufarm.privacy.*
import com.example.namufarm.sales.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class SecureCommerceTest {
    private lateinit var db:AppDatabase
    private lateinit var local:LocalDataSource
    private lateinit var api:FakeSheetApi
    private lateinit var gateway:FakeSales
    private lateinit var repo:NamuFarmRepository
    private val id="11111111-1111-4111-8111-111111111111"
    private val summary="소나무|상묘|1년생|1.0m|실생|3|1000|농원|500"
    private val stock=FarmInventoryEntity(1,1,"상묘","1년생","1.0m","실생",10,500,1000)
    private class FakeSales:SalesGateway {
        val pair=HybridCrypto.generate();val receipts=mutableMapOf<String,PendingSaleSyncPayload>();val stored=mutableMapOf<String,OrderEntity>();var loseResponse=false
        override fun prepare(order:OrderEntity,eventId:String,quoteDocumentJson:String?):PendingSaleSyncPayload {
            val meta=SalePolicy.metadata(order);val encrypted=HybridCrypto.seal(Gson().toJson(SalePrivateData(order.copy(orderId=null),quoteDocumentJson)),pair.public,"order",eventId)
            return PendingSaleSyncPayload(eventId,meta,encrypted,PrivacyWire.fingerprint(meta,encrypted),order.sourceRequestId)
        }
        override suspend fun save(payload:PendingSaleSyncPayload):Boolean {
            receipts[payload.eventId]?.let {check(it==payload);return true}
            val order=Gson().fromJson(HybridCrypto.open(payload.encryptedPersonal,pair.private,pair.public,"order",payload.eventId),SalePrivateData::class.java).order
            receipts[payload.eventId]=payload;stored[order.orderNumber]=order
            if(loseResponse){loseResponse=false;return false};return true
        }
        override suspend fun orders()=stored.values.toList()
        override suspend fun report(granularity:String,from:String,to:String)=SalesReport(granularity,from,to,emptyList())
    }
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication()
        db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).allowMainThreadQueries().build()
        local=LocalDataSource(db,db.managementDao(),SellerKeys(context){SecretKeySpec(ByteArray(32){7},"AES")})
        api=FakeSheetApi().apply {online=true;inventory=listOf(SheetInventoryDto("농원","소나무","상묘","1년생","1.0m","실생",10,500,1000))};gateway=FakeSales()
        val juso=object:JusoApi {override suspend fun searchAddress(apiKey:String,keyword:String,resultType:String,count:Int):JusoResponse=error("unused")}
        repo=NamuFarmRepository(local,SyncManager(local,api,gateway),juso)
        local.upsertFarm(FarmEntity(1,"농원"));local.upsertSpecies(SpeciesEntity(1,"소나무"));local.upsertInventory(stock)
    }
    @After fun close(){db.close()}
    private suspend fun quote():QuoteEntity {
        val q=QuoteEntity(quoteNumber="Q",saveDate="2026-10-05 10:00",customerPhone="01012345678",totalQty=3,totalPrice=3000,itemSummary=summary)
        repo.insertQuoteWithContact(q,com.example.namufarm.quotation.QuoteRecipient(customerName="김나무",customerEmail="buyer@example.com",sourceRequestId=id))
        return local.getQuoteByNumber("Q")!!
    }
    private fun order(q:QuoteEntity)=OrderEntity(orderNumber="ORDER_TEST",orderDate="2026-10-05 10:00",customerPhone=q.customerPhone,totalQty=q.totalQty,totalPrice=q.totalPrice,itemSummary=q.itemSummary)
    @Test fun actualBuyerFieldsAndOutboxAreEncryptedInRoomAndLegacyOrderApiIsNeverCalled()=runBlocking {
        val q=quote();gateway.loseResponse=true
        val result=repo.confirmOrder(q,order(q));assertFalse(result.cloudSynced)
        val raw=db.managementDao().getOrderByNumber("ORDER_TEST")!!
        assertTrue(raw.customerPhone.startsWith("NFENC1:"));assertFalse(raw.customerName.contains("김나무"));assertFalse(db.managementDao().getAllPendingSync().first().payloadJson.contains("01012345678"))
        assertEquals(OrderStatus.PURCHASED,repo.getOrder("ORDER_TEST")!!.status)
        assertEquals("buyer@example.com",repo.getOrder("ORDER_TEST")!!.customerEmail);assertEquals(7,local.getAllInventoryEntities().single().quantity)
        assertTrue(api.orderUploads.isEmpty());assertFalse(api.events.contains("get_orders"))
        val before=local.getAllPendingSync().first().payloadJson
        repo.syncAll();assertTrue(local.getAllPendingSync().isEmpty());assertEquals(1,gateway.receipts.size)
        assertEquals(before,Gson().toJson(gateway.receipts.values.single()));assertEquals(7,local.getAllInventoryEntities().single().quantity)
    }
    @Test fun forwardStateChecksAndCostReviewPersistToTheEncryptedSaleQueue()=runBlocking {
        val q=quote();repo.confirmOrder(q,order(q))
        var o=repo.getOrder("ORDER_TEST")!!
        assertTrue(runCatching {repo.updateSaleStatus(o.orderNumber,o.revision,OrderStatus.DELIVERED)}.isFailure)
        repo.updateSaleStatus(o.orderNumber,o.revision,OrderStatus.CONFIRMED);o=repo.getOrder(o.orderNumber)!!
        repo.updateSaleStatus(o.orderNumber,o.revision,OrderStatus.DELIVERED);o=repo.getOrder(o.orderNumber)!!
        assertTrue(runCatching {repo.updateSaleStatus(o.orderNumber,o.revision,OrderStatus.COMPLETED)}.isFailure)
        repo.updateSaleCosts(o.orderNumber,o.revision,1500,200,"resale",3000,true);o=repo.getOrder(o.orderNumber)!!
        repo.updateSaleStatus(o.orderNumber,o.revision,OrderStatus.COMPLETED)
        assertEquals(OrderStatus.COMPLETED,gateway.stored[o.orderNumber]!!.status)
        assertEquals(1300L,SalePolicy.profit(SalePolicy.metadata(repo.getOrder(o.orderNumber)!!)))
        assertFalse(repo.cancelOrder(o.orderNumber).success)
    }
    @Test fun failureWritingEncryptedPendingRollsBackPurchaseStockAndQuote()=runBlocking {
        val q=quote();db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_pending BEFORE INSERT ON pending_sync BEGIN SELECT RAISE(ABORT,'injected'); END")
        assertTrue(runCatching {repo.confirmOrder(q,order(q))}.isFailure)
        assertEquals(10,local.getAllInventoryEntities().single().quantity);assertNotNull(local.getQuoteByNumber("Q"));assertNull(repo.getOrder("ORDER_TEST"));assertTrue(gateway.receipts.isEmpty())
    }
    @Test fun canceledPurchaseRestoresStockExactlyOnceWithoutSendingPlaintext()=runBlocking {
        val q=quote();repo.confirmOrder(q,order(q));assertTrue(repo.cancelOrder("ORDER_TEST").success);assertTrue(repo.cancelOrder("ORDER_TEST").alreadyCanceled)
        assertEquals(10,local.getAllInventoryEntities().single().quantity);assertEquals(OrderStatus.CANCELED,gateway.stored["ORDER_TEST"]!!.status);assertTrue(api.orderUploads.isEmpty())
    }
    @Test fun legacyPlaintextCopiesAreRewrittenWithoutLosingTheOriginalAndReadState()=runBlocking {
        val dao=db.managementDao();dao.insertWebRequest(WebQuoteRequestEntity(id,7,"2026-10-04T01:23:45.000Z","김나무","01012345678","옥천","[]","문의",42))
        local.protectLegacyCustomerCopies();assertTrue(dao.getWebRequests().single().customerPhone.startsWith("NFENC1:"));assertEquals("김나무",local.getWebRequests().single().customerName);assertEquals(42L,local.getWebRequests().single().readAt)
        local.protectLegacyCustomerCopies();assertEquals("김나무",local.getWebRequests().single().customerName)
    }
    @Test fun expiredEncryptedPageAdvancesIndependentCursorWithoutResurrectingDeletedRequests()=runBlocking {
        val transport=object:WebQuoteApi {override suspend fun request(url:String,body:Map<String,Any>)=WebQuoteEnvelope(ok=true,hasMore=false,requests=listOf(WebQuoteRemote(requestId=id,sequence=7,createdAt="2026-09-01T01:23:45.000Z",expired=true)))}
        assertEquals(0,repo.syncWebRequests(transport,"test","test") {error("expired rows must not be decrypted")});assertEquals(7L,local.webRequestCursor());assertTrue(local.getWebRequests().isEmpty())
    }
    @Test fun expiredConsultationDeletesItsUnpurchasedQuoteButKeepsActualPurchaseRecord()=runBlocking {
        val time=PrivacyRetention.quoteTime("2026-10-04T01:23:45.000Z")
        local.transaction {insertWebRequests(listOf(WebQuoteRequestEntity(id,7,"2026-10-04T01:23:45.000Z","김나무","01012345678","옥천","[]","문의")))}
        quote();local.cleanupPrivacy(time+PrivacyRetention.QUOTE_MS+1);assertTrue(local.getWebRequests().isEmpty());assertNull(local.getQuoteByNumber("Q"));assertEquals(7L,local.webRequestCursor())
        val q=quote();repo.confirmOrder(q,order(q));local.cleanupPrivacy(System.currentTimeMillis()+PrivacyRetention.QUOTE_MS+1)
        assertNotNull(repo.getOrder("ORDER_TEST"))
    }
}
