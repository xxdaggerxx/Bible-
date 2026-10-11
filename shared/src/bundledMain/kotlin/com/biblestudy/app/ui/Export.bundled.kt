package com.biblestudy.app.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import okio.buffer
import okio.use
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint

import kotlin.math.min
import kotlin.math.sqrt

/** Exports drawn with Skia on computers and iOS. */
actual object ExportWriter {
    // TODO: PDF through Skia's PDF backend; until then only pictures export here.
    actual fun pdf(out: okio.Sink, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit) {
        out.close()
        throw UnsupportedOperationException("PDF export isn't available on this device yet; export as a picture.")
    }

    actual fun png(out: okio.Sink, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit) {
        val total = h + ChapterExport.FOOTER_PX
        val k = min(2f, sqrt(ChapterExport.MAX_PIXELS / (w * total)))
        val bmp = ImageBitmap((w * k).toInt().coerceAtLeast(1), (total * k).toInt().coerceAtLeast(1))
        val canvas = Canvas(bmp)
        CanvasDrawScope().draw(Density(1f, 1f), LayoutDirection.Ltr, canvas, Size(bmp.width.toFloat(), bmp.height.toFloat())) {
            drawRect(Color.White)
            scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                val inner = this
                CanvasDrawScope().draw(Density(1f, 1f), LayoutDirection.Ltr, inner.drawContext.canvas, Size(w, h), draw)
            }
        }
        val skia = bmp.asSkiaBitmap()
        val sc = org.jetbrains.skia.Canvas(skia)
        val paint = Paint().apply { color = 0xFF6B6B6B.toInt(); isAntiAlias = true }
        sc.drawString(footer, 24f * k, (h + ChapterExport.FOOTER_PX - 16f) * k, Font(null, 15f * k), paint)
        val data = Image.makeFromBitmap(skia).encodeToData(EncodedImageFormat.PNG) ?: throw okio.IOException("Couldn't encode the picture")
        out.buffer().use { it.write(data.bytes) }
    }
}
