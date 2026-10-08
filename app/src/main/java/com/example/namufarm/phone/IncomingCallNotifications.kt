package com.example.namufarm.phone

import android.Manifest
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
import com.example.namufarm.MainActivity
import com.example.namufarm.R

data class CallAssistRequest(val callerId: String, val reply: Boolean = false) {
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION
        data = Uri.Builder().scheme("namufarm").authority("incoming-call")
            .appendPath(callerId).appendPath(if (reply) "reply" else "quote").build()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    companion object {
        const val ACTION = "com.example.namufarm.OPEN_INCOMING_CALL"
        fun fromIntent(intent: Intent?): CallAssistRequest? {
            if (intent?.action != ACTION) return null
            val uri = intent.data ?: return null
            if (uri.scheme != "namufarm" || uri.authority != "incoming-call" || uri.pathSegments.size != 2) return null
            val (id, action) = uri.pathSegments
            if (id.isBlank() || action !in listOf("quote", "reply")) return null
            return CallAssistRequest(id, action == "reply")
        }
    }
}

object IncomingCallNotifications {
    const val CHANNEL_ID = "incoming_customer"
    const val NOTIFICATION_ID = 8101

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "전화 고객 연결", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "수신 번호로 견적 또는 답장 작성"
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26 && context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        return true
    }

    fun show(context: Context, caller: IncomingCaller) {
        createChannel(context)
        if (!allowed(context)) return // The local suggestion remains available inside the app.
        val quote = pendingIntent(context, CallAssistRequest(caller.id))
        val reply = pendingIntent(context, CallAssistRequest(caller.id, reply = true))
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_notification).setContentTitle("나무팜 · 전화 고객")
            .setContentText("잠금을 해제해 수신 번호를 확인하세요").build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle("전화 온 고객 · 나무팜")
            .setContentText("${caller.number} · 눌러서 견적 또는 답장 작성")
            .setContentIntent(quote)
            .addAction(R.drawable.ic_call_notification, "견적 작성", quote)
            .addAction(R.drawable.ic_call_notification, "답장 작성", reply)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setOnlyAlertOnce(true).setSilent(true).setAutoCancel(true)
            .setTimeoutAfter(IncomingCallStore.RETENTION_MILLIS).build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission may be revoked between the check and notify().
        }
    }

    private fun pendingIntent(context: Context, request: CallAssistRequest): PendingIntent =
        PendingIntent.getActivity(context, 0, request.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}
