package com.example.namufarm.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

fun smsComposeIntent(phone: String, body: String): Intent? {
    val number = normalizePhoneNumber(phone) ?: return null
    return Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", body)
}

/** Opens the user's SMS app. Sending is always an explicit action in that app. */
fun openSmsComposer(context: Context, phone: String, body: String): String? {
    val intent = smsComposeIntent(phone, body) ?: return "받는 분의 전화번호를 확인해주세요."
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "문자 앱을 찾을 수 없습니다."
    } catch (_: SecurityException) {
        "문자 앱을 열 수 없습니다. 휴대폰 설정을 확인해주세요."
    }
}

