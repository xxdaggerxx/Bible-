package com.biblestudy.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import okio.Buffer
import okio.buffer
import okio.use
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Exports drawn with Skia on computers and iOS. A PDF's pages are pictures of the chapter (at
 * about 150 dots per inch), cut into A4-shaped pages as on Android.
 */
actual object ExportWriter {
    /** Pixels per PDF point in a PDF's page pictures. */
    private const val PDF_SCALE = 2f

    actual fun pdf(out: okio.Sink, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit) {
        val k = ChapterExport.PDF_W / w
        val sliceH = (ChapterExport.PDF_H - ChapterExport.FOOTER_PT) / k
        val count = ceil(h / sliceH).toInt().coerceAtLeast(1)
        val pw = (ChapterExport.PDF_W * PDF_SCALE).toInt()
        val ph = (ChapterExport.PDF_H * PDF_SCALE).toInt()
        val pages = (0 until count).map { i ->
            val jpeg = picture(pw, ph, JPEG) { s ->
                scale(k * PDF_SCALE, k * PDF_SCALE, pivot = Offset.Zero) {
                    translate(0f, -i * sliceH) {
                        clipRect(0f, i * sliceH, w, min(h, (i + 1) * sliceH)) { paint(this, w, h, draw) }
                    }
                }
                s.drawString(
                    "$footer · page ${i + 1} of $count", 24f * PDF_SCALE, (ChapterExport.PDF_H - 10f) * PDF_SCALE,
                    Font(null, 8f * PDF_SCALE), grey,
                )
            }
            Triple(pw, ph, jpeg)
        }
        out.buffer().use { writePdf(it, pages) }
    }

    actual fun png(out: okio.Sink, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit) {
        val total = h + ChapterExport.FOOTER_PX
        val k = min(2f, sqrt(ChapterExport.MAX_PIXELS / (w * total)))
        val data = picture((w * k).toInt().coerceAtLeast(1), (total * k).toInt().coerceAtLeast(1), PNG) { s ->
            scale(k, k, pivot = Offset.Zero) { paint(this, w, h, draw) }
            s.drawString(footer, 24f * k, (h + ChapterExport.FOOTER_PX - 16f) * k, Font(null, 15f * k), grey)
        }
        out.buffer().use { it.write(data) }
    }

    private const val JPEG = 0
    private const val PNG = 1
    private val grey = Paint().apply { color = 0xFF6B6B6B.toInt(); isAntiAlias = true }

    /** Draws a white picture with [content] (Compose drawing, then Skia for the footer) and encodes it. */
    private fun picture(pw: Int, ph: Int, format: Int, content: DrawScope.(org.jetbrains.skia.Canvas) -> Unit): ByteArray {
        val bmp = ImageBitmap(pw, ph)
        val skia = bmp.asSkiaBitmap()
        val sc = org.jetbrains.skia.Canvas(skia)
        CanvasDrawScope().draw(Density(1f, 1f), LayoutDirection.Ltr, Canvas(bmp), Size(pw.toFloat(), ph.toFloat())) {
            drawRect(Color.White)
            content(sc)
        }
        val image = Image.makeFromBitmap(skia)
        val data = if (format == JPEG) image.encodeToData(EncodedImageFormat.JPEG, 88) else image.encodeToData(EncodedImageFormat.PNG)
        return data?.bytes ?: throw okio.IOException("Couldn't encode the picture")
    }

    /** The page at its own size inside [scope]'s current transform. */
    private fun paint(scope: DrawScope, w: Float, h: Float, draw: DrawScope.() -> Unit) {
        CanvasDrawScope().draw(Density(1f, 1f), LayoutDirection.Ltr, scope.drawContext.canvas, Size(w, h), draw)
    }

    /** A PDF whose pages are A4 and each shows one JPEG picture (width, height, bytes). */
    private fun writePdf(out: okio.BufferedSink, pages: List<Triple<Int, Int, ByteArray>>) {
        val header = byteArrayOf(*"%PDF-1.4\n%".encodeToByteArray(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte())
        val body = Buffer()
        val offsets = ArrayList<Long>()
        fun obj(text: String, stream: ByteArray? = null) {
            offsets += header.size + body.size
            body.writeUtf8("${offsets.size} 0 obj\n").writeUtf8(text)
            if (stream != null) body.writeUtf8("\nstream\n").write(stream).writeUtf8("\nendstream")
            body.writeUtf8("\nendobj\n")
        }
        // 1: catalog, 2: page tree, then three objects for each page: page, its drawing, its picture.
        val pw = ChapterExport.PDF_W.toInt()
        val ph = ChapterExport.PDF_H.toInt()
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Count ${pages.size} /Kids [${pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }}] >>")
        pages.forEachIndexed { i, (w, h, jpeg) ->
            val n = 3 + i * 3
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] /Resources << /XObject << /Im0 ${n + 2} 0 R >> >> /Contents ${n + 1} 0 R >>")
            val draw = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q".encodeToByteArray()
            obj("<< /Length ${draw.size} >>", draw)
            obj("<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>", jpeg)
        }
        val xref = header.size + body.size
        out.write(header)
        out.writeAll(body)
        out.writeUtf8("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.writeUtf8(o.toString().padStart(10, '0') + " 00000 n \n")
        out.writeUtf8("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }
}
