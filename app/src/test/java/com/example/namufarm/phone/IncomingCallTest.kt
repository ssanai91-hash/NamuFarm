package com.example.namufarm.phone

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingCallTest {
    private lateinit var context: Application
    private lateinit var store: IncomingCallStore
    private val receiver = IncomingCallReceiver()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = IncomingCallStore(context)
        store.preferences.edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.POST_NOTIFICATIONS)
    }

    @Suppress("DEPRECATION")
    private fun call(state: String, number: String? = null) {
        receiver.onReceive(context, Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(TelephonyManager.EXTRA_STATE, state)
            if (number != null) putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, number)
        })
    }

    @Test fun optInIsRequiredEvenWhenPermissionsWereAlreadyGranted() {
        call(TelephonyManager.EXTRA_STATE_RINGING, "01012345678")
        assertNull(store.latest())
        assertEquals(0, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test fun blankWithheldOutgoingAndUnrelatedEventsDoNotSelectACustomer() {
        store.setEnabled(true)
        call(TelephonyManager.EXTRA_STATE_RINGING)
        call(TelephonyManager.EXTRA_STATE_RINGING, "")
        call(TelephonyManager.EXTRA_STATE_RINGING, "-1")
        call(TelephonyManager.EXTRA_STATE_OFFHOOK, "01012345678")
        receiver.onReceive(context, Intent("other.action"))
        assertNull(store.latest())
    }

    @Test fun duplicateBroadcastsPreserveIdentityButNextCallGetsANewIdentity() {
        store.setEnabled(true)
        call(TelephonyManager.EXTRA_STATE_RINGING, "010-1234-5678")
        val first = store.latest()!!
        call(TelephonyManager.EXTRA_STATE_RINGING)
        call(TelephonyManager.EXTRA_STATE_RINGING, "01012345678")
        call(TelephonyManager.EXTRA_STATE_OFFHOOK, "01012345678")
        assertEquals(first, store.latest())
        call(TelephonyManager.EXTRA_STATE_IDLE)
        call(TelephonyManager.EXTRA_STATE_RINGING, first.number)
        assertNotEquals(first.id, store.latest()!!.id)
    }

    @Test fun revokedPhoneOrCallLogPermissionPreventsCapture() {
        store.setEnabled(true)
        shadowOf(context).denyPermissions(Manifest.permission.READ_CALL_LOG)
        call(TelephonyManager.EXTRA_STATE_RINGING, "01012345678")
        assertNull(store.latest())
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALL_LOG)
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        call(TelephonyManager.EXTRA_STATE_RINGING, "01012345678")
        assertNull(store.latest())
    }

    @Test fun notificationDenialStillKeepsTheLocalSuggestion() {
        store.setEnabled(true)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        call(TelephonyManager.EXTRA_STATE_RINGING, "01012345678")
        assertEquals("01012345678", store.latest()!!.number)
        assertEquals(0, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test fun notificationHasPrivateContentAndDistinctExplicitQuoteAndReplyIntents() {
        store.setEnabled(true)
        call(TelephonyManager.EXTRA_STATE_RINGING, "01012345678")
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        val caller = store.latest()!!
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertFalse(notification.publicVersion.extras.toString().contains(caller.number))
        assertNull(notification.fullScreenIntent)
        assertEquals(2, notification.actions.size)
        val quoteIntent = shadowOf(notification.actions[0].actionIntent).savedIntent
        val replyIntent = shadowOf(notification.actions[1].actionIntent).savedIntent
        assertEquals("com.example.namufarm.MainActivity", quoteIntent.component!!.className)
        assertEquals(CallAssistRequest(caller.id), CallAssistRequest.fromIntent(quoteIntent))
        assertEquals(CallAssistRequest(caller.id, true), CallAssistRequest.fromIntent(replyIntent))
        assertNotEquals(quoteIntent.data, replyIntent.data)
        assertFalse(quoteIntent.dataString!!.contains(caller.number))
        assertTrue(quoteIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun staleNotificationAndOldDialogNeverResolveToANewerCaller() {
        store.setEnabled(true)
        val first = store.recordRinging("01011112222")!!
        val second = store.recordRinging("01033334444")!!
        assertNull(store.find(first.id))
        store.clear(first.id)
        assertEquals(second, store.find(second.id))
    }

    @Test fun suggestionSurvivesStoreRecreationAndExpiresOnNextRead() {
        var now = 1_800_000_000_000L
        val firstStore = IncomingCallStore(context) { now }
        firstStore.setEnabled(true)
        val caller = firstStore.recordRinging("+82 (10) 1234-5678")!!
        val recreated = IncomingCallStore(context) { now }
        assertEquals(caller, recreated.latest())
        now += IncomingCallStore.RETENTION_MILLIS
        assertNull(recreated.latest())
        assertFalse(recreated.preferences.contains("number"))
        assertTrue(recreated.enabled)
    }

    @Test fun turningOffClearsCallerAndStopsFutureCapture() {
        store.setEnabled(true)
        store.recordRinging("01012345678")
        store.setEnabled(false)
        assertNull(store.latest())
        assertNull(store.recordRinging("01098765432"))
        assertFalse(store.preferences.contains("ringing_number"))
    }

    @Test fun clearingACallerAlsoRemovesTheDuplicateDetectionNumber() {
        store.setEnabled(true)
        val caller = store.recordRinging("01012345678")!!
        store.clear(caller.id)
        assertNull(store.latest())
        assertFalse(store.preferences.contains("ringing_number"))
        assertFalse(store.preferences.contains("number"))
        assertTrue(store.enabled)
    }

    @Test fun smsOpensOneRecipientWithEditableBodyAndRejectsUriInjection() {
        val intent = smsComposeIntent("+82 (10) 1234-5678", "나무팜 견적\n소나무 10주 120,000원")!!
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto", intent.data!!.scheme)
        assertEquals("+821012345678", intent.data!!.schemeSpecificPart)
        assertEquals("나무팜 견적\n소나무 10주 120,000원", intent.getStringExtra("sms_body"))
        listOf("", "발신번호표시제한", "-1", "01011112222;01033334444", "01012345678?body=other", "tel:01012345678", "*123#").forEach {
            assertNull(it, smsComposeIntent(it, "body"))
        }
    }

    @Test fun externalOrMalformedLaunchIntentsAreIgnored() {
        assertNull(CallAssistRequest.fromIntent(Intent(Intent.ACTION_MAIN)))
        assertNull(CallAssistRequest.fromIntent(Intent(CallAssistRequest.ACTION)))
        assertNull(CallAssistRequest.fromIntent(Intent(CallAssistRequest.ACTION, android.net.Uri.parse("namufarm://incoming-call/id/other"))))
    }

    @Test @Config(sdk = [24]) fun preNotificationPermissionAndroidAlsoReceivesAndNotifies() {
        store.setEnabled(true)
        call(TelephonyManager.EXTRA_STATE_RINGING, "01012345678")
        assertNotNull(store.latest())
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }
}
