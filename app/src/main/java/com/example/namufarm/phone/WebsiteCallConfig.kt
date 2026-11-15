package com.example.namufarm.phone

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.namufarm.quotation.QuoteIssuerSettings

/** Separate from customer/quote data and deliberately excluded from backup/transfer. */
internal class WebsiteCallConfig(context: Context) {
    val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { check(preferences.edit().putBoolean("enabled", value).commit()) }
    var smsSubscriptionId: Int
        get() = preferences.getInt("sms_subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        set(value) { check(preferences.edit().putInt("sms_subscription", value).commit()) }
    var problem: String
        get() = preferences.getString("problem", "").orEmpty()
        set(value) { preferences.edit().putString("problem", value).apply() }

    companion object { const val PREFS = "call_website_config" }
}

internal fun websiteCallPermissions(): Array<String> = buildList {
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.SEND_SMS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

internal fun hasWebsiteCallPermissions(context: Context) = websiteCallPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

internal fun websiteCallNotificationsAllowed(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT < 26) return true
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return manager.getNotificationChannel("call_website_assist")?.importance != NotificationManager.IMPORTANCE_NONE
}

internal fun websiteCallReady(context: Context) =
    context.packageManager.hasSystemFeature(if (Build.VERSION.SDK_INT >= 33)
        PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY) &&
        hasWebsiteCallPermissions(context) && Settings.canDrawOverlays(context) &&
        websiteCallNotificationsAllowed(context)

internal fun websiteCallMessage(context: Context): String {
    val issuer = QuoteIssuerSettings(context).load()
    val representative = issuer.representative.trim().replace(Regex("[\\r\\n]+"), " ")
    val greeting = if (representative.isNotBlank()) "나무팜 대표 ${representative}입니다." else "나무팜입니다."
    return "안녕하세요! $greeting\n\n" +
        "묘목 견적 홈페이지 보내드립니다.\n" +
        "https://namufarm.co.kr\n\n" +
        "원하시는 묘목과 수량을 편하게 남겨주세요.\n" +
        "이용이 어려우시면 이 번호로 전화나 문자 주세요.\n감사합니다."
}

@Suppress("MissingPermission")
internal fun websiteSmsSubscriptions(context: Context): List<SubscriptionInfo> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return runCatching {
        context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
            .sortedBy { it.simSlotIndex }
    }.getOrDefault(emptyList())
}

internal fun websiteSmsSubscription(context: Context): Int? {
    val active = websiteSmsSubscriptions(context)
    val selected = WebsiteCallConfig(context).smsSubscriptionId
    if (selected != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        // A removed/replaced SIM must not silently fall back to a different sender.
        return selected.takeIf { id -> active.any { it.subscriptionId == id } }
    }
    val default = SubscriptionManager.getDefaultSmsSubscriptionId()
    return active.firstOrNull { it.subscriptionId == default }?.subscriptionId
        ?: active.singleOrNull()?.subscriptionId
}

internal fun websiteSmsNumber(raw: String?): String? = raw?.let(::normalizePhoneNumber)
    ?.takeIf { Regex("01[016789][0-9]{7,8}").matches(it) }
