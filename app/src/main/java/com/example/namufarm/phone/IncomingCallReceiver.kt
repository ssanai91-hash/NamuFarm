package com.example.namufarm.phone

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** Manifest receiver: works without an Activity instance when the platform delivers PHONE_STATE. */
class IncomingCallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val store = IncomingCallStore(context)
        if (!store.enabled || !hasCallerPermissions(context)) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_IDLE -> store.endCall()
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val caller = store.recordRinging(intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) ?: return
                IncomingCallNotifications.show(context, caller)
            }
            // OFFHOOK can be an outgoing call. It must never select a customer.
        }
    }
}

fun hasCallerPermissions(context: Context): Boolean =
    listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
