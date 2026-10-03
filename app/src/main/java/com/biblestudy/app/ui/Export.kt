package com.biblestudy.app.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Exports one chapter page, with its ink, highlights, pictures and text boxes, as a PDF (cut into
 * A4-shaped pages) or a single PNG picture (DATA-5). [draw] paints the page at its own size.
 */
object ChapterExport {
    /** PDF page width in points; pages are A4-shaped. */
    private const val PDF_W = 595f
    private const val PDF_H = 842f
    /** Keeps a picture export's memory reasonable for very long chapters. */
    private const val MAX_PIXELS = 16_000_000f

    /** Space kept at the foot of each PDF page, and under a picture, for the copyright line (BIB-10). */
    private const val FOOTER_PT = 28f
    private const val FOOTER_PX = 44f

    fun pdf(out: OutputStream, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit) {
        val k = PDF_W / w
        val sliceH = (PDF_H - FOOTER_PT) / k
        val count = ceil(h / sliceH).toInt().coerceAtLeast(1)
        val doc = PdfDocument()
        try {
            for (i in 0 until count) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PDF_W.toInt(), PDF_H.toInt(), i + 1).create())
                val c = page.canvas
                c.save()
                c.scale(k, k)
                c.translate(0f, -i * sliceH)
                c.clipRect(0f, i * sliceH, w, min(h, (i + 1) * sliceH))
                paint(c, w, h, draw)
                c.restore()
                footerText(c, "$footer \u00b7 page ${i + 1} of $count", 24f, PDF_H - 10f, 8f)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun png(out: OutputStream, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit) {
        val total = h + FOOTER_PX
        val k = min(2f, sqrt(MAX_PIXELS / (w * total)))
        val bmp = Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (total * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val c = android.graphics.Canvas(bmp)
            c.drawColor(android.graphics.Color.WHITE)
            c.scale(k, k)
            paint(c, w, h, draw)
            footerText(c, footer, 24f, h + FOOTER_PX - 16f, 15f)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bmp.recycle()
        }
    }

    private fun footerText(c: android.graphics.Canvas, text: String, x: Float, y: Float, size: Float) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6B6B6B.toInt()
            textSize = size
        }
        c.drawText(text, x, y, p)
    }

    /** The line printed on every export: the version's copyright and where it came from (BIB-10). */
    fun footerFor(version: com.biblestudy.app.data.BibleVersion, reference: String): String =
        "$reference (${version.code}) \u00b7 ${version.copyright.trimEnd('.')} \u00b7 Exported from Ink & Word"

    private fun paint(c: android.graphics.Canvas, w: Float, h: Float, draw: DrawScope.() -> Unit) {
        CanvasDrawScope().draw(Density(1f, 1f), LayoutDirection.Ltr, Canvas(c), Size(w, h), draw)
    }
}
