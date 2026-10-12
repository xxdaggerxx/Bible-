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
import okio.buffer
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** Android's PDF and PNG writers for [ChapterExport]. */
actual object ExportWriter {
    private const val PDF_W = ChapterExport.PDF_W
    private const val PDF_H = ChapterExport.PDF_H
    private const val MAX_PIXELS = ChapterExport.MAX_PIXELS
    private const val FOOTER_PT = ChapterExport.FOOTER_PT
    private const val FOOTER_PX = ChapterExport.FOOTER_PX

    actual fun pdf(out: okio.Sink, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit) = out.buffer().outputStream().use { pdf(it, w, h, footer, draw) }

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

    actual fun png(out: okio.Sink, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit) = out.buffer().outputStream().use { png(it, w, h, footer, draw) }

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

    private fun paint(c: android.graphics.Canvas, w: Float, h: Float, draw: DrawScope.() -> Unit) {
        CanvasDrawScope().draw(Density(1f, 1f), LayoutDirection.Ltr, Canvas(c), Size(w, h), draw)
    }
}
