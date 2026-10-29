package com.example.namufarm.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.namufarm.BuildConfig
import com.example.namufarm.MainActivity
import com.example.namufarm.R
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * New quote / inquiry push notifications (Firebase Cloud Messaging).
 * Firebase values come from the builder's local.properties (never committed). Without them the app
 * runs exactly as before and simply shows no push notifications.
 * Notifications carry no customer name or phone number (lock-screen privacy).
 */
object NamuPush {
    const val EXTRA_OPEN = "namufarm_push_open" // "quote" or "inquiry"
    private const val CHANNEL_QUOTE = "new_quote_request"
    private const val CHANNEL_INQUIRY = "new_customer_inquiry"
    private const val PREFS = "namufarm_push"
    private const val KEY_REGISTERED = "registeredToken"

    val configured: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
        BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()

    /** Safe to call repeatedly (Application, Activity, worker). Returns false when push is not configured. */
    fun init(context: Context): Boolean {
        if (!configured) return false
        return runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context.applicationContext, FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build())
            }
            createChannels(context)
            true
        }.getOrDefault(false)
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_QUOTE, "새 견적요청", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "홈페이지로 새 견적요청이 들어오면 알려줍니다." })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_INQUIRY, "새 고객문의", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "홈페이지로 새 문의가 들어오면 알려줍니다." })
    }

    /** Registers this phone's push token with the server after network is available (retried by WorkManager). */
    fun scheduleRegister(context: Context) {
        if (!configured) return
        WorkManager.getInstance(context).enqueueUniqueWork("namufarm-push-register", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PushRegisterWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    internal suspend fun register(context: Context, force: Boolean = false) {
        check(init(context))
        val token = suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getString(KEY_REGISTERED, null) == token) return
        val client = com.example.namufarm.data.remote.OperationsMasterClient(
            com.example.namufarm.data.remote.MasterDeviceCredentials(context))
        val response = client.request("register_push", mapOf("fcmToken" to token))
        check(response.get("registered")?.asBoolean == true) { "알림 등록을 확인하지 못했습니다." }
        prefs.edit().putString(KEY_REGISTERED, token).apply()
    }

    fun show(context: Context, type: String) {
        val inquiry = type == "customer_inquiry"
        if (!inquiry && type != "quote_request") return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        init(context)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN, if (inquiry) "inquiry" else "quote")
        val pending = PendingIntent.getActivity(context, if (inquiry) 2 else 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, if (inquiry) CHANNEL_INQUIRY else CHANNEL_QUOTE)
            .setSmallIcon(R.drawable.ic_stat_namufarm)
            .setContentTitle(if (inquiry) "새 고객문의가 들어왔어요" else "새 견적요청이 들어왔어요")
            .setContentText("눌러서 확인하세요.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setContentIntent(pending).build()
        runCatching { NotificationManagerCompat.from(context).notify(if (inquiry) 2002 else 2001, notification) }
    }
}

class PushRegisterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        NamuPush.register(applicationContext); Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { if (runAttemptCount < 8) Result.retry() else Result.failure() }
}

class NamuMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { NamuPush.scheduleRegister(applicationContext) }
    override fun onMessageReceived(message: RemoteMessage) {
        NamuPush.show(applicationContext, message.data["type"].orEmpty())
    }
}

/** Firebase must be ready before a background push starts the process. */
class NamuFarmApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        NamuPush.init(this)
    }
}
