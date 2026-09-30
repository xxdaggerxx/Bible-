package com.biblestudy.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.biblestudy.app.model.ChapterData
import com.biblestudy.app.model.Heading
import com.biblestudy.app.model.InkStroke
import com.biblestudy.app.model.Region
import com.biblestudy.app.model.Verse
import kotlin.math.floor

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

/** Space between lines (ANCH-8). Line breaks never change, so ink stays on its line. */
enum class LineSpacing(val label: String, val factor: Float) {
    NORMAL("Normal", 1f), WIDE("Wide", 1.45f), EXTRA("Extra wide", 1.9f)
}

/** One or more section headings laid out together, shown in a gap above [beforeLine]. */
class HeadingBlock(val beforeLine: Int, val lines: List<TextLayoutResult>, val height: Float) {
    companion object {
        const val TOP_PAD = 30f
        const val FIRST_TOP_PAD = 6f // right under the chapter title
        const val BOTTOM_PAD = 12f
        const val GAP = 4f
    }
}

/**
 * The fixed layout of one chapter of one version.
 *
 * Coordinates: "layout" y is inside [text] as measured; "display" y (what everything else uses)
 * adds the gaps opened up for section headings.
 *
 * Ink on the words is stored in "line coordinates": each stroke is anchored to one line (the one
 * under its middle), and every y is stored as `line + 0.5 + (y - that line's baseline) / LINE_K`.
 * Showing headings or changing line spacing moves each line's baseline, and the stroke moves
 * rigidly with it: underlines stay under their words and circles keep their shape.
 */
