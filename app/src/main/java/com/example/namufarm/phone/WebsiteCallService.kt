package com.example.namufarm.phone

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** User-enabled foreground monitor. Only an overlay button click can initiate an SMS. */
class WebsiteCallService : Service() {
    private data class Call(
        val id: String = UUID.randomUUID().toString(),
        val started: Long = SystemClock.elapsedRealtime(),
        var number: String? = null,
        var connected: Boolean = false,
        var blocked: Boolean = false,
        var attempted: Boolean = false,
        var dismissed: Boolean = false,
        var feedback: String = ""
    )

    private lateinit var config: WebsiteCallConfig
    private lateinit var receipts: WebsiteSmsReceipts
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var call: Call? = null
    private var phase = "UNTRACKED"
    private var overlay: WebsiteCallOverlay? = null
    private var overlayCallId: String? = null

    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post {
            if (!config.enabled) stopSelf() else render()
        }
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!config.enabled || !websiteCallReady(this@WebsiteCallService)) {
                config.problem = "권한 또는 알림 설정을 확인하고 전화 안내를 다시 켜주세요."
                stopSelf()
                return
            }
            if (phoneState() == TelephonyManager.CALL_STATE_IDLE) clearCall()
            else render()
            handler.postDelayed(this, 15_000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> phoneChanged(intent)
                Intent.ACTION_SCREEN_OFF -> removeOverlay()
                Intent.ACTION_USER_PRESENT, Intent.ACTION_CONFIGURATION_CHANGED -> render()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = WebsiteCallConfig(this)
        receipts = WebsiteSmsReceipts(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote immediately, including sticky/reboot starts, before any phone processing.
        if (!promote()) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_DISABLE) {
            config.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!config.enabled || !websiteCallReady(this)) {
            config.problem = "전화·통화 기록·문자·알림·다른 앱 위 표시 권한을 확인해주세요."
            stopSelf()
            return START_NOT_STICKY
        }
        if (!registered) {
            // Never reconstruct a current recipient from a call log or a previous process.
            // If restarted mid-call, wait for that call to end and for a fresh incoming call.
            phase = if (phoneState() == TelephonyManager.CALL_STATE_IDLE) "IDLE" else "UNTRACKED"
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }
            try {
                // PHONE_STATE is a protected system broadcast; telephony may use a non-system UID.
                ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                registered = true
                config.preferences.registerOnSharedPreferenceChangeListener(changes)
                receipts.preferences.registerOnSharedPreferenceChangeListener(changes)
                handler.postDelayed(heartbeat, 15_000)
            } catch (_: Exception) {
                config.problem = "통화 감지를 시작하지 못했습니다. 설정을 확인해주세요."
                stopSelf()
                return START_NOT_STICKY
            }
        }
        mutableRunning.value = true
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun phoneChanged(intent: Intent) {
        if (!config.enabled || !hasWebsiteCallPermissions(this)) { stopSelf(); return }
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_IDLE -> clearCall()
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.let(::normalizePhoneNumber)
                when (phase) {
                    "IDLE" -> {
                        call = Call(number = number)
                        phase = "RINGING"
                    }
                    "RINGING" -> {
                        val current = call
                        // The system can broadcast twice, once with an empty number.
                        if (current != null && number != null) {
                            if (current.number == null) current.number = number
                            else if (current.number != number) { current.blocked = true; phase = "BLOCKED" }
                        }
                    }
                    else -> {
                        // Call waiting, a second SIM or an untracked call: don't guess the recipient.
                        call?.blocked = true
                        phase = "BLOCKED"
                    }
                }
                render()
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (phase == "RINGING") { call?.connected = true; phase = "CONNECTED" }
                else if (phase != "CONNECTED") { call?.blocked = true; phase = "BLOCKED" }
                // OFFHOOK alone can be an outgoing call; its number is never adopted.
                render()
            }
        }
    }

    private fun clearCall() {
        call = null
        phase = "IDLE"
        removeOverlay()
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun phoneState(): Int? = runCatching {
        getSystemService(TelephonyManager::class.java)?.callState
    }.getOrNull()

    @Suppress("MissingPermission")
    private fun singleConnectedCall(): Boolean {
        val subscriptions = websiteSmsSubscriptions(this)
        if (subscriptions.isEmpty()) return false
        if (subscriptions.size == 1) return phoneState() == TelephonyManager.CALL_STATE_OFFHOOK
        // Per-SIM call state is public from API 31. Fail closed on older dual-SIM devices.
        if (Build.VERSION.SDK_INT < 31) return false
        return runCatching {
            val manager = requireNotNull(getSystemService(TelephonyManager::class.java))
            val active = subscriptions.map {
                requireNotNull(manager.createForSubscriptionId(it.subscriptionId)).callStateForSubscription
            }.filter { it != TelephonyManager.CALL_STATE_IDLE }
            active.size == 1 && active.single() == TelephonyManager.CALL_STATE_OFFHOOK
        }.getOrDefault(false)
    }

    private fun render() {
        val current = call
        val number = current?.number
        if (current == null || number == null || current.blocked || current.dismissed ||
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) {
            removeOverlay()
            return
        }
        if (!websiteCallReady(this)) { removeOverlay(); return }
        val mobile = websiteSmsNumber(number) != null
        val simReady = websiteSmsSubscription(this) != null
        val singleCall = current.connected && singleConnectedCall()
        val message = when {
            current.attempted -> receipts.summary(current.id) ?: current.feedback.ifBlank { "전송 처리됨 · 재전송하지 않습니다." }
            !mobile -> "문자를 받을 수 있는 휴대전화 번호가 아니에요."
            !current.connected -> "통화가 연결되면 홈페이지 안내 문자를 보낼 수 있어요."
            !singleCall -> "통화 회선을 명확히 확인할 수 없어 전송할 수 없습니다."
            !simReady -> "문자 발신 SIM을 전화 안내 설정에서 선택해주세요."
            current.feedback.isNotBlank() -> current.feedback
            else -> "견적 홈페이지를 문자로 보내시겠어요?"
        }
        val label = when {
            !current.attempted -> "문자 전송"
            message.startsWith("발송 완료") -> "발송 완료"
            message == "발송 중…" -> "발송 중…"
            else -> "전송 처리됨"
        }
        if (overlayCallId != current.id) {
            removeOverlay()
            val id = current.id
            overlay = WebsiteCallOverlay(this, onSend = { sendCurrent(id) }, onClose = {
                if (call?.id == id) call?.dismissed = true
                removeOverlay()
            })
            overlayCallId = id
        }
        if (overlay?.show(number, message, singleCall && mobile && simReady && !current.attempted, label) != true) {
            removeOverlay()
            if (config.problem != "작은 안내 창을 표시하지 못했습니다. 다른 앱 위 표시 권한을 확인해주세요.")
                config.problem = "작은 안내 창을 표시하지 못했습니다. 다른 앱 위 표시 권한을 확인해주세요."
        }
    }

    private fun sendCurrent(expectedId: String) {
        val current = call ?: return
        if (current.id != expectedId || !current.connected || current.blocked || current.dismissed || current.attempted) return
        if (!config.enabled || !websiteCallReady(this) ||
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false ||
            phase != "CONNECTED" || phoneState() != TelephonyManager.CALL_STATE_OFFHOOK || !singleConnectedCall() ||
            SystemClock.elapsedRealtime() - current.started > 6 * 60 * 60 * 1000L) {
            current.blocked = true
            removeOverlay()
            return
        }
        val number = websiteSmsNumber(current.number) ?: return
        val subscription = websiteSmsSubscription(this) ?: run { render(); return }
        // Latch before the API call and persist its receipt before dispatch. Never auto-retry.
        current.attempted = true
        current.feedback = try {
            WebsiteSmsSender.send(this, current.id, number, subscription, websiteCallMessage(this)).orEmpty()
        } catch (_: Exception) {
            "문자 발송을 준비하지 못했습니다. 이번 통화에서는 재전송하지 않습니다."
        }
        render()
    }

    private fun removeOverlay() {
        overlay?.hide()
        overlay = null
        overlayCallId = null
    }

    private fun promote(): Boolean = try {
        val manager = requireNotNull(getSystemService(NotificationManager::class.java))
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "통화 중 홈페이지 안내", NotificationManager.IMPORTANCE_LOW).apply {
                description = "기능을 켠 동안 전화 수신을 감지합니다. 문자 전송은 버튼을 눌렀을 때만 합니다."
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                setShowBadge(false)
            }
        )
        val settings = PendingIntent.getActivity(this, 0, Intent(this, WebsiteCallSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WebsiteCallService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("나무팜 전화 안내 켜짐")
            .setContentText("통화 중 작은 창에서 홈페이지 문자를 보낼 수 있어요.")
            .setContentIntent(settings).setOngoing(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "끄기", stop).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification)
        true
    } catch (_: Exception) {
        config.problem = "전화 안내를 실행하지 못했습니다. 알림·권한 설정을 확인하고 다시 켜주세요."
        false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            unregisterReceiver(receiver)
            config.preferences.unregisterOnSharedPreferenceChangeListener(changes)
            receipts.preferences.unregisterOnSharedPreferenceChangeListener(changes)
        }
        removeOverlay()
        mutableRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "call_website_assist"
        private const val NOTIFICATION = 3201
        private const val ACTION_DISABLE = "com.example.namufarm.DISABLE_WEBSITE_CALL"
        private val mutableRunning = MutableStateFlow(false)
        internal val running = mutableRunning.asStateFlow()

        /** Call from a visible Activity, or an allowed boot/package-replaced broadcast. */
        internal fun resumeIfEnabled(context: Context) {
            val config = WebsiteCallConfig(context)
            if (!config.enabled || mutableRunning.value) return
            if (!websiteCallReady(context)) {
                config.problem = "권한·알림·다른 앱 위 표시 설정을 확인하고 전화 안내를 다시 켜주세요."
                return
            }
            try {
                config.problem = ""
                ContextCompat.startForegroundService(context, Intent(context, WebsiteCallService::class.java))
            } catch (_: Exception) {
                config.problem = "백그라운드 실행이 제한됐습니다. 앱에서 전화 안내를 다시 켜주세요."
            }
        }
    }
}

/** Restart only an already opted-in monitor; never starts or retries an SMS. */
class WebsiteCallRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            WebsiteCallService.resumeIfEnabled(context)
    }
}
