package com.biblestudy.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.biblestudy.app.ui.PEN_COLORS
import com.biblestudy.app.ui.PageTheme
import com.biblestudy.app.ui.inkOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkColourTest {
    private fun contrast(a: Int, b: Color): Float {
        val x = Color(a).luminance(); val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    @Test
    fun everyPenColourShowsOnEveryPage() {
        for (theme in PageTheme.entries) for (c in PEN_COLORS) {
            val shown = inkOn(theme, c)
            assertTrue("${theme.name} %08x -> %08x".format(c, shown), contrast(shown, theme.page) >= 4f)
        }
    }

    @Test
    fun blackInkIsLightOnTheDarkPageAndColoursKeepTheirHue() {
        val black = PEN_COLORS[0]
        assertEquals(black, inkOn(PageTheme.LIGHT, black)) // already shows: unchanged
        assertTrue(Color(inkOn(PageTheme.DARK, black)).luminance() > 0.5f)
        assertTrue(Color(inkOn(PageTheme.LIGHT, 0xFFFFFFFF.toInt())).luminance() < 0.2f) // white ink on white paper
        val red = Color(inkOn(PageTheme.DARK, 0xFFC62828.toInt()))
        assertTrue("still red: $red", red.red > red.green && red.red > red.blue)
        val orange = 0xFFEF6C00.toInt()
        assertEquals(orange, inkOn(PageTheme.DARK, orange)) // bright enough already
    }
}
