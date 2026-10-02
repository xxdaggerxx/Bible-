package com.biblestudy.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import com.biblestudy.app.model.Edit
import com.biblestudy.app.model.InkStroke
import com.biblestudy.app.model.Region
import com.biblestudy.app.model.Tool
import kotlin.math.hypot

/**
 * Writing on study views (INK-16): the dictionary, topics, commentary, names & places and
 * cross-references. There are no margins; the writing goes over the text.
 *
 * Study writing is stored as ink strokes under a "book" below zero, one per kind of study text
 * ([DICTIONARY] and the rest), with [chapter] and [verse] naming the article. So it shares the
 * strokes table, undo, layers and backups with the rest of the ink, and nothing that lists Bible
 * books or sketch pages (1 to 66, and from 1000) sees it.
 *
 * Writing stays on its words when the panel is resized, because each point is kept against the
 * character nearest to it: (character offset, x from that character, y from the top of its line).
 * A highlight snaps to whole words and is kept as a range of characters: one point
 * (start, end, [RANGE]).
 */
object StudyInk {
    const val DICTIONARY = -1
    const val TOPIC = -2
    const val COMMENTARY = -3
    const val NAME = -4
    const val CROSSREF = -5

    /** The third value of a highlight's single point: marks it as a character range. */
    const val RANGE = -7777f

    fun isStudy(book: Int) = book < 0

    fun isRange(s: InkStroke) = s.highlighter && s.points.size == 3 && s.points[2] == RANGE
}

/** One article (or cross-reference) that can be written on. */
data class InkDoc(val book: Int, val chapter: Int, val verse: Int = 0)

/** Where an anchored point is drawn in [layout]: the character's position plus the saved offset. */
private fun TextLayoutResult.place(offset: Float, dx: Float, dy: Float): Offset {
    val n = layoutInput.text.length
    val o = offset.toInt().coerceIn(0, n)
    val line = getLineForOffset(o)
    return Offset(getHorizontalPosition(o, true) + dx, getLineTop(line) + dy)
}

/** Anchors a point in [layout] to its nearest character. */
private fun TextLayoutResult.anchor(p: Offset): Triple<Float, Float, Float> {
    val o = getOffsetForPosition(p)
    val line = getLineForOffset(o)
    return Triple(o.toFloat(), p.x - getHorizontalPosition(o, true), p.y - getLineTop(line))
}

/** The points of an anchored stroke, as drawn in [layout]. */
private fun TextLayoutResult.drawnPoints(s: InkStroke): List<Offset> =
    (0 until s.points.size / 3).map { i -> place(s.points[3 * i], s.points[3 * i + 1], s.points[3 * i + 2]) }

/** A word-snapped character range from [a] to [b] (either order). */
private fun TextLayoutResult.wordRange(a: Offset, b: Offset): IntRange? {
    val n = layoutInput.text.length
    if (n == 0) return null
    val x = getOffsetForPosition(a).coerceIn(0, n - 1)
    val y = getOffsetForPosition(b).coerceIn(0, n - 1)
    val from = getWordBoundary(minOf(x, y)).start
    val to = getWordBoundary(maxOf(x, y)).end
    return if (to > from) from until to else null
}

/**
 * Study text that can be written on with the pen (INK-16). The stylus writes with the tool in
 * use (pen, highlighter or eraser); fingers scroll and tap links as before, unless *Draw with
 * finger* is on. Read mode leaves the text alone.
 */
