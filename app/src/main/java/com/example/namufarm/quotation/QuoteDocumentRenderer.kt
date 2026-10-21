package com.example.namufarm.quotation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One drawing model supplies both the high-resolution PDF and the MMS images. */
object QuoteDocumentRenderer {
    private const val WIDTH = 595
    private const val HEIGHT = 842
    private const val LEFT = 28f
    private const val RIGHT = 567f
    private val columns = floatArrayOf(117f, 92f, 50f, 70f, 96f, 46f, 68f)
    private fun paint(size: Float = 10f, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = size
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun money(amount: Long) = String.format(Locale.KOREA, "%,d", amount)
    private fun wrap(value: String, width: Float, p: Paint): List<String> = value.split('\n').flatMap { paragraph ->
        if (paragraph.isEmpty()) listOf("") else {
            val lines = mutableListOf<String>(); var line = ""
            paragraph.forEach { char ->
                if (line.isNotEmpty() && p.measureText(line + char) > width) { lines.add(line); line = "" }
                line += char
            }
            lines.add(line); lines
        }
    }
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, width: Float, p: Paint): Float {
        val height = p.textSize + 4f
        val lines = wrap(value, width, p)
        lines.forEachIndexed { i, line -> canvas.drawText(line, x, y + p.textSize + i * height, p) }
        return y + lines.size * height
    }
    private fun box(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        canvas.drawRect(x, y, x + width, y + height, paint().apply { style = Paint.Style.STROKE; strokeWidth = .7f })
    }
    private fun header(canvas: Canvas, doc: QuoteDocument): Float {
        canvas.drawColor(Color.WHITE)
        canvas.drawText("견  적  서", 221f, 52f, paint(24f, true))
        val date = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date(doc.issuedAt))
        canvas.drawText("NO. ${doc.number}", LEFT, 27f, paint(9f))
        canvas.drawText("견적번호 ${doc.sourceQuoteNumber}", LEFT, 64f, paint(9f))
        var yLeft = text(canvas, date, LEFT, 77f, 235f, paint(11f)) + 12f
        yLeft = text(canvas, "${doc.recipient.recipientName.ifBlank { doc.recipient.customerName }} 귀하", LEFT, yLeft, 235f, paint(12f, true)) + 5f
        yLeft = text(canvas, doc.customerPhone, LEFT, yLeft, 235f, paint(10f))
        if (doc.recipient.customerEmail.isNotBlank()) yLeft = text(canvas, doc.recipient.customerEmail, LEFT, yLeft, 235f, paint(9f))
        yLeft = text(canvas, "아래와 같이 견적합니다.", LEFT, yLeft + 13f, 235f, paint(10f))
        var yRight = 77f
        listOf("등록번호" to doc.issuer.businessNumber, "상호" to doc.issuer.businessName,
            "대표자" to doc.issuer.representative, "주소" to doc.issuer.address, "연락처" to doc.issuer.phone,
            "업태 / 종목" to listOf(doc.issuer.businessType, doc.issuer.category).filter(String::isNotBlank).joinToString(" / ").ifBlank { "—" }).forEach { (label, value) ->
            val p = paint(9f)
            val height = maxOf(22f, wrap(value, 207f, p).size * 13f + 8f)
            box(canvas, 285f, yRight, 65f, height); box(canvas, 350f, yRight, 217f, height)
            text(canvas, label, 290f, yRight + 4f, 55f, paint(8f)); text(canvas, value, 355f, yRight + 4f, 207f, p)
            yRight += height
        }
        val y = maxOf(yLeft, yRight) + 16f
        box(canvas, LEFT, y, RIGHT - LEFT, 34f)
        canvas.drawText("합계금액 (공급금액 + 세액)", LEFT + 8f, y + 21f, paint(10f, true))
        canvas.drawText("₩ ${money(doc.totalAmount)}원", 295f, y + 23f, paint(16f, true))
        var x = LEFT
        listOf("품명", "규격", "수량", "단가", "공급금액", "세액", "비고").forEachIndexed { i, title ->
            box(canvas, x, y + 45f, columns[i], 25f)
            val p = paint(9f, true); canvas.drawText(title, x + (columns[i] - p.measureText(title)) / 2, y + 62f, p)
            x += columns[i]
        }
        return y + 70f
    }
    private fun values(line: QuoteDocumentLine) = listOf(line.name, line.specification, money(line.quantity.toLong()) + line.unit, money(line.unitPrice), money(line.amount), "", line.remarks.orEmpty())
    private fun rowHeight(line: QuoteDocumentLine): Float = maxOf(29f, values(line).mapIndexed { i, value -> wrap(value, columns[i] - 10f, paint(9f)).size }.max() * 13f + 10f)
    private data class Page(val lines: List<QuoteDocumentLine>, val footer: List<String>)
    private fun pages(doc: QuoteDocument): List<Page> {
        val probe = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val top = try { header(Canvas(probe), doc) } finally { probe.recycle() }
        val capacity = 793f - top
        require(capacity >= 100f) { "공급자 또는 수신인 정보가 너무 깁니다. 내용을 줄여주세요." }
        val pages = mutableListOf<Page>(); var lines = mutableListOf<QuoteDocumentLine>(); var footer = mutableListOf<String>(); var used = 0f
        fun flush() { pages.add(Page(lines.toList(), footer.toList())); lines = mutableListOf(); footer = mutableListOf(); used = 0f }
        doc.lines.forEach { line ->
            val height = rowHeight(line); require(height <= capacity) { "품목 또는 규격이 너무 깁니다." }
            if (used + height > capacity) flush()
            lines.add(line); used += height
        }
        val note = buildList {
            add("공급금액: ${money(doc.supplyAmount)}원    세액: ${money(doc.taxAmount)}원")
            add("총 견적금액: ${money(doc.totalAmount)}원")
            add(doc.shippingNote); add("견적 유효기간: ${doc.validUntil}")
            if (doc.taxAmount > 0) add("세액은 위 합계에 별도로 표시했습니다.")
            if (doc.paymentAccount.isNotBlank()) { add("입금 계좌"); add(doc.paymentAccount) }
            if (doc.notes.isNotBlank()) { add("주요 사항"); add(doc.notes) }
        }.flatMap { wrap(it, RIGHT - LEFT - 16f, paint(10f)) }
        used += 12f
        note.forEach { value ->
            if (used + 15f > capacity) flush()
            footer.add(value); used += 15f
        }
        if (lines.isNotEmpty() || footer.isNotEmpty()) flush()
        return pages
    }
    private fun draw(canvas: Canvas, doc: QuoteDocument, page: Page, number: Int, total: Int) {
        var y = header(canvas, doc)
        page.lines.forEach { line ->
            val height = rowHeight(line); var x = LEFT
            values(line).forEachIndexed { i, value ->
                box(canvas, x, y, columns[i], height)
                text(canvas, value, x + 5f, y + 4f, columns[i] - 10f, paint(9f)); x += columns[i]
            }
            y += height
        }
        if (page.footer.isNotEmpty()) {
            y += 12f
            page.footer.forEachIndexed { i, value -> canvas.drawText(value, LEFT + 8f, y + 11f, paint(10f, i == 1 && value.startsWith("총 견적금액"))); y += 15f }
        }
        canvas.drawText("${doc.number}  ·  $number / $total", LEFT, 817f, paint(8f))
    }
    data class Files(val pdf: File, val images: List<File>)
    private fun atomic(file: File, write: (File) -> Unit) {
        val temporary = File.createTempFile("quote-", ".tmp", file.parentFile)
        try { write(temporary); check(temporary.renameTo(file)) { "견적서 파일을 저장하지 못했습니다." } }
        finally { temporary.delete() }
    }
    fun export(context: Context, source: QuoteDocument): Files {
        val doc = source.validate(); val layout = pages(doc)
        val root = File(context.cacheDir, "quote-documents").apply { mkdirs() }
        root.listFiles()?.filter { it.isDirectory && it.name != doc.id && System.currentTimeMillis() - it.lastModified() > 7 * 86400000L }?.forEach { it.deleteRecursively() }
        val folder = File(root, doc.id).apply { check(mkdirs() || isDirectory) }
        val pdfFile = File(folder, "${doc.number}.pdf")
        val printImages = layout.mapIndexed { i, page ->
            val bitmap = Bitmap.createBitmap(WIDTH * 4, HEIGHT * 4, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.scale(4f, 4f); draw(canvas, doc, page, i + 1, layout.size)
                val image = File(folder, "print-${i + 1}.jpg")
                atomic(image) { file -> file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }; image
            } finally { bitmap.recycle() }
        }
        atomic(pdfFile) { QuotePdfWriter.write(it, printImages, WIDTH * 4, HEIGHT * 4) }
        printImages.forEach { it.delete() }
        val images = layout.mapIndexed { i, page ->
            val bitmap = Bitmap.createBitmap(WIDTH * 2, HEIGHT * 2, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.scale(2f, 2f); draw(canvas, doc, page, i + 1, layout.size)
                val image = File(folder, "${doc.number}-${i + 1}.jpg")
                var bytes = byteArrayOf()
                for (quality in listOf(90, 80, 70)) {
                    bytes = ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)); output.toByteArray() }
                    if (bytes.size <= 650000) break
                }
                atomic(image) { it.writeBytes(bytes) }; image
            } finally { bitmap.recycle() }
        }
        return Files(pdfFile, images)
    }
}
