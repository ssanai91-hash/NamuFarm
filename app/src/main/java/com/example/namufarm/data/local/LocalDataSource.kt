package com.example.namufarm.data.local

import com.example.namufarm.data.WebQuoteRequestEntity
import androidx.room.withTransaction
import com.example.namufarm.data.AppDatabase
import com.example.namufarm.data.FarmEntity
import com.example.namufarm.data.FarmInventoryEntity
import com.example.namufarm.data.InventoryDisplayRow
import com.example.namufarm.data.ManagementDao
import com.example.namufarm.data.OrderEntity
import com.example.namufarm.data.PendingSyncEntity
import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.data.SellingDisplayRow
import com.example.namufarm.data.SpeciesEntity
import kotlinx.coroutines.sync.withLock

/**
 * Room 접근을 한 곳으로 모은 로컬 데이터 소스.
 *
 * ViewModel/SyncManager가 DAO와 DB 트랜잭션을 직접 만지지 않도록 한다.
 */
class LocalDataSource(
    private val database: AppDatabase,
    private val dao: ManagementDao,
    private val privacy: com.example.namufarm.privacy.LocalPrivacyCipher? = null
) {
    private fun protect(s: String) = privacy?.protect(s) ?: s
    private fun reveal(s: String) = privacy?.reveal(s) ?: s
    private fun webRow(r: WebQuoteRequestEntity, encode: Boolean): WebQuoteRequestEntity {
        val f: (String) -> String = if (encode) ::protect else ::reveal
        return r.copy(customerName=f(r.customerName), customerPhone=f(r.customerPhone), customerEmail=f(r.customerEmail),
            region=f(r.region), itemsJson=f(r.itemsJson), message=f(r.message), recipientName=f(r.recipientName))
    }
    private fun quoteRow(r: QuoteEntity, encode: Boolean): QuoteEntity {
        val f: (String) -> String = if (encode) ::protect else ::reveal
        return r.copy(customerPhone=f(r.customerPhone), itemSummary=f(r.itemSummary))
    }
    private fun orderRow(r: OrderEntity, encode: Boolean): OrderEntity {
        val f: (String) -> String = if (encode) ::protect else ::reveal
        return r.copy(customerPhone=f(r.customerPhone), customerName=f(r.customerName), customerEmail=f(r.customerEmail),
            sourceRequestId=f(r.sourceRequestId), itemSummary=f(r.itemSummary), receiverName=f(r.receiverName),
            zipNo=f(r.zipNo), roadAddress=f(r.roadAddress), detailAddress=f(r.detailAddress), note=f(r.note))
    }
    fun getAllOrders() = dao.getAllOrders().map { orderRow(it, false) }
    fun getAllQuotes() = dao.getAllQuotes().map { quoteRow(it, false) }
    fun getAllQuoteContacts() = dao.getAllQuoteContacts().map { it.copy(recipientJson=reveal(it.recipientJson)) }
    fun advanceWebCursor(cursor: Long) { check(cursor >= webRequestCursor()); dao.savePrivacyCursor(com.example.namufarm.data.PrivacySyncEntity("web", cursor)) }
    fun deleteOrder(number: String) = dao.deleteOrder(number)
    fun deleteWebRequest(id: String) = dao.deleteWebRequest(id)
    fun deleteQuoteDocument(id: String) = dao.deleteQuoteDocument(id)
    fun replacePending(item: PendingSyncEntity) = dao.replacePending(item.copy(payloadJson=protect(item.payloadJson)))

    fun purgeRequestCopies(id: String) {
        advanceWebCursor(webRequestCursor())
        val bought=getAllOrders().map { it.sourceQuoteNumber }.toSet()
        getAllQuoteContacts().filter { com.google.gson.Gson().fromJson(it.recipientJson,com.example.namufarm.quotation.QuoteRecipient::class.java).sourceRequestId==id }.forEach { contact ->
            if (contact.quoteNumber !in bought) {
                getQuoteByNumber(contact.quoteNumber)?.let { deleteQuote(it) }
                dao.deleteQuoteContact(contact.quoteNumber)
            }
            if(contact.quoteNumber !in bought) getQuoteDocument(contact.quoteNumber)?.let { deleteQuoteDocument(it.documentId) }
        }
        deleteWebRequest(id)
    }
    suspend fun cleanupPrivacy(now: Long = System.currentTimeMillis()) = transaction {
        // Cursor lives separately, so disposal can never cause old requests to be downloaded again.
        advanceWebCursor(webRequestCursor())
        getWebRequests().filter { com.example.namufarm.privacy.PrivacyRetention.quoteTime(it.createdAt)+com.example.namufarm.privacy.PrivacyRetention.QUOTE_MS<=now }.forEach { purgeRequestCopies(it.requestId) }
        val bought=getAllOrders().map { it.sourceQuoteNumber }.toSet()
        getAllQuotes().filter { it.quoteNumber !in bought && com.example.namufarm.privacy.PrivacyRetention.quoteTime(it.saveDate)+com.example.namufarm.privacy.PrivacyRetention.QUOTE_MS<=now }.forEach { q ->
            deleteQuote(q);getQuoteDocument(q.quoteNumber)?.let { deleteQuoteDocument(it.documentId) }
        }
        getQuoteDocuments().filter { it.quoteNumber !in bought && it.issuedAt+com.example.namufarm.privacy.PrivacyRetention.QUOTE_MS<=now }.forEach { deleteQuoteDocument(it.documentId) }
        if(getAllPendingSync().isEmpty()) getAllOrders().filter { com.example.namufarm.privacy.PrivacyRetention.saleExpires(it)?.let { date -> date<=now }==true }.forEach { order ->
            getQuoteDocument(order.sourceQuoteNumber)?.let { deleteQuoteDocument(it.documentId) };getQuoteByNumber(order.sourceQuoteNumber)?.let { deleteQuote(it) };dao.deleteQuoteContact(order.sourceQuoteNumber);deleteOrder(order.orderNumber)
        }
    }

    /** Rewrites legacy customer copies in a single transaction. Does not discard any data. */
    suspend fun protectLegacyCustomerCopies() {
        if (privacy == null) return
        transaction {
            if (dao.privacyCursor("encrypted-local-v1") == 1L) return@transaction
            dao.getWebRequests().forEach { dao.replaceWebRequest(webRow(webRow(it, false), true)) }
            dao.getAllQuotes().forEach { dao.replaceQuote(quoteRow(quoteRow(it, false), true)) }
            dao.getAllOrders().forEach { dao.upsertOrder(orderRow(orderRow(it, false), true)) }
            dao.getAllQuoteContacts().forEach { dao.replaceQuoteContact(it.copy(recipientJson=protect(reveal(it.recipientJson)))) }
            dao.getQuoteDocuments().forEach { dao.replaceQuoteDocument(it.copy(snapshotJson=protect(reveal(it.snapshotJson)))) }
            dao.getAllPendingSync().forEach { dao.replacePending(it.copy(payloadJson=protect(reveal(it.payloadJson)))) }
            dao.savePrivacyCursor(com.example.namufarm.data.PrivacySyncEntity("encrypted-local-v1",1))
        }
        // Remove old plaintext pages/WAL copies after the transactional rewrite.
        if(dao.privacyCursor("scrubbed-local-v1")!=1L) {
            val db=database.openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor -> check(cursor.moveToFirst() && cursor.getInt(0)==0) }
            db.execSQL("VACUUM")
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor -> check(cursor.moveToFirst() && cursor.getInt(0)==0) }
            transaction { dao.savePrivacyCursor(com.example.namufarm.data.PrivacySyncEntity("scrubbed-local-v1",1)) }
        }
    }

    suspend fun <T> transaction(block: LocalDataSource.() -> T): T =
        database.withTransaction { block(this@LocalDataSource) }

    suspend fun <T> serializedSync(block: suspend () -> T): T =
        database.syncMutex.withLock { block() }

    fun requireNoPending() {
        check(dao.getAllPendingSync().isEmpty()) {
            "전송 대기 중인 로컬 변경이 있어 Pull을 중단했습니다. 다시 동기화해주세요."
        }
    }

    fun getWebRequests() = dao.getWebRequests().map { webRow(it, false) }
    fun webRequestCursor() = maxOf(dao.webRequestCursor(), dao.privacyCursor("web") ?: 0)
    fun markWebRequestRead(id: String, time: Long) = dao.markWebRequestRead(id, time)
    fun insertWebRequests(requests: List<WebQuoteRequestEntity>) {
        requests.forEach { incoming ->
            val existing = dao.getWebRequest(incoming.requestId)?.let { webRow(it, false) }
            if (existing == null) dao.insertWebRequest(webRow(incoming, true))
            else check(existing.copy(readAt = null) == incoming.copy(readAt = null)) {
                "이미 받은 요청의 내용이 변경되었습니다. 관리자 확인이 필요합니다."
            }
        }
    }

    fun getAllFarms(): List<FarmEntity> = dao.getAllFarms()
    fun getAllSpecies(): List<SpeciesEntity> = dao.getAllSpecies()
    fun getAllSpeciesIncludingInactive(): List<SpeciesEntity> = dao.getAllSpeciesIncludingInactive()
    fun getAllInventoryEntities(): List<FarmInventoryEntity> = dao.getAllInventoryEntities()
    fun getOpenCommerceSummaries(): List<String> = dao.getOpenCommerceSummaries().map(::reveal)
    fun getAllSellingInventory(): List<SellingDisplayRow> = dao.getAllSellingInventory()

    fun getInventoryRows(farmId: Int, speciesId: Int): List<InventoryDisplayRow> =
        dao.getInventoryRows(farmId, speciesId)

    fun getInventoryEntity(
        farmId: Int,
        speciesId: Int,
        quality: String,
        age: String,
        size: String,
        plantType: String
    ): FarmInventoryEntity? =
        dao.getInventoryEntity(farmId, speciesId, quality, age, size, plantType)

    fun upsertInventory(entity: FarmInventoryEntity) = dao.upsertInventory(entity)

    fun deleteInventory(
        farmId: Int,
        speciesId: Int,
        quality: String,
        age: String,
        size: String,
        plantType: String
    ) = dao.deleteInventory(farmId, speciesId, quality, age, size, plantType)

    fun clearAllInventory() = dao.clearAllInventory()

    fun getFarmByName(farmName: String): FarmEntity? {
        val matches = getAllFarms().filter { it.farmName.trim() == farmName.trim() }
        check(matches.size <= 1) { "중복 농원명을 먼저 확인해주세요: $farmName" }
        return matches.singleOrNull()
    }
    fun getSpeciesByName(speciesName: String): SpeciesEntity? {
        val matches = getAllSpeciesIncludingInactive().filter { it.speciesName.trim() == speciesName.trim() }
        check(matches.size <= 1) { "중복 묘목명을 먼저 확인해주세요: $speciesName" }
        return matches.singleOrNull()
    }

    fun upsertFarm(farm: FarmEntity): Long = dao.upsertFarm(farm)
    fun deleteFarmById(farmId: Int) = dao.deleteFarmById(farmId)
    fun deleteInventoryByFarmId(farmId: Int) = dao.deleteInventoryByFarmId(farmId)

    fun upsertSpecies(species: SpeciesEntity): Long = dao.upsertSpecies(species)
    fun deactivateSpecies(speciesId: Int) = dao.deactivateSpecies(speciesId)

    fun getQuoteByNumber(number: String): QuoteEntity? = dao.getQuoteByNumber(number).map { quoteRow(it, false) }.also { check(it.size <= 1) { "중복 견적번호를 확인해주세요." } }.singleOrNull()
    fun insertQuoteContact(contact: com.example.namufarm.data.QuoteContactEntity) = dao.insertQuoteContact(contact.copy(recipientJson=protect(contact.recipientJson)))
    fun replaceQuoteContact(contact: com.example.namufarm.data.QuoteContactEntity) = dao.replaceQuoteContact(contact.copy(recipientJson=protect(contact.recipientJson)))
    fun getQuoteContact(number: String) = dao.getQuoteContact(number)?.let { it.copy(recipientJson=reveal(it.recipientJson)) }
    fun getQuoteDocument(number: String) = dao.getQuoteDocument(number)?.let { it.copy(snapshotJson=reveal(it.snapshotJson)) }
    fun getQuoteDocuments() = dao.getQuoteDocuments().map { it.copy(snapshotJson=reveal(it.snapshotJson)) }
    fun insertQuoteDocument(document: com.example.namufarm.data.QuoteDocumentEntity) = dao.insertQuoteDocument(document.copy(snapshotJson=protect(document.snapshotJson)))
    fun insertQuote(quote: QuoteEntity) = dao.insertQuote(quoteRow(quote,true))
    fun replaceQuote(quote: QuoteEntity) = dao.replaceQuote(quoteRow(quote,true))
    fun getQuoteById(quoteId: Int): QuoteEntity? = dao.getQuoteById(quoteId)?.let { quoteRow(it,false) }
    fun deleteQuote(quote: QuoteEntity) { dao.deleteQuote(quote); dao.deleteQuoteContact(quote.quoteNumber) }
    fun getLastQuoteId(): Int? = dao.getLastQuoteId()
    fun getQuotesForDate(datePrefix: String): List<QuoteEntity> =
        dao.getQuotesForDate(datePrefix).map { quoteRow(it,false) }

    fun insertOrder(order: OrderEntity) = dao.insertOrder(orderRow(order,true))
    fun upsertOrder(order: OrderEntity) = dao.upsertOrder(orderRow(order,true))
    fun getOrderByNumber(orderNumber: String): OrderEntity? {
        check(dao.countOrdersByNumber(orderNumber) <= 1) {
            "중복 주문번호를 먼저 확인해주세요: $orderNumber"
        }
        return dao.getOrderByNumber(orderNumber)?.let { orderRow(it,false) }
    }

    fun getOrdersForDate(datePrefix: String): List<OrderEntity> =
        dao.getOrdersForDate(datePrefix).map { orderRow(it,false) }

    fun updateOrderStatus(orderNumber: String, status: String) =
        dao.updateOrderStatus(orderNumber, status)

    fun updateOrderDeliveryInfo(
        orderNumber: String,
        receiverName: String,
        zipNo: String,
        roadAddress: String,
        detailAddress: String,
        note: String
    ) = dao.updateOrderDeliveryInfo(
        orderNumber,
        protect(receiverName),
        protect(zipNo),
        protect(roadAddress),
        protect(detailAddress),
        protect(note)
    )

    fun insertPendingSync(item: PendingSyncEntity): Int =
        dao.insertPendingSync(item.copy(payloadJson=protect(item.payloadJson))).toInt()

    fun getAllPendingSync(): List<PendingSyncEntity> = dao.getAllPendingSync().map { it.copy(payloadJson=reveal(it.payloadJson)) }
    fun getPendingSyncById(syncId: Int): PendingSyncEntity? = dao.getPendingSyncById(syncId)?.let { it.copy(payloadJson=reveal(it.payloadJson)) }
    fun deletePendingSync(syncId: Int) = dao.deletePendingSync(syncId)
    fun markPendingSyncFailed(syncId: Int, error: String) =
        dao.markPendingSyncFailed(syncId, error)
}
