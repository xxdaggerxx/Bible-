package com.biblestudy.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
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
    /** A book introduction: chapter is the book, verse the part (background, purpose and so on). */
    const val INTRO = -6
    /** Bible aids (AID-11): a custom or feast, or a symbol or number; chapter is the entry's key. */
    const val CUSTOM = -8
    const val SYMBOL = -9

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
    // Writing picked with the lasso: its ids, and where to show the bar of actions.
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pickedTop by remember { mutableStateOf(Offset.Zero) }
    var pickedBottom by remember { mutableStateOf(0f) }
    androidx.compose.foundation.layout.Box(modifier) {
    BasicText(
        text,
        style = style.copy(color = color),
        onTextLayout = { layout = it },
        modifier = Modifier
            .testTag("inkable")
            .pointerInput(doc) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val pen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                    if (vm.readMode || !(pen || vm.fingerDraw)) return@awaitEachGesture
                    val tool = when {
                        down.type == PointerType.Eraser || (pen && StylusState.sideButtonHeld && vm.sideButton.tool == Tool.ERASER) -> Tool.ERASER
                        pen && StylusState.sideButtonHeld && vm.sideButton.tool == Tool.LASSO -> Tool.LASSO
                        vm.tool == Tool.PEN || vm.tool == Tool.HIGHLIGHTER || vm.tool == Tool.ERASER || vm.tool == Tool.LASSO -> vm.tool
                        else -> return@awaitEachGesture
                    }
                    picked = emptySet()
                    val l = layout ?: return@awaitEachGesture
                    val layer = vm.activeLayer()
                    if (tool != Tool.ERASER && tool != Tool.LASSO && (layer == null || layer.locked)) {
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
                    // Writing sounds (INK-15), as on the Bible page.
                    val texture = when (tool) {
                        Tool.PEN -> WritingSound.Texture.PEN
                        Tool.HIGHLIGHTER -> WritingSound.Texture.HIGHLIGHTER
                        Tool.ERASER -> WritingSound.Texture.ERASER
                        else -> null
                    }
                    if (texture != null) vm.sound.start(texture, down.position.x, down.position.y, down.uptimeMillis)
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        // A second finger before the first moves: they scroll the article instead (PH-3).
                        if (!pen && !moved && ev.changes.count { it.pressed } > 1) {
                            live.clear(); vm.sound.stop()
                            return@awaitEachGesture
                        }
                        if (!moved && (c.position - down.position).getDistance() < viewConfiguration.touchSlop / 2f) continue
                        moved = true
                        c.consume()
                        if (tool == Tool.ERASER) eraseAt(c.position)
                        if (texture != null) vm.sound.move(c.position.x, c.position.y, c.pressure, c.uptimeMillis, density)
                        if (live.isEmpty() || (c.position - live.last()).getDistance() >= 1.5f) live.add(c.position)
                    }
                    liveTool = null
                    vm.sound.stop()
                    val points = live.toList()
                    live.clear()
                    if (tool == Tool.LASSO) {
                        // Pick the writing inside the loop (most of a stroke, or a highlight's first word).
                        if (points.size < 3) return@awaitEachGesture
                        val sel = strokes.filter { st ->
                            if (st.verse != doc.verse) return@filter false
                            val at = if (StudyInk.isRange(st)) listOf(l.getBoundingBox(st.points[0].toInt().coerceIn(0, (l.layoutInput.text.length - 1).coerceAtLeast(0))).center)
                            else l.drawnPoints(st)
                            at.isNotEmpty() && at.count { inside(it, points) } * 2 >= at.size
                        }
                        picked = sel.mapTo(HashSet()) { it.id }
                        pickedTop = Offset(points.minOf { it.x }, points.minOf { it.y })
                        pickedBottom = points.maxOf { it.y }
                        if (sel.isEmpty()) vm.message = "Nothing written inside the loop."
                        return@awaitEachGesture
                    }
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
                // The picked writing is outlined.
                if (l != null && picked.isNotEmpty()) for (st in mine) {
                    if (st.id !in picked) continue
                    val pts = if (StudyInk.isRange(st)) {
                        val n = l.layoutInput.text.length
                        val a = st.points[0].toInt().coerceIn(0, n); val b = st.points[1].toInt().coerceIn(a, n)
                        l.getPathForRange(a, b).getBounds().let { listOf(it.topLeft, it.bottomRight) }
                    } else l.drawnPoints(st)
                    if (pts.isEmpty()) continue
                    val pad = 6f
                    drawRect(
                        Color(0xFF1E88E5), Offset(pts.minOf { it.x } - pad, pts.minOf { it.y } - pad),
                        androidx.compose.ui.geometry.Size(pts.maxOf { it.x } - pts.minOf { it.x } + 2 * pad, pts.maxOf { it.y } - pts.minOf { it.y } + 2 * pad),
                        style = Stroke(1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                    )
                }
                if (liveTool == Tool.LASSO) {
                    drawInk(live.toList() + live.take(1), Color(0xFF1E88E5), 2f)
                }
                if (liveTool == Tool.PEN || liveTool == Tool.HIGHLIGHTER) {
                    val hl = liveTool == Tool.HIGHLIGHTER
                    drawInk(live.toList(), Color(if (hl) vm.highlightColor else vm.penColor).copy(alpha = if (hl) 0.4f else 1f), vm.currentWidth(hl) * density / 2f)
                }
            },
    )
    if (picked.isNotEmpty()) {
        // Above the loop, or below it when there's no room above.
        StudyLassoBar(vm, strokes.filter { it.id in picked }, Modifier.offset {
            val above = pickedTop.y - 56 * density
            androidx.compose.ui.unit.IntOffset(pickedTop.x.toInt(), (if (above >= 0) above else pickedBottom + 8 * density).toInt())
        }) {
            picked = it
        }
    }
    }
}

