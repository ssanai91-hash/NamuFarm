package com.example.namufarm.quotation

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.FileProvider
import com.example.namufarm.BuildConfig
import com.google.gson.Gson
import java.io.File

class QuoteIssuerSettings(context: Context) {
    private val preferences = context.getSharedPreferences("quote_issuer", Context.MODE_PRIVATE)
    fun load(): QuoteIssuer = runCatching { Gson().fromJson(preferences.getString("issuer", ""), QuoteIssuer::class.java) }.getOrNull() ?: QuoteIssuer()
    fun save(issuer: QuoteIssuer) { check(preferences.edit().putString("issuer", Gson().toJson(issuer.checked())).commit()) { "공급자 정보를 저장하지 못했습니다." } }
}

object QuoteReply {
    fun textEmailIntent(email: String, body: String): Intent {
        require(email.isNotBlank() && validQuoteEmail(email)) { "이메일 주소를 확인해주세요." }
        require(body.isNotBlank()) { "견적 내용을 확인해주세요." }
        val subject = "묘목 견적 안내"
        // URI fields are supported by mail apps that ignore SENDTO extras.
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(email)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email)); putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, body)
        }
    }
    fun openTextEmail(context: Context, email: String, body: String) {
        try { context.startActivity(textEmailIntent(email, body)) }
        catch (_: ActivityNotFoundException) { error("이메일 앱을 설치하거나 계정을 설정해주세요. 저장한 견적에서 다시 보낼 수 있습니다.") }
    }

}
