package com.example.namufarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FarmEntity::class,
        SpeciesEntity::class,
        FarmInventoryEntity::class,
        QuoteEntity::class,
        OrderEntity::class,
        PendingSyncEntity::class,
        MasterLinkEntity::class,
        InventoryLinkEntity::class
    ],
    version = 14, // Add inventory versions only; preserve every existing table.
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun managementDao(): ManagementDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$tableName'")
                .use { cursor ->
                    return cursor.moveToFirst()
                }
        }

        private fun columnExists(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String
        ): Boolean {
            if (!tableExists(db, tableName)) return false

            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == columnName) {
                        return true
                    }
                }
            }
            return false
        }

        private fun migrateLegacySchemaToV7(db: SupportSQLiteDatabase) {
            // =========================
            // farm_info
            // =========================
            if (tableExists(db, "farm_info")) {
                db.execSQL("ALTER TABLE farm_info RENAME TO farm_info_old")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS farm_info (
                        farm_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        farm_name TEXT NOT NULL,
                        address TEXT,
                        phone TEXT,
                        memo TEXT
                    )
                """.trimIndent())

                val addressExpr = if (columnExists(db, "farm_info_old", "address")) "address" else "NULL"
                val phoneExpr = if (columnExists(db, "farm_info_old", "phone")) "phone" else "NULL"
                val memoExpr = if (columnExists(db, "farm_info_old", "memo")) "memo" else "NULL"

                db.execSQL("""
                    INSERT INTO farm_info (farm_id, farm_name, address, phone, memo)
                    SELECT farm_id, farm_name, $addressExpr, $phoneExpr, $memoExpr
                    FROM farm_info_old
                """.trimIndent())

                db.execSQL("DROP TABLE farm_info_old")
            } else {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS farm_info (
                        farm_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        farm_name TEXT NOT NULL,
                        address TEXT,
                        phone TEXT,
                        memo TEXT
                    )
                """.trimIndent())
            }

            // =========================
            // species
            // =========================
            if (tableExists(db, "species")) {
                db.execSQL("ALTER TABLE species RENAME TO species_old")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS species (
                        species_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        species_name TEXT NOT NULL,
                        name_farm TEXT,
                        chosung TEXT,
                        category TEXT,
                        memo TEXT,
                        is_active INTEGER DEFAULT 1
                    )
                """.trimIndent())

                val nameFarmExpr = if (columnExists(db, "species_old", "name_farm")) "name_farm" else "NULL"
                val chosungExpr = if (columnExists(db, "species_old", "chosung")) "chosung" else "NULL"
                val categoryExpr = if (columnExists(db, "species_old", "category")) "category" else "NULL"
                val memoExpr = if (columnExists(db, "species_old", "memo")) "memo" else "NULL"
                val isActiveExpr = if (columnExists(db, "species_old", "is_active")) "is_active" else "1"

                db.execSQL("""
                    INSERT INTO species (
                        species_id, species_name, name_farm, chosung, category, memo, is_active
                    )
                    SELECT
                        species_id, species_name, $nameFarmExpr, $chosungExpr, $categoryExpr, $memoExpr, $isActiveExpr
                    FROM species_old
                """.trimIndent())

                db.execSQL("DROP TABLE species_old")
            } else {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS species (
                        species_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        species_name TEXT NOT NULL,
                        name_farm TEXT,
                        chosung TEXT,
                        category TEXT,
                        memo TEXT,
                        is_active INTEGER DEFAULT 1
                    )
                """.trimIndent())
            }

            // =========================
            // farm_inventory
            // =========================
            if (tableExists(db, "farm_inventory")) {
                db.execSQL("ALTER TABLE farm_inventory RENAME TO farm_inventory_old")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS farm_inventory (
                        farm_id INTEGER NOT NULL,
                        species_id INTEGER NOT NULL,
                        quality TEXT NOT NULL,
                        age TEXT NOT NULL,
                        size TEXT NOT NULL,
                        plant_type TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        wholesale_price INTEGER NOT NULL,
                        retail_price INTEGER NOT NULL,
                        PRIMARY KEY(farm_id, species_id, quality, age, size, plant_type)
                    )
                """.trimIndent())

                val sizeExpr = if (columnExists(db, "farm_inventory_old", "size")) "size" else "'0.0m'"
                val plantTypeExpr = if (columnExists(db, "farm_inventory_old", "plant_type")) "plant_type" else "'실생'"

                db.execSQL("""
                    INSERT OR REPLACE INTO farm_inventory (
                        farm_id, species_id, quality, age, size, plant_type,
                        quantity, wholesale_price, retail_price
                    )
                    SELECT
                        farm_id, species_id, quality, age, $sizeExpr, $plantTypeExpr,
                        quantity, wholesale_price, retail_price
                    FROM farm_inventory_old
                """.trimIndent())

                db.execSQL("DROP TABLE farm_inventory_old")
            } else {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS farm_inventory (
                        farm_id INTEGER NOT NULL,
                        species_id INTEGER NOT NULL,
                        quality TEXT NOT NULL,
                        age TEXT NOT NULL,
                        size TEXT NOT NULL,
                        plant_type TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        wholesale_price INTEGER NOT NULL,
                        retail_price INTEGER NOT NULL,
                        PRIMARY KEY(farm_id, species_id, quality, age, size, plant_type)
                    )
                """.trimIndent())
            }

            // =========================
            // quote_list
            // =========================
            if (tableExists(db, "quote_list")) {
                db.execSQL("ALTER TABLE quote_list RENAME TO quote_list_old")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS quote_list (
                        quote_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        quote_number TEXT NOT NULL,
                        save_date TEXT NOT NULL,
                        customer_phone TEXT NOT NULL,
                        total_qty INTEGER NOT NULL,
                        total_price INTEGER NOT NULL,
                        item_summary TEXT NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO quote_list (
                        quote_id, quote_number, save_date, customer_phone,
                        total_qty, total_price, item_summary
                    )
                    SELECT
                        quote_id, quote_number, save_date, customer_phone,
                        total_qty, total_price, item_summary
                    FROM quote_list_old
                """.trimIndent())

                db.execSQL("DROP TABLE quote_list_old")
            } else {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS quote_list (
                        quote_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        quote_number TEXT NOT NULL,
                        save_date TEXT NOT NULL,
                        customer_phone TEXT NOT NULL,
                        total_qty INTEGER NOT NULL,
                        total_price INTEGER NOT NULL,
                        item_summary TEXT NOT NULL
                    )
                """.trimIndent())
            }

            // =========================
            // order_list
            // =========================
            if (tableExists(db, "order_list")) {
                db.execSQL("ALTER TABLE order_list RENAME TO order_list_old")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS order_list (
                        order_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        order_number TEXT NOT NULL,
                        order_date TEXT NOT NULL,
                        customer_phone TEXT NOT NULL,
                        total_qty INTEGER NOT NULL,
                        total_price INTEGER NOT NULL,
                        item_summary TEXT NOT NULL,
                        receiver_name TEXT NOT NULL DEFAULT '',
                        zip_no TEXT NOT NULL DEFAULT '',
                        road_address TEXT NOT NULL DEFAULT '',
                        detail_address TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())

                val receiverExpr = if (columnExists(db, "order_list_old", "receiver_name")) "receiver_name" else "''"
                val zipExpr = if (columnExists(db, "order_list_old", "zip_no")) "zip_no" else "''"
                val roadExpr = if (columnExists(db, "order_list_old", "road_address")) "road_address" else "''"
                val detailExpr = if (columnExists(db, "order_list_old", "detail_address")) "detail_address" else "''"

                db.execSQL("""
                    INSERT INTO order_list (
                        order_id, order_number, order_date, customer_phone,
                        total_qty, total_price, item_summary,
                        receiver_name, zip_no, road_address, detail_address
                    )
                    SELECT
                        order_id, order_number, order_date, customer_phone,
                        total_qty, total_price, item_summary,
                        $receiverExpr, $zipExpr, $roadExpr, $detailExpr
                    FROM order_list_old
                """.trimIndent())

                db.execSQL("DROP TABLE order_list_old")
            } else {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS order_list (
                        order_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        order_number TEXT NOT NULL,
                        order_date TEXT NOT NULL,
                        customer_phone TEXT NOT NULL,
                        total_qty INTEGER NOT NULL,
                        total_price INTEGER NOT NULL,
                        item_summary TEXT NOT NULL,
                        receiver_name TEXT NOT NULL DEFAULT '',
                        zip_no TEXT NOT NULL DEFAULT '',
                        road_address TEXT NOT NULL DEFAULT '',
                        detail_address TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
            }

            // =========================
            // pending_sync
            // =========================
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS pending_sync (
                    sync_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sync_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    retry_count INTEGER NOT NULL,
                    last_error TEXT NOT NULL
                )
            """.trimIndent())
        }

        private val MIGRATION_1_7 = object : Migration(1, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacySchemaToV7(db)
            }
        }

        private val MIGRATION_2_7 = object : Migration(2, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacySchemaToV7(db)
            }
        }

        private val MIGRATION_3_7 = object : Migration(3, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacySchemaToV7(db)
            }
        }

        private val MIGRATION_4_7 = object : Migration(4, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacySchemaToV7(db)
            }
        }

        private val MIGRATION_5_7 = object : Migration(5, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacySchemaToV7(db)
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateLegacySchemaToV7(db)
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE order_list ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE order_list ADD COLUMN status TEXT NOT NULL DEFAULT '입금확인'"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
            CREATE TABLE IF NOT EXISTS order_list_new (
                order_id INTEGER PRIMARY KEY AUTOINCREMENT,
                order_number TEXT NOT NULL,
                order_date TEXT NOT NULL,
                customer_phone TEXT NOT NULL,
                total_qty INTEGER NOT NULL,
                total_price INTEGER NOT NULL,
                item_summary TEXT NOT NULL,
                receiver_name TEXT NOT NULL DEFAULT '',
                zip_no TEXT NOT NULL DEFAULT '',
                road_address TEXT NOT NULL DEFAULT '',
                detail_address TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT '입금확인'
            )
        """.trimIndent())

                db.execSQL("""
            INSERT INTO order_list_new (
                order_id,
                order_number,
                order_date,
                customer_phone,
                total_qty,
                total_price,
                item_summary,
                receiver_name,
                zip_no,
                road_address,
                detail_address,
                note,
                status
            )
            SELECT
                order_id,
                order_number,
                order_date,
                customer_phone,
                total_qty,
                total_price,
                item_summary,
                receiver_name,
                zip_no,
                road_address,
                detail_address,
                note,
                CASE
                    WHEN status IS NULL
                      OR status = ''
                      OR status = '입금대기'
                      OR status = '입금완료'
                    THEN '입금확인'
                    ELSE status
                END
            FROM order_list
        """.trimIndent())

                db.execSQL("DROP TABLE order_list")
                db.execSQL("ALTER TABLE order_list_new RENAME TO order_list")
            }
        }


        private val MIGRATION_10_12 = object : Migration(10, 12) {
            override fun migrate(db: SupportSQLiteDatabase) { addShippingColumns(db) }
        }
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) { addShippingColumns(db) }
        }
        private fun addShippingColumns(db: SupportSQLiteDatabase) {
            for (table in listOf("quote_list", "order_list")) {
                if (!columnExists(db, table, "is_prepaid"))
                    db.execSQL("ALTER TABLE $table ADD COLUMN is_prepaid INTEGER NOT NULL DEFAULT 0")
                if (!columnExists(db, table, "shipping_fee"))
                    db.execSQL("ALTER TABLE $table ADD COLUMN shipping_fee INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS master_links (
                    kind TEXT NOT NULL, local_id INTEGER NOT NULL,
                    remote_id INTEGER NOT NULL, version TEXT NOT NULL,
                    PRIMARY KEY(kind, local_id))""".trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_master_links_kind_remote_id ON master_links(kind, remote_id)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS inventory_links (
                    farm_id INTEGER NOT NULL, species_id INTEGER NOT NULL,
                    quality TEXT NOT NULL, age TEXT NOT NULL, size TEXT NOT NULL,
                    plant_type TEXT NOT NULL, version TEXT NOT NULL,
                    PRIMARY KEY(farm_id, species_id, quality, age, size, plant_type))""".trimIndent())
            }
        }

        @Synchronized fun closeForRestore() {
            instance?.close()
            instance = null
        }
        fun getDatabase(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                val newInstance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "NamuFarm.db"
                )
                    .addMigrations(
                        MIGRATION_1_7,
                        MIGRATION_2_7,
                        MIGRATION_3_7,
                        MIGRATION_4_7,
                        MIGRATION_5_7,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_12,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14
                    )
                    .build()

                instance = newInstance
                newInstance
            }
        }
    }
}