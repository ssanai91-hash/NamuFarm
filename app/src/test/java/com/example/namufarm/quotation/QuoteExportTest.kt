package com.example.namufarm.quotation

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuoteExportTest {
    @Test fun actualPdfAndJpegRenderSameSinglePageDocument() {
        val files = QuoteDocumentRenderer.export(RuntimeEnvironment.getApplication(), QuoteTestData.document())
        assertEquals("%PDF", files.pdf.inputStream().use { String(it.readNBytes(4)) })
        assertEquals(1, files.images.size)
        PdfRenderer(ParcelFileDescriptor.open(files.pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { assertEquals(files.images.size, it.pageCount) }
        val bitmap = BitmapFactory.decodeFile(files.images.single().path)
        assertEquals(1190, bitmap.width); assertEquals(1684, bitmap.height); bitmap.recycle()
        val output = File("build/outputs/quote-previews").apply { mkdirs() }
        files.pdf.copyTo(File(output, "quote-example.pdf"), overwrite = true)
        files.images.single().copyTo(File(output, "quote-example.jpg"), overwrite = true)
    }
    @Test fun manyItemsPaginateWithAnImageForEveryPdfPage() {
        val files = QuoteDocumentRenderer.export(RuntimeEnvironment.getApplication(), QuoteTestData.document(40))
        assertTrue(files.images.size > 1)
        PdfRenderer(ParcelFileDescriptor.open(files.pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { assertEquals(files.images.size, it.pageCount) }
        assertTrue(files.images.all { it.length() > 1000 })
        val output = File("build/outputs/quote-previews").apply { mkdirs() }
        files.pdf.copyTo(File(output, "quote-multipage.pdf"), overwrite = true)
        files.images.last().copyTo(File(output, "quote-multipage-last.jpg"), overwrite = true)
    }
    @Test fun attachmentIntentsKeepRecipientMimeAndTemporaryReadPermissions() {
        val doc = QuoteTestData.document(); val first = Uri.parse("content://test/quote.pdf")
        val email = QuoteReply.emailIntent(doc, first)
        assertEquals("application/pdf", email.type); assertArrayEquals(arrayOf("buyer@example.com"), email.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(first, email.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(email.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val mms = QuoteReply.mmsIntent(doc, listOf(first, Uri.parse("content://test/page2.jpg")))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, mms.action); assertEquals("01012345678", mms.getStringExtra("address"))
        assertEquals("smsto:01012345678", mms.data.toString()); assertEquals(2, mms.clipData!!.itemCount)
    }
}
