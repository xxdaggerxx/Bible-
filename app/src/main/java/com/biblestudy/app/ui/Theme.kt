package com.biblestudy.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

enum class PageTheme(
    val label: String,
    val page: Color,
    val surround: Color,
    val text: Color,
    val margin: Color,
    val rule: Color,
    val dark: Boolean,
) {
    LIGHT("Light", Color(0xFFFFFFFF), Color(0xFFE4E4E4), Color(0xFF1B1B1B), Color(0xFFF8F6F1), Color(0xFFDDD6C8), false),
    SEPIA("Sepia", Color(0xFFF6EEDC), Color(0xFFD9CFBA), Color(0xFF3B2F22), Color(0xFFEFE5CE), Color(0xFFD2C3A3), false),
    DARK("Dark", Color(0xFF1F1F1F), Color(0xFF111111), Color(0xFFDADADA), Color(0xFF272727), Color(0xFF3A3A3A), true),
}

val PEN_COLORS = intArrayOf(
    0xFF1B1B1B.toInt(), 0xFF1E4FA8.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(),
    0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(), 0xFF6D4C41.toInt(), 0xFFFFFFFF.toInt(),
)

val HIGHLIGHT_COLORS = intArrayOf(
    0xFFFFE600.toInt(), 0xFF8BC34A.toInt(), 0xFFFF80AB.toInt(),
    0xFF4FC3F7.toInt(), 0xFFFFB74D.toInt(), 0xFFB39DDB.toInt(),
)

val LAYER_COLORS = intArrayOf(
    0xFF7A5C2E.toInt(), 0xFF1E4FA8.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(),
    0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(), 0xFF00838F.toInt(),
)

val PEN_SIZES = floatArrayOf(2f, 3.5f, 6f)
val HIGHLIGHT_SIZES = floatArrayOf(16f, 24f, 34f)
const val HIGHLIGHT_ALPHA = 0.38f
/** Highlights from another translation, shown over whole verses (HL-10). */
const val CROSS_HIGHLIGHT_ALPHA = 0.2f

/**
 * An ink colour as it's shown on [theme]'s page (INK-17): a colour too close to the page to see
 * (black ink on the dark page, white on a light one, navy or purple on dark) is lightened on a dark
 * page or darkened on a light one, keeping its hue, until it stands out. Strokes keep the colour they
 * were written in, so they show right on every page and every device.
 */
fun inkOn(theme: PageTheme, color: Int): Int {
    val key = theme.ordinal.toLong() shl 32 or (color.toLong() and 0xffffffffL)
    INK_SHOWN[key]?.let { return it }
    val page = theme.page
    val target = if (theme.dark) Color.White else Color(0xFF111111)
    val alpha = (color ushr 24) and 0xff
    val solid = Color(color or (0xff shl 24))
    // Black or white ink that doesn't show writes in the page's own text colour, like the print.
    val grey = maxOf(solid.red, solid.green, solid.blue) - minOf(solid.red, solid.green, solid.blue) < 0.12f
    if (grey && contrast(solid, page) < INK_MIN_CONTRAST) {
        return ((theme.text.toArgb() and 0x00ffffff) or (alpha shl 24)).also { INK_SHOWN[key] = it }
    }
    var shown = solid
    var t = 0f
    while (contrast(shown, page) < INK_MIN_CONTRAST && t < 1f) {
        t += 0.05f
        shown = androidx.compose.ui.graphics.lerp(solid, target, t)
    }
    val out = (shown.toArgb() and 0x00ffffff) or (alpha shl 24)
    INK_SHOWN[key] = out
    return out
}

/** How clearly ink must stand out from the page (WCAG contrast). */
private const val INK_MIN_CONTRAST = 4f
private val INK_SHOWN = java.util.concurrent.ConcurrentHashMap<Long, Int>()

private fun contrast(a: Color, b: Color): Float {
    val x = a.luminance(); val y = b.luminance()
    return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
}
