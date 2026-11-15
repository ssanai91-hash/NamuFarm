package com.example.namufarm.phone

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

/** One local receipt per explicit click. No customer records, sync jobs or retry queue. */
internal class WebsiteSmsReceipts(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(id: String): JSONObject? = runCatching {
        preferences.getString("sms_$id", null)?.let(::JSONObject)
    }.getOrNull()

    fun begin(id: String, number: String, parts: Int): Boolean {
        if (read(id) != null) return false
        val now = System.currentTimeMillis()
        val record = JSONObject().put("created", now).put("parts", parts)
            .put("number", "***-${number.takeLast(4)}").put("sent", JSONArray())
            .put("failed", false).put("uncertain", false)
        val editor = preferences.edit()
        // Retain at most 20 short receipts, including no raw phone numbers or message bodies.
        preferences.all.keys.filter { it.startsWith("sms_") }
            .sortedByDescending { read(it.removePrefix("sms_"))?.optLong("created") ?: 0L }
            .drop(19).forEach { editor.remove(it) }
        return editor.putString("sms_$id", record.toString()).putString("last", id).commit()
    }

    fun uncertain(id: String) {
        val row = read(id) ?: return
        preferences.edit().putString("sms_$id", row.put("uncertain", true).toString()).commit()
    }

    fun result(id: String, part: Int, code: Int) {
        val row = read(id) ?: return
        if (part !in 0 until row.optInt("parts")) return
        val sent = row.optJSONArray("sent") ?: JSONArray()
        val completed = (0 until sent.length()).map { sent.optInt(it) }.toMutableSet()
        if (!completed.add(part)) return
        if (code != Activity.RESULT_OK) row.put("failed", true)
        row.put("sent", JSONArray(completed.toList()))
        preferences.edit().putString("sms_$id", row.toString()).commit()
    }

    fun summary(id: String): String? {
        val row = read(id) ?: return null
        val count = row.optJSONArray("sent")?.length() ?: 0
        return when {
            row.optBoolean("failed") -> "일부 또는 전체 발송 실패 · 자동 재전송 안 함"
            count == row.optInt("parts") && count > 0 -> "발송 완료 · 상대 수신 여부는 미확인"
            row.optBoolean("uncertain") || System.currentTimeMillis() - row.optLong("created") > 120_000 ->
                "발송 결과 확인 필요 · 자동 재전송 안 함"
            else -> "발송 중…"
        }
    }

    fun lastSummary(): String? {
        val id = preferences.getString("last", null) ?: return null
        val row = read(id) ?: return null
        return "${row.optString("number")} · ${summary(id)}"
    }

    companion object { const val PREFS = "call_website_receipts" }
}

/** Invoked only by this app's explicit, immutable sent PendingIntents. */
class WebsiteSmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT) return
        val uri = intent.data ?: return
        if (uri.scheme != "namufarm-call-sms" || uri.host != "sent") return
        val id = uri.pathSegments.getOrNull(0) ?: return
        val part = uri.pathSegments.getOrNull(1)?.toIntOrNull() ?: return
        WebsiteSmsReceipts(context).result(id, part, resultCode)
    }

    companion object { internal const val ACTION_SENT = "com.example.namufarm.WEBSITE_SMS_SENT" }
}

internal object WebsiteSmsSender {
    /** After this returns, the caller must never retry the same call, even on exception/timeout. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun send(context: Context, id: String, number: String, subscriptionId: Int, body: String): String? {
        val manager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        val parts = manager.divideMessage(body)
        if (parts.isEmpty()) return "문자 내용을 준비하지 못했습니다."
        val receipts = WebsiteSmsReceipts(context)
        if (!receipts.begin(id, number, parts.size)) return "발송 기록이 있거나 저장할 수 없어 재전송하지 않았습니다."
        return try {
            val callbacks = ArrayList(parts.indices.map { index ->
                val intent = Intent(context, WebsiteSmsSentReceiver::class.java).apply {
                    action = WebsiteSmsSentReceiver.ACTION_SENT
                    data = Uri.parse("namufarm-call-sms://sent/$id/$index")
                }
                PendingIntent.getBroadcast(context, index, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            })
            if (parts.size == 1) manager.sendTextMessage(number, null, parts.single(), callbacks.single(), null)
            else manager.sendMultipartTextMessage(number, null, parts, callbacks, null)
            null
        } catch (_: Exception) {
            // The telephony process may already have accepted part of this multipart SMS.
            receipts.uncertain(id)
            "발송 결과를 확인할 수 없습니다. 자동 재전송하지 않습니다."
        }
    }
}