class ChapterLayout(
    val version: String,
    val book: Int,
    val chapter: Int,
    val title: TextLayoutResult,
    val text: TextLayoutResult,
    private val verseStarts: IntArray,
    private val verseNumbers: IntArray,
    val headings: List<HeadingBlock> = emptyList(),
) {
    val textLength = text.layoutInput.text.length
    val lineCount = text.lineCount

    /** Extra display offset of each line: the height of all headings above it. */
    private val shift = FloatArray(lineCount).also { arr ->
        var acc = 0f
        var h = 0
        val sorted = headings.sortedBy { it.beforeLine }
        for (i in 0 until lineCount) {
            while (h < sorted.size && sorted[h].beforeLine <= i) acc += sorted[h++].height
            arr[i] = acc
        }
    }

    /** Height of the text including heading gaps. */
    val displayHeight: Float = text.size.height + (if (lineCount > 0) shift[lineCount - 1] else 0f)

    private fun layoutTop(i: Int) = text.getLineTop(i)

    /** Distance from this line's top to the next line's top (the last line: its own height). */
    fun pitch(i: Int): Float =
        if (i + 1 < lineCount) layoutTop(i + 1) - layoutTop(i) else text.getLineBottom(i) - layoutTop(i)

    fun displayTop(i: Int): Float = layoutTop(i) + shift[i]

    private fun lineAtDisplayY(y: Float): Int {
        var lo = 0
        var hi = lineCount - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (displayTop(mid) <= y) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** Baseline of a line, in display coordinates. */
    fun baseline(i: Int): Float = text.getLineBaseline(i) + shift[i]

    /** The line a display y falls on (lines own the space from their top to the next line's top). */
    fun lineAt(y: Float): Int = if (lineCount == 0) 0 else lineAtDisplayY(y)

    /** Display y (relative to the text's top) to a line coordinate anchored on [line]. */
    fun toLine(y: Float, line: Int = lineAt(y)): Float {
        if (lineCount == 0) return 0.5f + y / LINE_K
        return line + 0.5f + (y - baseline(line)) / LINE_K
    }

    /** Line coordinate back to display y. */
    fun fromLine(c: Float): Float {
        if (lineCount == 0) return (c - 0.5f) * LINE_K
        val i = floor(c).toInt().coerceIn(0, lineCount - 1)
        return baseline(i) + (c - i - 0.5f) * LINE_K
    }

    /** Whether display y falls on a line of text (not in a heading gap or outside the text). */
    fun isOnText(y: Float): Boolean {
        if (lineCount == 0 || y < displayTop(0)) return false
        val i = lineAtDisplayY(y)
        return y < displayTop(i) + (text.getLineBottom(i) - layoutTop(i))
    }

    private fun toLayoutY(y: Float): Float {
        if (lineCount == 0) return y
        val i = lineAtDisplayY(y)
        val inLine = (y - displayTop(i)).coerceAtMost(text.getLineBottom(i) - layoutTop(i) - 0.5f)
        return layoutTop(i) + inLine
    }

    /** Character offset under a point in display coordinates (relative to the text's top-left). */
    fun offsetAt(x: Float, y: Float): Int = text.getOffsetForPosition(Offset(x, toLayoutY(y)))

    /** Centre of a character's box, in display coordinates. */
    fun charCenter(offset: Int): Offset {
        val box = text.getBoundingBox(offset)
        return Offset(box.center.x, box.center.y + shift[text.getLineForOffset(offset)])
    }

    /** Runs of lines that share the same heading offset: (first line, last line, shift). */
    val segments: List<Triple<Int, Int, Float>> = buildList {
        var start = 0
        for (i in 1..lineCount) {
            if (i == lineCount || shift[i] != shift[start]) {
                if (lineCount > 0) add(Triple(start, i - 1, shift[start]))
                start = i
            }
        }
    }

    /** Layout y where a segment's clip starts/ends (open-ended at the top and bottom of the text). */
    fun segmentClip(firstLine: Int, lastLine: Int): Pair<Float, Float> {
        val top = if (firstLine == 0) -10_000f else layoutTop(firstLine)
        val bottom = if (lastLine >= lineCount - 1) 100_000f else layoutTop(lastLine + 1)
        return top to bottom
    }

    // ---------- ink on the words ----------

    /** Line-coordinate points (x, line, pressure) to display points (x, y, pressure). */
    fun displayPoints(points: FloatArray): FloatArray =
        FloatArray(points.size) { i -> if (i % 3 == 1) fromLine(points[i]) else points[i] }

    /** Display points to line-coordinate points, all anchored on the line under the stroke's middle. */
    fun linePoints(points: FloatArray): FloatArray {
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 1 until points.size step 3) { minY = minOf(minY, points[i]); maxY = maxOf(maxY, points[i]) }
        val line = lineAt((minY + maxY) / 2f)
        return FloatArray(points.size) { i -> if (i % 3 == 1) toLine(points[i], line) else points[i] }
    }

    private val renders = HashMap<Long, StrokeRender>()

    /** Drawing paths for a stroke on the words, in display coordinates. Cached per layout. */
    fun render(s: InkStroke): StrokeRender {
        renders[s.id]?.let { if (it.source === s.points) return it }
        return buildRender(displayPoints(s.points), s.width, s.highlighter, source = s.points).also { renders[s.id] = it }
    }

    private val hlPaths = HashMap<Long, Triple<Int, Int, Path>>()

    /** The shape of a highlight over a character range, in display coordinates (split around headings). */
    fun highlightPath(id: Long, start: Int, end: Int): Path {
        hlPaths[id]?.let { if (it.first == start && it.second == end) return it.third }
        val a = start.coerceIn(0, textLength)
        val b = end.coerceIn(0, textLength)
        val path = Path()
        for ((first, last, dy) in segments) {
            val s0 = maxOf(a, text.getLineStart(first))
            val s1 = minOf(b, text.getLineEnd(last))
            if (s1 <= s0) continue
            val piece = text.getPathForRange(s0, s1)
            piece.translate(Offset(0f, dy))
            path.addPath(piece)
        }
        hlPaths[id] = Triple(start, end, path)
        return path
    }

    // ---------- verses ----------

    private val verseTops = FloatArray(verseStarts.size) { i ->
        Page.TEXT_TOP + displayTop(text.getLineForOffset(verseStarts[i]))
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

    /**
     * Page-y range a verse occupies: from its first line to the next verse's first line (so a
     * heading above the next verse counts as part of this one). The last verse ends with the text.
     */
    fun verseSpan(verse: Int): Pair<Float, Float> {
        if (verseTops.isEmpty()) return Page.TEXT_TOP to Page.TEXT_TOP + displayHeight
        var i = 0
        while (i + 1 < verseNumbers.size && verseNumbers[i + 1] <= verse) i++
        val bottom = if (i + 1 < verseTops.size) verseTops[i + 1] else Page.TEXT_TOP + displayHeight
        return verseTops[i] to maxOf(bottom, verseTops[i] + 1f)
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

    /** Character range of a verse (without its number), for selecting whole verses. */
    fun verseRange(verse: Int): IntRange? {
        val i = verseNumbers.indexOf(verse)
        if (i < 0) return null
        val start = verseStarts[i]
        val end = if (i + 1 < verseStarts.size) verseStarts[i + 1] - 1 else textLength
        return start until end
    }

    fun textOf(start: Int, end: Int): String =
        text.layoutInput.text.text.substring(start.coerceIn(0, textLength), end.coerceIn(0, textLength))
}

/** Page geometry for a chapter with the current margin widths (0 = margin hidden). */
class PageGeometry(val layout: ChapterLayout, val leftW: Float, val rightW: Float) {
    val left = leftW > 0f
    val right = rightW > 0f
    val textLeft = leftW + Page.COL_PAD
    val colRight = leftW + Page.COL_W
    val width = colRight + rightW
    val height = Page.TEXT_TOP + layout.displayHeight + Page.BOTTOM

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

/** Scale for offsets from a line's baseline in line coordinates; keeps every offset inside one line's slot. */
private const val LINE_K = 4000f

fun buildChapterLayout(
    measurer: TextMeasurer,
    font: FontFamily,
    bookName: String,
    data: ChapterData,
    spacing: LineSpacing = LineSpacing.NORMAL,
): ChapterLayout {
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
    val textStyle = TextStyle(fontFamily = font, fontSize = Page.FONT.sp, lineHeight = (Page.LINE * spacing.factor).sp)
    val constraints = Constraints(maxWidth = Page.TEXT_W.toInt())
    val text = measurer.measure(builder.toAnnotatedString(), textStyle, constraints = constraints, density = PAGE_DENSITY)
    val title = measurer.measure(
        text = AnnotatedString("$bookName ${data.chapter}"),
        style = TextStyle(fontFamily = font, fontSize = 40.sp, fontWeight = FontWeight.Bold),
        constraints = constraints,
        density = PAGE_DENSITY,
    )

    // Section headings go in a gap above the first line of the verse they introduce.
    val blocks = data.headings.groupBy { it.verse }.mapNotNull { (verse, hs) ->
        val i = numbers.indexOf(verse).takeIf { it >= 0 } ?: numbers.indexOfFirst { it > verse }.takeIf { it >= 0 }
            ?: return@mapNotNull null
        val line = text.getLineForOffset(starts[i])
        val lines = hs.sortedBy { it.level }.flatMap { h -> measureHeading(measurer, font, h, constraints) }
        val top = if (line == 0) HeadingBlock.FIRST_TOP_PAD else HeadingBlock.TOP_PAD
        val height = top + lines.sumOf { it.size.height.toDouble() }.toFloat() + HeadingBlock.GAP * (lines.size - 1) +
            HeadingBlock.BOTTOM_PAD
        HeadingBlock(line, lines, height)
    }
    return ChapterLayout(data.version, data.book, data.chapter, title, text, starts, numbers, blocks)
}

/**
 * Where each verse starts in a chapter's text, exactly as [buildChapterLayout] builds it
 * (number, thin space, text, newline). Lets highlights be placed in a verse without laying out the text.
 */
fun verseStartOffsets(verses: List<Verse>): IntArray {
    val out = IntArray(verses.size)
    var pos = 0
    verses.forEachIndexed { i, v ->
        out[i] = pos
        pos += v.verse.toString().length + 1 + v.text.length + 1
    }
    return out
}

private fun measureHeading(measurer: TextMeasurer, font: FontFamily, h: Heading, c: Constraints): List<TextLayoutResult> {
    val style = when (h.level) {
        0 -> TextStyle(fontFamily = font, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        1 -> TextStyle(fontFamily = font, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        else -> TextStyle(fontFamily = font, fontSize = 20.sp, fontStyle = FontStyle.Italic)
    }
    val out = mutableListOf(measurer.measure(AnnotatedString(h.text), style, constraints = c, density = PAGE_DENSITY))
    if (h.refs.isNotBlank()) {
        val refStyle = TextStyle(fontFamily = font, fontSize = 15.sp, fontStyle = FontStyle.Italic, color = Color(0xFF8A7A62))
        out += measurer.measure(AnnotatedString(h.refs), refStyle, constraints = c, density = PAGE_DENSITY)
    }
    return out
}