/**
 * The actions for writing picked with the lasso on a study view (INK-16): give it the colour in
 * use, move it to another layer, or delete it. Each is one undoable step.
 */
@Composable
private fun StudyLassoBar(vm: StudyViewModel, sel: List<InkStroke>, modifier: Modifier, onPicked: (Set<Long>) -> Unit) {
    fun swap(after: List<InkStroke>) {
        sel.forEach { vm.removeItem(it) }
        after.forEach { vm.addItem(it) }
        vm.record(Edit(after, sel))
        onPicked(after.mapTo(HashSet()) { it.id })
    }
    androidx.compose.material3.Surface(
        modifier.testTag("studyLassoBar"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.TextButton(onClick = {
                swap(sel.map { it.withColor(if (it.highlighter) vm.highlightColor else vm.penColor) })
            }) { androidx.compose.material3.Text("Colour") }
            var layers by remember { mutableStateOf(false) }
            androidx.compose.foundation.layout.Box {
                androidx.compose.material3.TextButton(onClick = { layers = true }) { androidx.compose.material3.Text("Layer") }
                androidx.compose.material3.DropdownMenu(expanded = layers, onDismissRequest = { layers = false }) {
                    for (layer in vm.layers) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { androidx.compose.material3.Text(layer.name) },
                            enabled = !layer.locked,
                            onClick = { layers = false; swap(sel.map { it.withLayer(layer.id) }) },
                        )
                    }
                }
            }
            androidx.compose.material3.TextButton(onClick = {
                sel.forEach { vm.removeItem(it) }
                vm.record(Edit(emptyList(), sel))
                onPicked(emptySet())
            }) { androidx.compose.material3.Text("Delete") }
            androidx.compose.material3.TextButton(onClick = { onPicked(emptySet()) }) { androidx.compose.material3.Text("Done") }
        }
    }
}

/** Whether [p] is inside the loop [poly] (even-odd rule). */
private fun inside(p: Offset, poly: List<Offset>): Boolean {
    var c = false
    var j = poly.lastIndex
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
        j = i
    }
    return c
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
