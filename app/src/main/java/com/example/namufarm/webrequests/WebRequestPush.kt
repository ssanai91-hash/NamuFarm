package com.example.namufarm.webrequests

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.namufarm.BuildConfig
import com.example.namufarm.MainActivity
import com.example.namufarm.R
import com.example.namufarm.data.remote.WebQuoteValidator
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class NamuFarmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WebRequestPush.initialize(this)
        PushRegistrationWorker.enqueue(this)
    }
}

object WebRequestPush {
    const val ACTION = "com.example.namufarm.OPEN_WEB_REQUEST"
    const val CHANNEL = "web_quote_requests"
    val configured: Boolean get() = listOf(BuildConfig.FIREBASE_APPLICATION_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_SENDER_ID).all { it.isNotBlank() }
    fun initialize(context: Context): Boolean {
        if (!configured) return false
        return runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build())
            FirebaseMessaging.getInstance().isAutoInitEnabled = WebRequestSettings(context).paired
            true
        }.getOrDefault(false)
    }
    suspend fun token(context: Context): String? {
        if (!initialize(context)) return null
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }
    fun fromIntent(intent: Intent?): String? {
        if (intent?.action != ACTION) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "namufarm" || uri.authority != "web-request" || uri.pathSegments.size != 1) return null
        return uri.lastPathSegment?.takeIf(WebQuoteValidator::validId)
    }
    fun intent(context: Context, id: String) = Intent(context, MainActivity::class.java).apply {
        action = ACTION
        data = Uri.Builder().scheme("namufarm").authority("web-request").appendPath(id).build()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    fun show(context: Context, id: String) {
        if (!WebQuoteValidator.validId(id)) return
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "홈페이지 견적 요청", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "홈페이지에 접수된 새 묘목 견적 요청"; lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            })
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val pending = PendingIntent.getActivity(context, 0, intent(context,id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_web_request)
            .setContentTitle("나무팜 · 새 견적 요청").setContentText("홈페이지 요청이 도착했어요. 요청함에서 확인해주세요.")
            .setContentIntent(pending).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { NotificationManagerCompat.from(context).notify(id, 8201, notification) } catch (_: SecurityException) { }
    }
}

class WebRequestMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Persist rotation offline; register it when the connected inbox next refreshes.
        runCatching { WebRequestSettings(this).saveFcmToken(token); PushRegistrationWorker.enqueue(this) }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "quote_request" || !WebRequestSettings(this).paired) return
        message.data["requestId"]?.let { WebRequestPush.show(this, it) }
    }
}
