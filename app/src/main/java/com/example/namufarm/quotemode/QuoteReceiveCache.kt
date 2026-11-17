package com.example.namufarm.quotemode

import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.Closeable
import java.io.File

/** Disposable, private, excluded from backup. Business Room schemas remain unchanged. */
internal class QuoteReceiveCache(file: File, scope: String) : Closeable {
    private val gson = Gson()
    private val db: SQLiteDatabase
    val meta: JsonObject
    init {
        file.parentFile?.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("PRAGMA secure_delete=ON")
        db.execSQL("CREATE TABLE IF NOT EXISTS rows(id TEXT PRIMARY KEY, seq INTEGER UNIQUE NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(body TEXT NOT NULL)")
        val saved = runCatching { db.rawQuery("SELECT body FROM meta", null).use { c ->
            if (c.moveToFirst()) gson.fromJson(c.getString(0), JsonObject::class.java) else null
        } }.getOrNull()
        meta = saved ?: JsonObject()
        val age = System.currentTimeMillis() - (meta.get("startedAt")?.asLong ?: 0L)
        if (meta.get("scope")?.asString != scope || age !in 0..3_600_000L) reset(scope)
    }
    fun reset(scope: String) {
        db.execSQL("DELETE FROM rows")
        meta.keySet().toList().forEach { meta.remove(it) }
        meta.addProperty("scope", scope); meta.addProperty("startedAt", System.currentTimeMillis())
        meta.addProperty("cursor", 0L); meta.addProperty("received", 0)
        save()
    }
    fun save() {
        db.execSQL("DELETE FROM meta")
        db.execSQL("INSERT INTO meta VALUES (?)", arrayOf(meta.toString()))
    }
    fun append(rows: List<com.example.namufarm.data.remote.WebQuoteRemote?>, cursor: Long) {
        check((java.io.File(db.path).parentFile?.usableSpace ?: 0L) > 32L * 1024 * 1024) {
            "견적 임시 수신 공간이 부족합니다. 기존 기기 자료를 유지합니다. 저장 공간을 확보해주세요."
        }
        db.beginTransaction()
        try {
            rows.forEach { row ->
                checkNotNull(row)
                db.execSQL("INSERT INTO rows VALUES (?,?,?)", arrayOf(checkNotNull(row.requestId), checkNotNull(row.sequence), gson.toJson(row)))
            }
            meta.addProperty("cursor", cursor)
            meta.addProperty("received", count())
            save(); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM rows", null).use { it.moveToFirst(); it.getInt(0) }
    fun contains(id: String): Boolean = db.rawQuery("SELECT 1 FROM rows WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
    fun page(after: Long): List<com.example.namufarm.data.remote.WebQuoteRemote?> =
        db.rawQuery("SELECT body FROM rows WHERE seq>? ORDER BY seq LIMIT 100", arrayOf(after.toString())).use { c ->
            buildList { while (c.moveToNext()) add(gson.fromJson(c.getString(0), com.example.namufarm.data.remote.WebQuoteRemote::class.java)) }
        }
    fun clear() { reset(meta.get("scope").asString) }
    override fun close() = db.close()
}

class QuoteReceivePending(message: String) : IllegalStateException(message)
