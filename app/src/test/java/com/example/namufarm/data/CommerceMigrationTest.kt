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
        val context = RuntimeEnvironment.getApplication<android.app.Application>()
        val name = "migration-12.db"
        context.deleteDatabase(name)
        Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build().use { db ->
            db.managementDao().insertPendingSync(PendingSyncEntity(syncType = "inventory", payloadJson = "{}"))
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE web_quote_request")
            db.execSQL("DROP TABLE room_master_table")
            db.version = 12
        }
        Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build().use { db ->
            assertEquals(1, db.managementDao().getAllPendingSync().size)
            assertTrue(db.managementDao().getWebRequests().isEmpty())
        }
        context.deleteDatabase(name)
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
