package com.example.namufarm.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommerceMigrationTest {
    @Test fun v10ToV13PreservesStockHistoryAndPending() { migrate(10) }
    @Test fun v11ToV13PreservesPrepaidAndStructuredAddress() { migrate(11) }

    @Test fun v12ToV13AddsInboxWithoutLosingPending() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-12.db"
        context.deleteDatabase(name)
        val original = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            original.managementDao().insertPendingSync(PendingSyncEntity(syncType = "inventory", payloadJson = "{}"))
        } finally { original.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            stripV15(db)
            db.execSQL("DROP TABLE web_quote_request")
            db.execSQL("DROP TABLE room_master_table")
            db.version = 12
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            assertEquals(1, migrated.managementDao().getAllPendingSync().size)
            assertTrue(migrated.managementDao().getWebRequests().isEmpty())
        } finally { migrated.close() }
        context.deleteDatabase(name)
    }

    @Test fun v13ToV14KeepsReceiptAndReadStateWithSafeDefaults() {
        val context = RuntimeEnvironment.getApplication(); val name = "migration-13.db"; context.deleteDatabase(name)
        val original = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try { original.managementDao().insertPendingSync(PendingSyncEntity(syncType = "inventory", payloadJson = "{}")) } finally { original.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            stripV15(raw)
            raw.execSQL("DROP TABLE web_quote_request")
            raw.execSQL("CREATE TABLE web_quote_request (requestId TEXT NOT NULL PRIMARY KEY, sequence INTEGER NOT NULL, createdAt TEXT NOT NULL, customerName TEXT NOT NULL, customerPhone TEXT NOT NULL, region TEXT NOT NULL, itemsJson TEXT NOT NULL, message TEXT NOT NULL, readAt INTEGER)")
            raw.execSQL("CREATE UNIQUE INDEX index_web_quote_request_sequence ON web_quote_request(sequence)")
            raw.execSQL("INSERT INTO web_quote_request VALUES('11111111-1111-4111-8111-111111111111',7,'2026-10-04T01:23:45.000Z','김나무','01012345678','옥천','[]','문의',42)")
            raw.execSQL("DROP TABLE quote_contact"); raw.execSQL("DROP TABLE quote_document"); raw.execSQL("DROP TABLE room_master_table"); raw.version = 13
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            val dao = migrated.managementDao(); val row = dao.getWebRequests().single()
            assertEquals(7L, row.sequence); assertEquals(42L, row.readAt); assertEquals("", row.customerEmail)
            assertFalse(row.formalQuoteRequested); assertEquals("sms", row.replyChannel)
            assertEquals(1, dao.getAllPendingSync().size); assertTrue(dao.getQuoteDocuments().isEmpty())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    /** Recreate the actual old order schema instead of merely changing the version number on a new DB. */
    private fun stripV16(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE quote_list RENAME TO quote_list_v16")
        db.execSQL("CREATE TABLE quote_list (quote_id INTEGER PRIMARY KEY AUTOINCREMENT, is_prepaid INTEGER NOT NULL, shipping_fee INTEGER NOT NULL, quote_number TEXT NOT NULL, save_date TEXT NOT NULL, customer_phone TEXT NOT NULL, total_qty INTEGER NOT NULL, total_price INTEGER NOT NULL, item_summary TEXT NOT NULL)")
        val fields = "quote_id,is_prepaid,shipping_fee,quote_number,save_date,customer_phone,total_qty,total_price,item_summary"
        db.execSQL("INSERT INTO quote_list ($fields) SELECT $fields FROM quote_list_v16")
        db.execSQL("DROP TABLE quote_list_v16")
    }
    private fun stripV15(db: SQLiteDatabase) {
        stripV16(db)
        db.execSQL("DROP TABLE privacy_sync")
        db.execSQL("ALTER TABLE order_list RENAME TO order_list_v15")
        db.execSQL("CREATE TABLE order_list (order_id INTEGER PRIMARY KEY AUTOINCREMENT, order_number TEXT NOT NULL, order_date TEXT NOT NULL, customer_phone TEXT NOT NULL, total_qty INTEGER NOT NULL, total_price INTEGER NOT NULL, item_summary TEXT NOT NULL, receiver_name TEXT NOT NULL DEFAULT '', zip_no TEXT NOT NULL DEFAULT '', road_address TEXT NOT NULL DEFAULT '', detail_address TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT '입금확인', is_prepaid INTEGER NOT NULL, shipping_fee INTEGER NOT NULL)")
        val fields="order_id,order_number,order_date,customer_phone,total_qty,total_price,item_summary,receiver_name,zip_no,road_address,detail_address,note,status,is_prepaid,shipping_fee"
        db.execSQL("INSERT INTO order_list ($fields) SELECT $fields FROM order_list_v15")
        db.execSQL("DROP TABLE order_list_v15")
    }
    @Test fun v14ToV15PreservesCustomerHistoryAndIndependentCursor() {
        val context=RuntimeEnvironment.getApplication();val name="migration-14.db";context.deleteDatabase(name)
        val original=Room.databaseBuilder(context,AppDatabase::class.java,name).allowMainThreadQueries().build()
        original.managementDao().insertOrder(OrderEntity(orderNumber="O",orderDate="2026-10-01 10:00",customerPhone="01012345678",totalQty=1,totalPrice=1000,itemSummary="소나무|||||1|1000|수기견적|0"))
        original.managementDao().insertWebRequest(WebQuoteRequestEntity("11111111-1111-4111-8111-111111111111",7,"2026-10-04T01:23:45.000Z","김나무","01012345678","옥천","[]","문의"))
        original.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { raw ->stripV15(raw);raw.execSQL("DROP TABLE room_master_table");raw.version=14 }
        val migrated=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(*AppDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {val dao=migrated.managementDao();val order=dao.getOrderByNumber("O")!!;assertEquals("01012345678",order.customerPhone);assertEquals(0L,order.revision);assertFalse(order.costConfirmed);assertEquals(7L,dao.privacyCursor("web"));assertEquals(1,dao.getWebRequests().size)}finally {migrated.close();context.deleteDatabase(name)}
    }

    @Test fun v15ToV16KeepsDraftAndDoesNotInventSentTime() {
        val context = RuntimeEnvironment.getApplication(); val name = "migration-15.db"; context.deleteDatabase(name)
        val original = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        original.managementDao().insertQuote(QuoteEntity(quoteNumber = "DRAFT", saveDate = "2026-10-05", customerPhone = "01012345678", totalQty = 1, totalPrice = 1000, itemSummary = "감나무|||||1|1000|수기견적|0"))
        original.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            stripV16(raw); raw.execSQL("DROP TABLE room_master_table"); raw.version = 15
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        try { val q = migrated.managementDao().getAllQuotes().single(); assertEquals("DRAFT", q.quoteNumber); assertEquals(0L, q.sentAt) }
        finally { migrated.close(); context.deleteDatabase(name) }
    }

    private fun migrate(version: Int) {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "migration-$version.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val raw = "감나무|||||2|1000|수기견적|0"
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE farm_info (farm_id INTEGER PRIMARY KEY AUTOINCREMENT, farm_name TEXT NOT NULL, address TEXT, phone TEXT, memo TEXT)")
            db.execSQL("CREATE TABLE species (species_id INTEGER PRIMARY KEY AUTOINCREMENT, species_name TEXT NOT NULL, name_farm TEXT, chosung TEXT, category TEXT, memo TEXT, is_active INTEGER DEFAULT 1)")
            db.execSQL("CREATE TABLE farm_inventory (farm_id INTEGER NOT NULL, species_id INTEGER NOT NULL, quality TEXT NOT NULL, age TEXT NOT NULL, size TEXT NOT NULL, plant_type TEXT NOT NULL, quantity INTEGER NOT NULL, wholesale_price INTEGER NOT NULL, retail_price INTEGER NOT NULL, PRIMARY KEY(farm_id,species_id,quality,age,size,plant_type))")
            db.execSQL("CREATE TABLE pending_sync (sync_id INTEGER PRIMARY KEY AUTOINCREMENT, sync_type TEXT NOT NULL, payload_json TEXT NOT NULL, created_at INTEGER NOT NULL, retry_count INTEGER NOT NULL, last_error TEXT NOT NULL)")
            val shipping = if (version == 11) ", is_prepaid INTEGER NOT NULL, shipping_fee INTEGER NOT NULL" else ""
            db.execSQL("CREATE TABLE quote_list (quote_id INTEGER PRIMARY KEY AUTOINCREMENT, quote_number TEXT NOT NULL, save_date TEXT NOT NULL, customer_phone TEXT NOT NULL, total_qty INTEGER NOT NULL, total_price INTEGER NOT NULL, item_summary TEXT NOT NULL$shipping)")
            db.execSQL("CREATE TABLE order_list (order_id INTEGER PRIMARY KEY AUTOINCREMENT, order_number TEXT NOT NULL, order_date TEXT NOT NULL, customer_phone TEXT NOT NULL, total_qty INTEGER NOT NULL, total_price INTEGER NOT NULL, item_summary TEXT NOT NULL, receiver_name TEXT NOT NULL DEFAULT '', zip_no TEXT NOT NULL DEFAULT '', road_address TEXT NOT NULL DEFAULT '', detail_address TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT '입금확인'$shipping)")
            db.execSQL("INSERT INTO farm_info(farm_id,farm_name) VALUES(1,'농원')")
            db.execSQL("INSERT INTO species(species_id,species_name) VALUES(1,'감나무')")
            db.execSQL("INSERT INTO farm_inventory VALUES(1,1,'상묘','1년생','1.0m','실생',10,500,1000)")
            db.execSQL("INSERT INTO pending_sync VALUES(7,'order','{}',123,2,'offline')")
            val fields = if (version == 11) ",is_prepaid,shipping_fee" else ""
            val values = if (version == 11) ",1,500" else ""
            db.execSQL("INSERT INTO quote_list(quote_id,quote_number,save_date,customer_phone,total_qty,total_price,item_summary$fields) VALUES(4,'Q','2026-09-29','010',2,2500,?$values)", arrayOf(raw))
            db.execSQL("INSERT INTO order_list(order_id,order_number,order_date,customer_phone,total_qty,total_price,item_summary,receiver_name,zip_no,road_address,detail_address,note,status$fields) VALUES(9,'O','2026-09-29','010',2,2500,?,'받는사람','01234','서울','101호','메모','입금완료'$values)", arrayOf(raw))
            db.version = version
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            val dao = db.managementDao() // First access validates the actual Room schema.
            val order = dao.getOrderByNumber("O")!!
            assertEquals(9, order.orderId)
            assertEquals(raw, order.itemSummary)
            assertEquals(OrderStatus.CONFIRMED, order.status)
            assertEquals("01234", order.zipNo)
            assertEquals("101호", order.detailAddress)
            assertEquals(version == 11, order.isPrepaid)
            assertEquals(if (version == 11) 500 else 0, order.shippingFee)
            assertEquals(4, dao.getQuotesForDate("2026-09-29").single().quoteId)
            assertEquals(10, dao.getAllInventoryEntities().single().quantity)
            assertEquals(7, dao.getAllPendingSync().single().syncId)
            assertEquals(2, dao.getAllPendingSync().single().retryCount)
            assertEquals("{}", dao.getAllPendingSync().single().payloadJson)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
