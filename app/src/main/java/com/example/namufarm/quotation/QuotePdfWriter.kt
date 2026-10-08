package com.example.namufarm.quotation

import java.io.File
import java.io.OutputStream
import java.util.Locale

/** Portable PDF 1.4 with high-resolution JPEG pages, without device PDF JNI dependencies. */
internal object QuotePdfWriter {
    private class CountingOutput(private val target: OutputStream) : OutputStream() {
        var count = 0L; private set
        override fun write(value: Int) { target.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { target.write(bytes, offset, length); count += length }
        fun ascii(value: String) = write(value.toByteArray(Charsets.US_ASCII))
    }
    fun write(target: File, images: List<File>, imageWidth: Int, imageHeight: Int) {
        require(images.isNotEmpty())
        target.outputStream().buffered().use { raw ->
            val output = CountingOutput(raw); val offsets = mutableListOf(0L)
            output.ascii("%PDF-1.4\n"); output.write(byteArrayOf(37, -30, -29, -49, -45, 10))
            fun objectStart(id: Int) { check(id == offsets.size); offsets.add(output.count); output.ascii("$id 0 obj\n") }
            fun objectEnd() { output.ascii("\nendobj\n") }
            objectStart(1); output.ascii("<< /Type /Catalog /Pages 2 0 R >>"); objectEnd()
            objectStart(2); output.ascii("<< /Type /Pages /Count ${images.size} /Kids [${images.indices.joinToString(" ") { "${3 + it * 3} 0 R" }}] >>"); objectEnd()
            images.forEachIndexed { index, image ->
                val page = 3 + index * 3
                objectStart(page)
                output.ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Im0 ${page + 1} 0 R >> >> /Contents ${page + 2} 0 R >>")
                objectEnd()
                objectStart(page + 1)
                output.ascii("<< /Type /XObject /Subtype /Image /Width $imageWidth /Height $imageHeight /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${image.length()} >>\nstream\n")
                image.inputStream().use { it.copyTo(output) }
                output.ascii("\nendstream"); objectEnd()
                val content = "q\n595 0 0 842 0 0 cm\n/Im0 Do\nQ\n"
                objectStart(page + 2); output.ascii("<< /Length ${content.toByteArray(Charsets.US_ASCII).size} >>\nstream\n$content" + "endstream"); objectEnd()
            }
            val xref = output.count
            output.ascii("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
            offsets.drop(1).forEach { output.ascii(String.format(Locale.US, "%010d 00000 n \n", it)) }
            output.ascii("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }
    }
}
