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

    fun pdf(out: OutputStream, w: Float, h: Float, draw: DrawScope.() -> Unit) {
        val k = PDF_W / w
        val sliceH = PDF_H / k
        val count = ceil(h / sliceH).toInt().coerceAtLeast(1)
        val doc = PdfDocument()
        try {
            for (i in 0 until count) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PDF_W.toInt(), PDF_H.toInt(), i + 1).create())
                val c = page.canvas
                c.scale(k, k)
                c.translate(0f, -i * sliceH)
                c.clipRect(0f, i * sliceH, w, min(h, (i + 1) * sliceH))
                paint(c, w, h, draw)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun png(out: OutputStream, w: Float, h: Float, draw: DrawScope.() -> Unit) {
        val k = min(2f, sqrt(MAX_PIXELS / (w * h)))
        val bmp = Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val c = android.graphics.Canvas(bmp)
            c.scale(k, k)
            paint(c, w, h, draw)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bmp.recycle()
        }
    }

    private fun paint(c: android.graphics.Canvas, w: Float, h: Float, draw: DrawScope.() -> Unit) {
        CanvasDrawScope().draw(Density(1f, 1f), LayoutDirection.Ltr, Canvas(c), Size(w, h), draw)
    }
}
