package com.biblestudy.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.biblestudy.app.model.ChapterData
import com.biblestudy.app.model.Region

/**
 * Study Layout constants, in fixed "page units".
 *
 * Text is always laid out at the same width and size, so line breaks never change on any
 * device. Screens only zoom the page; that is what keeps ink on the words.
 */
object Page {
    const val COL_PAD = 56f
    const val TEXT_W = 640f
    const val COL_W = TEXT_W + COL_PAD * 2
    const val MARGIN_W = 440f // default margin width
    const val MARGIN_MIN = 220f
    const val MARGIN_MAX = 1000f
    const val TITLE_TOP = 36f
    const val TEXT_TOP = 130f
    const val BOTTOM = 260f
    const val FONT = 22f
    const val LINE = 42f // generous line spacing leaves room to write between lines
}

/** The fixed layout of one chapter of one version. */
class ChapterLayout(
    val version: String,
    val book: Int,
    val chapter: Int,
    val title: TextLayoutResult,
    val text: TextLayoutResult,
    private val verseStarts: IntArray,
    private val verseNumbers: IntArray,
) {
    val textLength = text.layoutInput.text.length
    private val verseTops = FloatArray(verseStarts.size) { i ->
        Page.TEXT_TOP + text.getLineTop(text.getLineForOffset(verseStarts[i]))
    }

    val verses: IntArray get() = verseNumbers

    /**
     * Top of a verse's first line. A verse this version leaves out (e.g. Matthew 17:21 in modern
     * translations) uses the nearest verse before it, so margin notes stay close to where they belong.
     */
    fun verseTop(verse: Int): Float {
        if (verseTops.isEmpty()) return Page.TEXT_TOP
        var i = 0
        while (i + 1 < verseNumbers.size && verseNumbers[i + 1] <= verse) i++
        return verseTops[i]
    }

    /** The verse whose first line starts at or above page y. */
    fun verseAtY(y: Float): Int {
        var result = verseNumbers.firstOrNull() ?: 1
        for (i in verseTops.indices) {
            if (verseTops[i] <= y + 1f) result = verseNumbers[i] else break
        }
        return result
    }

    fun verseAtOffset(offset: Int): Int {
        var lo = 0
        var hi = verseStarts.lastIndex
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (verseStarts[mid] <= offset) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return verseNumbers.getOrElse(ans) { 1 }
    }
}

/** Page geometry for a chapter with the current margin widths (0 = margin hidden). */
class PageGeometry(val layout: ChapterLayout, val leftW: Float, val rightW: Float) {
    val left = leftW > 0f
    val right = rightW > 0f
    val textLeft = leftW + Page.COL_PAD
    val colRight = leftW + Page.COL_W
    val width = colRight + rightW
    val height = Page.TEXT_TOP + layout.text.size.height + Page.BOTTOM

    fun regionAt(x: Float): Region = when {
        left && x < leftW -> Region.LEFT
        right && x >= colRight -> Region.RIGHT
        else -> Region.TEXT
    }

    fun visible(r: Region) = when (r) {
        Region.TEXT -> true
        Region.LEFT -> left
        Region.RIGHT -> right
    }

    fun originX(r: Region) = when (r) {
        Region.TEXT -> textLeft
        Region.LEFT -> 0f
        Region.RIGHT -> colRight
    }

    fun originY(r: Region, verse: Int) = if (r == Region.TEXT) Page.TEXT_TOP else layout.verseTop(verse)
}

/** A chapter page placed in a panel's continuous strip; [top] is in strip units. */
class PlacedPage(val geo: PageGeometry, val top: Float) {
    val bottom get() = top + geo.height
    val layout get() = geo.layout
}

private val PAGE_DENSITY = Density(1f, 1f)

fun buildChapterLayout(measurer: TextMeasurer, font: FontFamily, bookName: String, data: ChapterData): ChapterLayout {
    val builder = AnnotatedString.Builder()
    val starts = IntArray(data.verses.size)
    val numbers = IntArray(data.verses.size)
    val numberStyle = SpanStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFA07B45),
        baselineShift = BaselineShift(0.4f),
    )
    data.verses.forEachIndexed { i, v ->
        starts[i] = builder.length
        numbers[i] = v.verse
        builder.withStyle(numberStyle) { append(v.verse.toString()) }
        builder.append("\u2009")
        builder.append(v.text)
        if (i < data.verses.lastIndex) builder.append("\n")
    }
    val textStyle = TextStyle(fontFamily = font, fontSize = Page.FONT.sp, lineHeight = Page.LINE.sp)
    val text = measurer.measure(
        text = builder.toAnnotatedString(),
        style = textStyle,
        constraints = Constraints(maxWidth = Page.TEXT_W.toInt()),
        density = PAGE_DENSITY,
    )
    val title = measurer.measure(
        text = AnnotatedString("$bookName ${data.chapter}"),
        style = TextStyle(fontFamily = font, fontSize = 40.sp, fontWeight = FontWeight.Bold),
        constraints = Constraints(maxWidth = Page.TEXT_W.toInt()),
        density = PAGE_DENSITY,
    )
    return ChapterLayout(data.version, data.book, data.chapter, title, text, starts, numbers)
}
