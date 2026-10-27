// workspace.py render_official_quote_image와 동일하게 유지
package com.example.namufarm.quotation

import android.content.Context
import android.graphics.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Napi's measured form and page plan. Totals are read from the issued document. */
object QuoteDocumentRenderer {
    internal const val WIDTH = 955
    internal const val HEIGHT = 1342
    internal val COLUMN_WIDTHS = listOf(207.5f, 207.5f, 64f, 78f, 151f, 52f, 124f)
    private val ink = Color.rgb(34, 34, 34)
    private val seal = Color.rgb(167, 25, 25)
    private fun font(size: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; textSize = size.toFloat()
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun money(n: Long) = String.format(Locale.KOREA, "%,d", n)
    private fun height(p: Paint) = p.fontMetrics.descent - p.fontMetrics.ascent
    internal fun wrappedLines(text: String, width: Float, size: Int): List<String> {
        val p = font(size); val result = mutableListOf<String>(); var current = ""
        text.replace('\t', ' ').codePoints().toArray().forEach { code ->
            val char = String(Character.toChars(code))
            if (char == "\n") { result += current; current = "" }
            else {
                if (current.isNotEmpty() && p.measureText(current + char) > width) { result += current; current = "" }
                current += char
            }
        }
        return result + current
    }
    internal data class Row(val values: List<String>, val height: Int)
    internal data class Page(val rows: List<Row>, val note: String, val continuation: String = "",
        val number: Int = 1, val count: Int = 1, val noteContinues: Boolean = false)
    private fun isShipping(line: QuoteDocumentLine) = line.unit == "건" &&
        line.specification == "선불" && line.name in setOf("택배비", "운반비")
    internal fun pagePlan(doc: QuoteDocument): List<Page> {
        doc.validate()
        val groups = mutableListOf<List<Row>>(); var current = mutableListOf<Row>(); var used = 0
        doc.lines.filterNot { isShipping(it) && it.unitPrice == 0L }.forEach { line ->
            val shipping = isShipping(line)
            val values = listOf(if (shipping) "운반비" else line.name, line.specification, money(line.quantity.toLong()),
                money(line.unitPrice), money(line.amount), "", if (shipping) "택배비" else line.remarks.orEmpty())
            val rowHeight = maxOf(32, listOf(0 to 15, 1 to 12, 6 to 12).maxOf { (i, size) ->
                (wrappedLines(values[i], COLUMN_WIDTHS[i] - 8, size).size * height(font(size)) + 8).toInt()
            })
            require(rowHeight <= 743) { "한 품목의 이름·규격·비고가 너무 깁니다. 내용을 줄여주세요." }
            if (current.isNotEmpty() && used + rowHeight > 743) { groups += current.toList(); current = mutableListOf(); used = 0 }
            current += Row(values, rowHeight); used += rowHeight
        }
        groups += current.toList()
        val notes = wrappedLines("요청사항 : " + doc.notes.trim().ifBlank { "없음" }, 664f, 12)
        val footerCount = maxOf(1, (56 / height(font(12))).toInt())
        val first = notes.take(footerCount).joinToString("\n"); val rest = notes.drop(footerCount)
        val pages = groups.map { Page(it, first) }.toMutableList()
        rest.chunked(maxOf(1, (690 / height(font(15))).toInt())).forEach {
            pages += Page(emptyList(), "요청사항은 위 본문을 확인해주세요.", it.joinToString("\n"))
        }
        return pages.mapIndexed { i, p -> p.copy(number = i + 1, count = pages.size, noteContinues = rest.isNotEmpty()) }
    }
    private enum class Align { LEFT, CENTER, RIGHT }
    private class Form(val c: Canvas) {
        fun line(x: Float, y: Float, xx: Float, yy: Float, width: Float = 1f, color: Int = ink) {
            c.drawLine(x.toInt().toFloat(), y.toInt().toFloat(), xx.toInt().toFloat(), yy.toInt().toFloat(), font(12).apply { strokeWidth = width; this.color = color })
        }
        fun box(x: Float, y: Float, xx: Float, yy: Float, width: Float = 1f) {
            c.drawRect(x.toInt().toFloat(), y.toInt().toFloat(), x.toInt() + (xx - x).toInt().toFloat(), y.toInt() + (yy - y).toInt().toFloat(),
                font(12).apply { style = Paint.Style.STROKE; strokeWidth = width })
        }
        fun text(x: Float, y: Float, w: Float, h: Float, value: String, size: Int = 16,
            bold: Boolean = false, align: Align = Align.CENTER, minimum: Int = 9, wrap: Boolean = false,
            top: Boolean = false, color: Int = ink, spacing: Float = 0f) {
            val p = font(size, bold).apply { this.color = color; letterSpacing = spacing / size }
            while (!wrap && p.textSize > minimum) {
                if (value.split('\n').maxOf { p.measureText(it) } <= maxOf(2f, w - 8) && height(p) <= maxOf(2f, h - 3)) break
                p.textSize -= 1f
            }
            val lines = if (wrap) wrappedLines(value, maxOf(1f, w - 8), size) else value.split('\n')
            val lineHeight = height(p); val start = if (top) y + 1 else y + 1 + (h - 2 - lines.size * lineHeight) / 2
            c.save(); c.clipRect(x + 4, y + 1, x + w - 4, y + h - 1)
            lines.forEachIndexed { i, s ->
                val left = when (align) { Align.LEFT -> x + 4; Align.CENTER -> x + w / 2 - p.measureText(s) / 2; Align.RIGHT -> x + w - 4 - p.measureText(s) }
                c.drawText(s, left, start - p.fontMetrics.ascent + i * lineHeight, p)
            }
            c.restore()
        }
    }
    private fun phone(raw: String): String {
        val d = raw.filter(Char::isDigit)
        return when (d.length) { 11 -> "${d.take(3)}-${d.substring(3, 7)}-${d.takeLast(4)}";
            10 -> "${d.take(3)}-${d.substring(3, 6)}-${d.takeLast(4)}"; else -> raw }
    }
    internal fun renderPage(doc: QuoteDocument, page: Page, scale: Int, stamp: Bitmap? = null): Bitmap {
        require(scale in 1..3)
        val bitmap = Bitmap.createBitmap(WIDTH * scale, HEIGHT * scale, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap); c.scale(scale.toFloat(), scale.toFloat()); c.drawColor(Color.WHITE); val f = Form(c)
        f.text(26f, 13f, 205f, 24f, "NO. ${doc.sourceQuoteNumber}", 12, align = Align.LEFT); f.line(27f, 35f, 226f, 35f)
        // Compensate the fitted-text inset to match the title's direct QRectF(292,38,298,44).
        f.text(288f, 37f, 306f, 46f, "견 적 서", 39, minimum = 39, spacing = 15f)
        f.line(294f, 82f, 587f, 82f, 2f, Color.rgb(85, 85, 85))
        val date = SimpleDateFormat("yyyy년  M월  d일", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date(doc.issuedAt))
        f.text(42f, 99f, 250f, 31f, date, 17); f.line(27f, 132f, 293f, 132f)
        f.text(120f, 132f, 220f, 23f, doc.recipient.customerName, 16, align = Align.RIGHT)
        f.text(52f, 153f, 286f, 24f, "${phone(doc.customerPhone)} 귀하", 16, align = Align.RIGHT); f.line(27f, 177f, 343f, 177f)
        f.text(27f, 184f, 355f, 31f, "아래와같이 견적 합니다.", 16, align = Align.LEFT)
        f.box(434f, 109f, 910f, 231f, 2f); f.line(468f, 109f, 468f, 231f); f.line(587f, 109f, 587f, 231f)
        listOf(132f, 155f, 177f, 208f).forEach { f.line(468f, it, 910f, it) }
        f.text(434f, 109f, 34f, 122f, "공\n급\n자", 17, true)
        f.text(468f, 109f, 119f, 23f, "등록번호", 15, true); f.text(587f, 109f, 323f, 23f, doc.issuer.businessNumber, 15)
        f.line(765f, 132f, 765f, 155f); f.line(835f, 132f, 835f, 155f)
        f.text(468f, 132f, 119f, 23f, "상   호", 15, true); f.text(587f, 132f, 178f, 23f, doc.issuer.businessName, 15)
        f.text(765f, 132f, 70f, 23f, "성명", 14, true); f.text(835f, 132f, 75f, 23f, doc.issuer.representative, 15, minimum = 11)
        f.text(468f, 155f, 119f, 22f, "사업장주소", 15, true); f.text(587f, 155f, 323f, 22f, doc.issuer.address, 15, minimum = 11)
        f.line(712f, 177f, 712f, 208f); f.line(765f, 177f, 765f, 208f)
        f.text(468f, 177f, 119f, 31f, "업   태", 15, true); f.text(587f, 177f, 125f, 31f, doc.issuer.businessType.ifBlank { "농업,도소매" }, 15, minimum = 11)
        f.text(712f, 177f, 53f, 31f, "종목", 14, true); f.text(765f, 177f, 145f, 31f, doc.issuer.category.ifBlank { "묘목,화훼" }, 15, minimum = 11)
        f.text(468f, 208f, 119f, 23f, "연락처", 15, true); f.text(587f, 208f, 323f, 23f, "T:(043)732-0980 F:732-0981", 14, minimum = 11)
        if (stamp != null) c.drawBitmap(stamp, null, RectF(850f, 82f, 910f, 138f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        else {
            c.drawOval(RectF(854f, 84f, 906f, 136f), font(10).apply { color = seal; style = Paint.Style.STROKE; strokeWidth = 2f })
            f.text(857f, 86f, 46f, 48f, "오\n진\n웅", 10, true, minimum = 10, color = seal)
        }
        f.box(26f, 231f, 910f, 280f, 2f); f.line(292f, 231f, 292f, 280f)
        f.text(29f, 231f, 263f, 49f, "합계금액(공급가액+세액)", 20, true, Align.LEFT, 14)
        f.text(303f, 231f, 607f, 49f, "₩${money(doc.totalAmount)}원", 19, true, Align.LEFT)
        val xs = mutableListOf(26f); COLUMN_WIDTHS.forEach { xs += xs.last() + it }
        f.box(26f, 280f, 910f, 1087f, 2f)
        if (page.continuation.isNotEmpty()) {
            f.text(26f, 280f, 884f, 32f, "요청사항 (계속)", 16, true)
            f.text(38f, 324f, 860f, 719f, page.continuation, 15, align = Align.LEFT, minimum = 15, wrap = true, top = true)
        } else {
            xs.drop(1).dropLast(1).forEach { f.line(it, 280f, it, 1087f) }; f.line(26f, 312f, 910f, 312f)
            listOf("품   명", "규격", "수량", "단   가", "공급가액", "세   액", "비   고").forEachIndexed { i, s -> f.text(xs[i], 280f, COLUMN_WIDTHS[i], 32f, s, 16, minimum = 12) }
            var y = 312f
            page.rows.forEach { row ->
                row.values.forEachIndexed { i, s -> f.text(xs[i], y, COLUMN_WIDTHS[i], row.height.toFloat(), s,
                    if (i == 0 || i in 2..4) 15 else 12,
                    align = if (i in 3..5) Align.RIGHT else if (i == 0 || i == 6) Align.LEFT else Align.CENTER,
                    minimum = if (i == 1 || i == 6) 12 else 10, wrap = i in setOf(0, 1, 6)) }
                y += row.height; f.line(26f, y, 910f, y)
            }
            while (y + 32 < 1055) { y += 32; f.line(26f, y, 910f, y) }; f.line(26f, 1055f, 910f, 1055f)
        }
        f.text(xs[0], 1055f, COLUMN_WIDTHS[0], 32f, "계", 16)
        f.text(xs[4], 1055f, COLUMN_WIDTHS[4], 32f, money(doc.supplyAmount), 15, align = Align.RIGHT)
        if (doc.taxAmount != 0L) f.text(xs[5], 1055f, COLUMN_WIDTHS[5], 32f, money(doc.taxAmount), 15, align = Align.RIGHT)
        f.box(26f, 1087f, 910f, 1268f, 2f); f.line(226f, 1087f, 226f, 1268f)
        f.text(26f, 1087f, 200f, 181f, "중요사항", 18)
        val payment = when (val account = doc.paymentAccount.trim()) {
            "", "농협 328-12-035671 / 오진웅" -> "농협 328-12-035671 오진웅"
            else -> account
        }
        f.text(228f, 1091f, 672f, 25f, "1. $payment", 14, align = Align.LEFT)
        f.text(228f, 1119f, 672f, 25f, "2. 견적후 일정기간후 금액변동 있을수 있음.", 14, align = Align.LEFT)
        f.text(228f, 1146f, 672f, 28f, "3. 계산서 또는 현금영수증발행은 정상가격 처리후 7일이내 발행가능함.", 13, align = Align.LEFT, minimum = 10)
        val prepaid = doc.shippingPrepaid ?: doc.lines.any(::isShipping)
        val fee = doc.quotedShippingFee ?: doc.lines.firstOrNull(::isShipping)?.unitPrice ?: 0L
        val terms = if (prepaid) "택배비 선불 ${money(fee)}원 (총금액 포함)" else "택배비 후불 / 선불 시 ${money(fee)}원"
        f.text(228f, 1177f, 672f, 24f, "4. $terms", 13, align = Align.LEFT, minimum = 11)
        f.text(228f, 1205f, 672f, 60f, page.note, 12, align = Align.LEFT, minimum = 12, wrap = true, top = true)
        if (page.count > 1) f.text(26f, 1280f, 884f, 28f, "${page.number} / ${page.count}쪽" +
            if (page.noteContinues && page.continuation.isEmpty()) " · 긴 요청사항은 뒤쪽에 계속됩니다." else "", 13)
        return bitmap
    }
    data class Files(val pdf: File, val images: List<File>)
    private fun atomic(file: File, write: (File) -> Unit) {
        val temp = File.createTempFile("quote-", ".tmp", file.parentFile)
        try { write(temp); check(temp.renameTo(file)) { "견적서 파일을 저장하지 못했습니다." } } finally { temp.delete() }
    }
    fun export(context: Context, source: QuoteDocument): Files {
        val doc = source.validate(); val pages = pagePlan(doc)
        val root = File(context.cacheDir, "quote-documents").apply { mkdirs() }
        root.listFiles()?.filter { it.isDirectory && it.name != doc.id && System.currentTimeMillis() - it.lastModified() > 7 * 86400000L }?.forEach { it.deleteRecursively() }
        val folder = File(root, doc.id).apply { check(mkdirs() || isDirectory) }
        val stamp = runCatching { context.assets.open("quote_stamp.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        val prints = mutableListOf<File>()
        try {
            pages.forEachIndexed { i, p ->
                val b = renderPage(doc, p, 3, stamp)
                try { File(folder, "print-${i + 1}.jpg").also { file -> atomic(file) { it.outputStream().use { out -> check(b.compress(Bitmap.CompressFormat.JPEG, 95, out)) } }; prints += file } }
                finally { b.recycle() }
            }
            val pdf = File(folder, "${doc.number}.pdf"); atomic(pdf) { QuotePdfWriter.write(it, prints, WIDTH * 3, HEIGHT * 3) }
            val images = pages.mapIndexed { i, p ->
                val b = renderPage(doc, p, 2, stamp)
                try {
                    var bytes = byteArrayOf()
                    for (quality in listOf(90, 80, 70, 60, 50, 40, 30, 20, 10)) {
                        bytes = ByteArrayOutputStream().use { out -> check(b.compress(Bitmap.CompressFormat.JPEG, quality, out)); out.toByteArray() }
                        if (bytes.size <= 650000) break
                    }
                    check(bytes.size <= 650000) { "문자 이미지가 650KB를 넘습니다. PDF 공유를 이용해주세요." }
                    File(folder, "${doc.number}-${i + 1}.jpg").also { file -> atomic(file) { it.writeBytes(bytes) } }
                } finally { b.recycle() }
            }
            return Files(pdf, images)
        } finally { prints.forEach { it.delete() }; stamp?.recycle() }
    }
}
