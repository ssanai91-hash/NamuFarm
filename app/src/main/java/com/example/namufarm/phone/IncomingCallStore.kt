package com.example.namufarm.phone

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

data class IncomingCaller(val id: String, val number: String, val receivedAt: Long)

/** Only a temporary caller suggestion. Never writes a quote, an order or pending_sync. */
class IncomingCallStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val enabled: Boolean get() = preferences.getBoolean("enabled", false)

    fun setEnabled(value: Boolean) {
        val editor = preferences.edit()
        if (!value) editor.clear()
        editor.putBoolean("enabled", value).apply()
    }

    fun latest(): IncomingCaller? {
        val id = preferences.getString("id", null) ?: return null
        val number = normalizePhoneNumber(preferences.getString("number", null)) ?: return null
        val timestamp = preferences.getLong("received_at", 0)
        val age = now() - timestamp
        if (age !in 0 until RETENTION_MILLIS) {
            clear(id)
            return null
        }
        return IncomingCaller(id, number, timestamp)
    }

    fun find(id: String): IncomingCaller? = latest()?.takeIf { it.id == id }

    /** Ignore the empty duplicate broadcast and repeated RINGING events for the same call. */
    fun recordRinging(rawNumber: String?): IncomingCaller? {
        if (!enabled) return null
        val number = normalizePhoneNumber(rawNumber) ?: return null
        val timestamp = now()
        if (preferences.getString("ringing_number", null) == number &&
            timestamp - preferences.getLong("ringing_at", 0) in 0 until RINGING_TIMEOUT_MILLIS) return null
        val caller = IncomingCaller(UUID.randomUUID().toString(), number, timestamp)
        preferences.edit()
            .putString("id", caller.id).putString("number", number).putLong("received_at", timestamp)
            .putString("ringing_number", number).putLong("ringing_at", timestamp).apply()
        return caller
    }

    fun endCall() {
        preferences.edit().remove("ringing_number").remove("ringing_at").apply()
    }

    /** A dialog for caller A must not remove a newer caller B. */
    fun clear(id: String) {
        if (preferences.getString("id", null) == id) {
            preferences.edit().remove("id").remove("number").remove("received_at")
                .remove("ringing_number").remove("ringing_at").apply()
        }
    }

    companion object {
        const val PREFERENCES = "incoming_call_assist"
        const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L
        private const val RINGING_TIMEOUT_MILLIS = 5 * 60 * 1000L
    }
}

/** Reject private-number placeholders, URI syntax and multiple SMS recipients. */
fun normalizePhoneNumber(raw: String?): String? {
    val number = raw?.trim()?.filterNot { it.isWhitespace() || it in "-()." } ?: return null
    return number.takeIf { it.matches(Regex("\\+?[0-9]{3,15}")) }
}
