package com.example.namufarm.quotemode

import com.example.namufarm.ui.theme.*
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.quotation.*
import com.example.namufarm.quotemode.components.NamuDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native preview and export within the original app; opening a composer never marks a send. */
@Composable
internal fun InitialFormalQuoteDialog(request: WebQuoteRequestEntity, draft: SellerQuoteDraft,
    onDismiss: () -> Unit, onPrepare: suspend () -> Unit = {}) {
    val context = LocalContext.current
    var document by remember(request.requestId, draft) { mutableStateOf<QuoteDocument?>(null) }
    var files by remember(request.requestId, draft) { mutableStateOf<QuoteDocumentRenderer.Files?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(request.requestId, draft, attempt) {
        busy = true
        error = null
        try {
            // The caller saves the edited draft locally before generating or sharing a file.
            onPrepare()
            val result = withContext(Dispatchers.IO) {
                val saved = InitialFormalQuoteStore(context).issue(request, draft)
                QuoteIssuerSettings(context).save(saved.issuer)
                saved to QuoteDocumentRenderer.export(context, saved)
            }
            document = result.first
            files = result.second
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "공식견적서를 생성하지 못했습니다." }
        finally { busy = false }
    }
    fun share(kind: String) {
        val saved = document ?: return
        val generated = files ?: return
        try {
            error = null
            when (kind) {
                "email" -> QuoteReply.openEmail(context, saved, generated.pdf)
                "sms" -> QuoteReply.openMms(context, saved, generated.images)
                "view" -> QuoteReply.viewPdf(context, generated.pdf)
                else -> QuoteReply.sharePdf(context, generated.pdf)
            }
            notice = "작성 앱에서 받는 사람과 첨부파일을 확인하고 실제로 전송해주세요."
        } catch (e: Exception) {
            error = when (kind) {
                "view" -> "PDF를 열 앱이 없습니다. 아래 미리보기 또는 PDF 공유를 이용해주세요."
                else -> e.message ?: "첨부파일을 열지 못했습니다. PDF 공유를 이용해주세요."
            }
        }
    }
    NamuDialog("공식견적서", document?.number ?: "문서 생성 중",
        onDismiss = { if (!busy) onDismiss() }, actions = {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!busy && files == null) Button(onClick = { attempt++ },
                modifier = Modifier.fillMaxWidth()) { Text("다시 생성") }
            if (files != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { share("sms") }, enabled = !busy,
                        modifier = Modifier.weight(1f)) { Text("문자 이미지") }
                    Button(onClick = { share("email") },
                        enabled = !busy && document?.recipient?.customerEmail?.isNotBlank() == true,
                        modifier = Modifier.weight(1f)) { Text("메일 PDF") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { share("view") }, enabled = !busy,
                        modifier = Modifier.weight(1f)) { Text("PDF 보기") }
                    OutlinedButton(onClick = { share("share") }, enabled = !busy,
                        modifier = Modifier.weight(1f)) { Text("PDF 공유") }
                }
            }
            TextButton(onClick = onDismiss, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("닫기") }
        }) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(request.receiptNumber.ifBlank { request.requestId }, fontWeight = FontWeight.Bold)
                Text("품목별 비고와 판매자 요청사항이 포함됩니다.",
                    style = MaterialTheme.typography.bodySmall)
                if (busy) Text("PDF와 문자용 이미지를 만들고 있습니다.")
            }
            files?.images?.let { images ->
                itemsIndexed(images, key = { _, image -> image.absolutePath }) { index, image ->
                    val bitmap = remember(image.absolutePath, attempt) {
                        BitmapFactory.decodeFile(image.absolutePath,
                            BitmapFactory.Options().apply { inSampleSize = 2 })
                    }
                    Text("${index + 1} / ${images.size}쪽", style = MaterialTheme.typography.labelMedium)
                    Surface(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        if (bitmap != null) Image(bitmap.asImageBitmap(), "공식견적서 ${index + 1}쪽",
                            modifier = Modifier.fillMaxWidth().aspectRatio(955f / 1342f))
                    }
                }
            }
        }
    }
}
