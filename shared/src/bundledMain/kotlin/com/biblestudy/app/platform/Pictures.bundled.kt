package com.biblestudy.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.roundToInt

/** Pictures on computers and iOS, with Skia (which draws Compose there). */
actual object Pictures {
    private fun scaled(img: Image, maxSide: Int): Image {
        val scale = maxSide.toFloat() / max(img.width, img.height).toFloat()
        if (scale >= 1f) return img
        val w = (img.width * scale).roundToInt().coerceAtLeast(1)
        val h = (img.height * scale).roundToInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(img, Rect.makeWH(w.toFloat(), h.toFloat()))
        return surface.makeImageSnapshot()
    }

    actual fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? = runCatching {
        scaled(Image.makeFromEncoded(bytes), maxSide).toComposeImageBitmap()
    }.getOrNull()

    actual fun prepare(bytes: ByteArray, maxSide: Int): Triple<ByteArray, Boolean, Float>? = runCatching {
        val img = scaled(Image.makeFromEncoded(bytes), maxSide)
        val png = !img.isOpaque
        val data = img.encodeToData(if (png) EncodedImageFormat.PNG else EncodedImageFormat.JPEG, 88) ?: return null
        Triple(data.bytes, png, img.height.toFloat() / img.width.toFloat())
    }.getOrNull()
}
