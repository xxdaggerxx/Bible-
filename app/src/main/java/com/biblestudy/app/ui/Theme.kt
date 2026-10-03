package com.biblestudy.app.ui

import androidx.compose.ui.graphics.Color

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
