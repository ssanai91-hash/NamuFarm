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
        val subject = "[나무팜] 요청하신 견적 안내"
        // URI fields are supported by mail apps that ignore SENDTO extras.
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(email)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email)); putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, body)
        }
    }
    fun openTextEmail(context: Context, email: String, body: String) {
        try { context.startActivity(textEmailIntent(email, body)) }
        catch (_: ActivityNotFoundException) { error("이메일 앱을 설치하거나 계정을 설정해주세요. 저장한 견적에서 다시 보낼 수 있습니다.") }
    }

    private fun body(doc: QuoteDocument) = "[${doc.issuer.businessName}] ${doc.recipient.recipientName.ifBlank { doc.recipient.customerName }}님, ${doc.number} 견적서를 보내드립니다."
    fun emailIntent(doc: QuoteDocument, uri: Uri): Intent {
        require(doc.recipient.customerEmail.isNotBlank() && validQuoteEmail(doc.recipient.customerEmail))
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"; putExtra(Intent.EXTRA_EMAIL, arrayOf(doc.recipient.customerEmail))
            putExtra(Intent.EXTRA_SUBJECT, "[${doc.issuer.businessName}] ${doc.number} 견적서")
            putExtra(Intent.EXTRA_TEXT, body(doc)); putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("견적서 PDF", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    fun mmsIntent(doc: QuoteDocument, uris: List<Uri>): Intent {
        require(uris.isNotEmpty())
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            setDataAndType(Uri.fromParts("smsto", doc.customerPhone, null), "image/jpeg")
            putExtra("address", doc.customerPhone); putExtra("sms_body", body(doc))
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newRawUri("견적서 이미지", uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    private fun uri(context: Context, file: File) = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.quote-files", file)
    @Suppress("DEPRECATION")
    fun openEmail(context: Context, doc: QuoteDocument, file: File) {
        val packages = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct()
        check(packages.isNotEmpty()) { "이메일 앱을 설치하거나 계정을 설정해주세요. PDF는 앱의 파일 공유로도 보낼 수 있습니다." }
        val intents = packages.map { emailIntent(doc, uri(context, file)).setPackage(it) }
        val chooser = Intent.createChooser(intents.first(), "견적서 이메일 작성")
        if (intents.size > 1) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
        context.startActivity(chooser)
    }
    fun openMms(context: Context, doc: QuoteDocument, files: List<File>) {
        val intent = mmsIntent(doc, files.map { uri(context, it) })
        val smsPackage = Telephony.Sms.getDefaultSmsPackage(context)
        check(!smsPackage.isNullOrBlank()) { "기본 문자 앱을 설정해주세요." }
        intent.setPackage(smsPackage)
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) {
            // Some default message apps accept address extras but not smsto with a MIME type.
            intent.setDataAndType(null, "image/jpeg"); context.startActivity(intent)
        }
    }
    fun viewPdf(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, file), "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }
    fun sharePdf(context: Context, file: File) {
        val target = uri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, target)
            clipData = ClipData.newRawUri("견적서 PDF", target); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "견적서 PDF 공유"))
    }
}
