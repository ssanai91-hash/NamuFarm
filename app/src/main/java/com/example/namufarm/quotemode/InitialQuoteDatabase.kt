package com.example.namufarm.quotemode

import android.content.Context
import androidx.room.*
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.data.DeferredWebRequestEntity
import kotlinx.coroutines.flow.Flow

/** Quote intake is isolated from the original farm, stock and order database. */
@Database(entities = [WebQuoteRequestEntity::class, DeferredWebRequestEntity::class],
    version = 1, exportSchema = false)
abstract class InitialQuoteDatabase : RoomDatabase() {
    abstract fun inbox(): InitialQuoteDao
    companion object {
        @Volatile private var instance: InitialQuoteDatabase? = null
        fun get(context: Context): InitialQuoteDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                InitialQuoteDatabase::class.java, "NamuFarmQuotes.db").build().also { instance = it }
        }
    }
}

@Dao
interface InitialQuoteDao {
    @Query("SELECT * FROM web_quote_request ORDER BY sequence DESC")
    fun observeRequests(): Flow<List<WebQuoteRequestEntity>>
    @Query("SELECT COUNT(*) FROM deferred_web_request")
    fun observeDeferredCount(): Flow<Int>
    @Query("SELECT * FROM web_quote_request ORDER BY sequence DESC")
    fun requests(): List<WebQuoteRequestEntity>
    @Query("SELECT COUNT(*) FROM deferred_web_request")
    fun deferredCount(): Int
    @Query("DELETE FROM web_quote_request")
    fun clearRequests()
    @Query("DELETE FROM deferred_web_request")
    fun clearDeferred()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRequests(rows: List<WebQuoteRequestEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDeferred(rows: List<DeferredWebRequestEntity>)
    @Query("UPDATE web_quote_request SET readAt = :now WHERE requestId = :id AND readAt IS NULL")
    fun read(id: String, now: Long)
    @Query("UPDATE web_quote_request SET status = '견적확정', quotePricingJson = :pricing WHERE requestId = :id")
    fun confirmed(id: String, pricing: String)
    @Query("DELETE FROM web_quote_request WHERE requestId = :id")
    fun deleteRequest(id: String)
    @Query("DELETE FROM deferred_web_request WHERE requestId = :id")
    fun deleteDeferred(id: String)
}