@Composable
fun InkableText(vm: StudyViewModel, doc: InkDoc, text: AnnotatedString, modifier: Modifier = Modifier, style: TextStyle) {
    LaunchedEffect(doc.book, doc.chapter) { vm.ensureStudyInkLoaded(doc.book, doc.chapter) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // The stroke being drawn, in this text's own pixels (anchored when the pen lifts).
    val live = remember { mutableStateListOf<Offset>() }
    var liveTool by remember { mutableStateOf<Tool?>(null) }
    val color = style.color.takeIf { it != Color.Unspecified } ?: androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val strokes = vm.marginStrokesFor(doc.book, doc.chapter)
    BasicText(
        text,
        style = style.copy(color = color),
        onTextLayout = { layout = it },
        modifier = modifier
            .testTag("inkable")
            .pointerInput(doc) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val pen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                    if (vm.readMode || !(pen || vm.fingerDraw)) return@awaitEachGesture
                    val tool = when {
                        down.type == PointerType.Eraser || (pen && StylusState.sideButtonHeld && vm.sideButton.tool == Tool.ERASER) -> Tool.ERASER
                        vm.tool == Tool.PEN || vm.tool == Tool.HIGHLIGHTER || vm.tool == Tool.ERASER -> vm.tool
                        else -> return@awaitEachGesture
                    }
                    val l = layout ?: return@awaitEachGesture
                    val layer = vm.activeLayer()
                    if (tool != Tool.ERASER && (layer == null || layer.locked)) {
                        layer?.let { vm.message = "Layer “${it.name}” is locked." }
                        return@awaitEachGesture
                    }
                    live.clear(); live.add(down.position); liveTool = tool
                    val erased = ArrayList<InkStroke>()
                    fun eraseAt(p: Offset) {
                        val r = 18f * density / 2f
                        val o = l.getOffsetForPosition(p)
                        for (s in strokes.toList()) {
                            if (s.verse != doc.verse || s in erased) continue
                            val hit = if (StudyInk.isRange(s)) {
                                o >= s.points[0].toInt() && o < s.points[1].toInt() && l.getBoundingBox(o.coerceAtMost(l.layoutInput.text.length - 1)).contains(p)
                            } else l.drawnPoints(s).any { hypot(it.x - p.x, it.y - p.y) <= r + s.width }
                            if (hit) { erased.add(s); vm.removeItem(s) }
                        }
                    }
                    if (tool == Tool.ERASER) eraseAt(down.position)
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        if (!moved && (c.position - down.position).getDistance() < viewConfiguration.touchSlop / 2f) continue
                        moved = true
                        c.consume()
                        if (tool == Tool.ERASER) eraseAt(c.position)
                        if (live.isEmpty() || (c.position - live.last()).getDistance() >= 1.5f) live.add(c.position)
                    }
                    liveTool = null
                    val points = live.toList()
                    live.clear()
                    if (tool == Tool.ERASER) {
                        if (erased.isNotEmpty()) vm.record(Edit(emptyList(), erased))
                        return@awaitEachGesture
                    }
                    if (!moved || layer == null) return@awaitEachGesture
                    val stroke = if (tool == Tool.HIGHLIGHTER && vm.snapHighlights) {
                        val range = l.wordRange(points.first(), points.last()) ?: return@awaitEachGesture
                        InkStroke(vm.newId(), layer.id, null, doc.book, doc.chapter, Region.RIGHT, doc.verse, true, vm.highlightColor, 0f,
                            floatArrayOf(range.first.toFloat(), (range.last + 1).toFloat(), StudyInk.RANGE))
                    } else {
                        val packed = FloatArray(points.size * 3)
                        points.forEachIndexed { i, p ->
                            val (o, dx, dy) = l.anchor(p)
                            packed[3 * i] = o; packed[3 * i + 1] = dx; packed[3 * i + 2] = dy
                        }
                        val hl = tool == Tool.HIGHLIGHTER
                        InkStroke(vm.newId(), layer.id, null, doc.book, doc.chapter, Region.RIGHT, doc.verse, hl,
                            if (hl) vm.highlightColor else vm.penColor, vm.currentWidth(hl), packed)
                    }
                    if (!layer.visible) vm.setLayerVisible(layer.id, true)
                    vm.addItem(stroke)
                    vm.record(Edit(listOf(stroke), emptyList()))
                }
            }
            .drawWithContent {
                val l = layout
                val mine = if (l == null) emptyList() else strokes.filter { it.verse == doc.verse }
                val visible = vm.layers.filter { it.visible }.associateBy { it.id }
                // Highlights go under the words, writing over them.
                if (l != null) for (s in mine) {
                    val layer = visible[s.layerId] ?: continue
                    if (StudyInk.isRange(s)) {
                        val n = l.layoutInput.text.length
                        val a = s.points[0].toInt().coerceIn(0, n); val b = s.points[1].toInt().coerceIn(a, n)
                        drawPath(l.getPathForRange(a, b), Color(s.color).copy(alpha = 0.4f * layer.opacity))
                    }
                }
                drawContent()
                if (l != null) for (s in mine) {
                    val layer = visible[s.layerId] ?: continue
                    if (StudyInk.isRange(s)) continue
                    val alpha = (if (s.highlighter) 0.4f else 1f) * layer.opacity
                    drawInk(l.drawnPoints(s), Color(s.color).copy(alpha = alpha), s.width * density / 2f)
                }
                if (liveTool == Tool.PEN || liveTool == Tool.HIGHLIGHTER) {
                    val hl = liveTool == Tool.HIGHLIGHTER
                    drawInk(live.toList(), Color(if (hl) vm.highlightColor else vm.penColor).copy(alpha = if (hl) 0.4f else 1f), vm.currentWidth(hl) * density / 2f)
                }
            },
    )
}

private fun DrawScope.drawInk(points: List<Offset>, color: Color, width: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) { drawCircle(color, width / 2f, points[0]); return }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
