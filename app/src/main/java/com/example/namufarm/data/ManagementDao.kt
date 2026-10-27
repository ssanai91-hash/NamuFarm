package com.example.namufarm.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ManagementDao {

    @Query("SELECT * FROM farm_info ORDER BY farm_name ASC")
    fun getAllFarms(): List<FarmEntity>

    @Query("SELECT * FROM species WHERE is_active = 1 ORDER BY species_name ASC")
    fun getAllSpecies(): List<SpeciesEntity>

    // 🚨 1. 수정됨: size, plantType(AS plantType) Select 추가!
    @Query("""
        SELECT 
            f.species_id AS speciesId, 
            s.species_name AS speciesName, 
            f.quality, 
            f.age, 
            f.size, 
            f.plant_type AS plantType, 
            f.quantity, 
            f.wholesale_price AS wholesalePrice, 
            f.retail_price AS retailPrice
        FROM farm_inventory AS f
        INNER JOIN species AS s ON f.species_id = s.species_id
        WHERE f.farm_id = :farmId 
          AND f.species_id = :speciesId
          AND f.quantity > 0
        ORDER BY f.age ASC, 
            CASE f.quality 
                WHEN '특묘' THEN 1 WHEN '상묘' THEN 2
                WHEN '중묘' THEN 3 WHEN '하묘' THEN 4 ELSE 5 
            END ASC
    """)
    fun getInventoryRows(farmId: Int, speciesId: Int): List<InventoryDisplayRow>

    // 🚨 2. 수정됨: size, plantType(AS plantType) Select 추가!
    @Query("""
        SELECT 
            f.farm_id AS farmId, s.species_id AS speciesId,
            fm.farm_name AS farmName, s.species_name AS speciesName,
            f.quality, f.age, 
            f.size, f.plant_type AS plantType, 
            f.quantity, 
            f.wholesale_price AS wholesalePrice, f.retail_price AS retailPrice,
            s.chosung AS chosung
        FROM farm_inventory AS f
        INNER JOIN species AS s ON f.species_id = s.species_id
        INNER JOIN farm_info AS fm ON f.farm_id = fm.farm_id
        WHERE f.quantity > 0
        ORDER BY s.species_name ASC, f.age ASC, 
            CASE f.quality WHEN '특묘' THEN 1 WHEN '상묘' THEN 2 WHEN '중묘' THEN 3 WHEN '하묘' THEN 4 ELSE 5 END ASC
    """)
    fun getAllSellingInventory(): List<SellingDisplayRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertInventory(row: FarmInventoryEntity)

    // 🚨 3. 수정됨: Primary Key가 늘어났으므로 삭제 조건에도 size, plantType 완벽 명시!
    @Query("""
        DELETE FROM farm_inventory
        WHERE farm_id = :farmId AND species_id = :speciesId 
          AND quality = :quality AND age = :age
          AND size = :size AND plant_type = :plantType
    """)
    fun deleteInventory(
        farmId: Int,
        speciesId: Int,
        quality: String,
        age: String,
        size: String,
        plantType: String
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertFarm(farm: FarmEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSpecies(species: SpeciesEntity)

    // --- 📋 견적서 & 주문서 관리 ---

    @Insert
    fun insertQuote(quote: QuoteEntity)

    @Query("SELECT MAX(quote_id) FROM quote_list")
    fun getLastQuoteId(): Int?

    @Query("SELECT MAX(order_id) FROM order_list")
    fun getLastOrderId(): Int?

    @Query("SELECT * FROM quote_list ORDER BY quote_id DESC")
    fun getTodayQuotes(): List<QuoteEntity>

    @Query("SELECT * FROM order_list ORDER BY order_id DESC")
    fun getTodayOrders(): List<OrderEntity>

    @Insert
    fun insertOrder(order: OrderEntity)

    // ✅ 추가: 주문서 배송정보 업데이트
    @Query("""
    UPDATE order_list
    SET receiver_name = :receiverName,
        zip_no = :zipNo,
        road_address = :roadAddress,
        detail_address = :detailAddress,
        note = :note
    WHERE order_number = :orderNumber
""")
    fun updateOrderDeliveryInfo(
        orderNumber: String,
        receiverName: String,
        zipNo: String,
        roadAddress: String,
        detailAddress: String,
        note: String
    )

    @Delete
    fun deleteQuote(quote: QuoteEntity)

    @Delete
    fun deleteOrder(order: OrderEntity)

    @Query("DELETE FROM farm_inventory")
    fun clearAllInventory()

    @Query("SELECT * FROM farm_info WHERE farm_name = :farmName LIMIT 1")
    fun getFarmByName(farmName: String): FarmEntity?

    @Query("SELECT * FROM species WHERE species_name = :speciesName LIMIT 1")
    fun getSpeciesByName(speciesName: String): SpeciesEntity?

    @Query("""
    SELECT * FROM farm_inventory
    WHERE farm_id = :farmId
      AND species_id = :speciesId
      AND quality = :quality
      AND age = :age
      AND size = :size
      AND plant_type = :plantType
    LIMIT 1
""")
    fun getInventoryEntity(
        farmId: Int,
        speciesId: Int,
        quality: String,
        age: String,
        size: String,
        plantType: String
    ): FarmInventoryEntity?

    @Insert
    fun insertPendingSync(item: PendingSyncEntity): Long

    @Query("SELECT * FROM pending_sync ORDER BY sync_id ASC")
    fun getAllPendingSync(): List<PendingSyncEntity>

    @Query("SELECT * FROM pending_sync ORDER BY sync_id ASC")
    fun observePendingSync(): Flow<List<PendingSyncEntity>>

    @Query("DELETE FROM pending_sync WHERE sync_id = :syncId")
    fun deletePendingSync(syncId: Int)

    @Query("""
    UPDATE pending_sync
    SET retry_count = retry_count + 1,
        last_error = :error
    WHERE sync_id = :syncId
""")
    fun markPendingSyncFailed(syncId: Int, error: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertFarm(farm: FarmEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSpecies(species: SpeciesEntity): Long

    @Query("DELETE FROM farm_inventory WHERE farm_id = :farmId")
    fun deleteInventoryByFarmId(farmId: Int)

    @Query("DELETE FROM farm_info WHERE farm_id = :farmId")
    fun deleteFarmById(farmId: Int)

    @Query("UPDATE species SET is_active = 0 WHERE species_id = :speciesId")
    fun deactivateSpecies(speciesId: Int)

    @Query("DELETE FROM farm_info")
    fun clearAllFarms()

    @Query("DELETE FROM species")
    fun clearAllSpecies()

    // Call only inside backupAndClearPendingSync, after the backup has been saved.
    @Query("DELETE FROM pending_sync")
    fun clearAllPendingSync(): Int

    /** Keep concurrent inserts/updates outside the snapshot-to-delete interval. No schema change. */
    @Transaction
    fun backupAndClearPendingSync(
        confirmed: List<PendingSyncEntity>,
        writeBackup: (List<PendingSyncEntity>) -> Unit
    ): Int? {
        val pending = getAllPendingSync()
        if (pending != confirmed) return null // The user must confirm the changed queue again.
        if (pending.isEmpty()) return 0
        writeBackup(pending) // An exception rolls back and MUST NOT reach DELETE.
        return clearAllPendingSync()
    }

    // 🚨 [추가] 중복 주문 방지용: 주문번호로 기존 주문 삭제하는 쿼리
    @Query("DELETE FROM order_list WHERE order_number = :orderNumber")
    fun deleteOrderByNumber(orderNumber: String)

    @Query("""
    UPDATE order_list
    SET status = :status
    WHERE order_number = :orderNumber
""")
    fun updateOrderStatus(
        orderNumber: String,
        status: String
    )

    @Query("SELECT * FROM species ORDER BY species_name")
    fun getAllSpeciesIncludingInactive(): List<SpeciesEntity>

    @Query("SELECT * FROM species WHERE species_id = :id LIMIT 1")
    fun getSpeciesById(id: Int): SpeciesEntity?

    @Query("SELECT COUNT(*) FROM farm_inventory WHERE farm_id = :id")
    fun getFarmInventoryCount(id: Int): Int

    @Query("SELECT * FROM master_links WHERE kind = :kind")
    fun getMasterLinks(kind: String): List<MasterLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMasterLink(link: MasterLinkEntity)

    @Query("DELETE FROM master_links WHERE kind = :kind AND local_id = :id")
    fun deleteMasterLink(kind: String, id: Int)

    @Query("UPDATE pending_sync SET payload_json = :json WHERE sync_id = :id")
    fun updatePendingPayload(id: Int, json: String)


    @Query("SELECT * FROM inventory_links")
    fun getInventoryLinks(): List<InventoryLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertInventoryLink(link: InventoryLinkEntity)

    @Query("SELECT * FROM inventory_links WHERE farm_id=:farmId AND species_id=:speciesId AND quality=:quality AND age=:age AND size=:size AND plant_type=:plantType LIMIT 1")
    fun getInventoryLink(farmId: Int, speciesId: Int, quality: String, age: String, size: String, plantType: String): InventoryLinkEntity?

    @Query("DELETE FROM inventory_links WHERE farm_id=:farmId AND species_id=:speciesId AND quality=:quality AND age=:age AND size=:size AND plant_type=:plantType")
    fun deleteInventoryLink(farmId: Int, speciesId: Int, quality: String, age: String, size: String, plantType: String)

    @Query("SELECT * FROM farm_inventory")
    fun getAllInventoryEntities(): List<FarmInventoryEntity>

    @Query("SELECT * FROM farm_inventory WHERE farm_id=:farmId AND species_id=:speciesId AND quality=:quality AND age=:age AND size=:size AND plant_type=:plantType LIMIT 1")
    fun findInventory(farmId: Int, speciesId: Int, quality: String, age: String, size: String, plantType: String): FarmInventoryEntity?

    @Query("UPDATE pending_sync SET retry_count=retry_count+1 WHERE sync_id=:id")
    fun markInventoryAttempt(id: Int)

    @Query("DELETE FROM pending_sync WHERE sync_type='inventory'")
    fun deleteLegacyInventoryPending(): Int
} // <- 인터페이스 닫는 괄호