package com.biblestudy.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.biblestudy.app.R
import com.biblestudy.app.model.ChapterData
import com.biblestudy.app.model.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val bibleFont = FontFamily(
    Font(R.font.gentium_book_plus_regular),
    Font(R.font.gentium_book_plus_bold, FontWeight.Bold),
)

@Composable
fun ReaderPanel(vm: StudyViewModel, index: Int, onOpenPicker: () -> Unit, modifier: Modifier = Modifier) {
    val panel = vm.panels[index]
    val ctl = remember(panel) { ReaderController(vm, panel) }
    val resolver = LocalFontFamilyResolver.current
    val measurer = remember(resolver) { TextMeasurer(resolver, Density(1f, 1f), LayoutDirection.Ltr) }
    val theme = vm.theme

    val data by produceState<ChapterData?>(null, panel.version, panel.book, panel.chapter) {
        val v = panel.version; val b = panel.book; val c = panel.chapter
        value = withContext(Dispatchers.IO) { ChapterData(v, b, c, vm.bible.chapter(b, c)) }
    }
    val layout = remember(data) {
        data?.let { buildChapterLayout(measurer, bibleFont, vm.bible.book(it.book).name, it) }
    }
    val geo = remember(layout, vm.marginLeft, vm.marginRight) {
        layout?.let { PageGeometry(it, vm.marginLeft, vm.marginRight) }
    }
    SideEffect {
        ctl.geo = geo
        ctl.panelIndex = index
    }

    // Fit / scroll whenever the chapter, margins or panel size change.
    LaunchedEffect(geo, panel.viewW, panel.viewH, panel.pendingVerse) {
        val g = geo ?: return@LaunchedEffect
        if (panel.viewW <= 0f) return@LaunchedEffect
        if (ctl.lastPageW != g.width || ctl.lastViewW != panel.viewW) {
            ctl.fitWidth()
            ctl.lastPageW = g.width
            ctl.lastViewW = panel.viewW
        }
        val pending = panel.pendingVerse
        val sameChapter = g.layout.book == panel.book && g.layout.chapter == panel.chapter
        if (pending != null && sameChapter) {
            panel.panY = -(g.layout.verseTop(pending) - 40f) * panel.zoom
            panel.pendingVerse = null
        } else if (ctl.lastLayout !== g.layout && ctl.lastLayout != null) {
            panel.panY = 0f
        }
        ctl.lastLayout = g.layout
        ctl.clamp()
    }
    LaunchedEffect(layout, vm.dataGeneration) {
        layout?.let { vm.ensureLoaded(it.version, it.book, it.chapter) }
    }

    val active = vm.activePanel == index && vm.panels.size > 1
    Column(
        modifier.then(
            if (active) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier
        )
    ) {
        PanelHeader(vm, index, ctl, onOpenPicker)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        panel.viewW = it.width.toFloat()
                        panel.viewH = it.height.toFloat()
                    }
                    .pointerInput(ctl) { readerGestures(ctl) { vm.fingerDraw } }
            ) {
                drawRect(theme.surround)
                val g = geo ?: return@Canvas
                drawPage(vm, ctl, g, theme)
            }
            if (layout == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            val selected = ctl.selectedImage()
            if (selected != null && vm.tool == com.biblestudy.app.model.Tool.SELECT) {
                FilledTonalButton(
                    onClick = { vm.deleteImage(selected); ctl.selectedImageId = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) { Text("Delete image") }
            }
        }
    }
}

@Composable
private fun PanelHeader(vm: StudyViewModel, index: Int, ctl: ReaderController, onOpenPicker: () -> Unit) {
    val panel = vm.panels[index]
    val book = vm.bible.book(panel.book)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.activePanel = index; vm.prevChapter(index) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous chapter")
        }
        TextButton(onClick = { vm.activePanel = index; onOpenPicker() }) {
            Text("${book.name} ${panel.chapter}", style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = { vm.activePanel = index; vm.nextChapter(index) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next chapter")
        }
        Text(panel.version, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { ctl.fitWidth() }) { Text("Fit width") }
        if (vm.panels.size > 1) {
            IconButton(onClick = { vm.closePanel(index) }) {
                Icon(Icons.Filled.Close, contentDescription = "Close panel")
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawPage(vm: StudyViewModel, ctl: ReaderController, g: PageGeometry, theme: PageTheme) {
    val panel = ctl.panel
    val layout = g.layout
    val zoom = panel.zoom
    val view = ctl.visibleRect()

    // Layer order: later layers draw on top. Hidden layers are skipped entirely.
    val order = vm.layers.filter { it.visible }.map { it.id }

    val textStrokes = vm.textStrokesFor(layout.version, layout.book, layout.chapter)
    val marginStrokes = vm.marginStrokesFor(layout.book, layout.chapter)
    val highlights = vm.highlightsFor(layout.version, layout.book, layout.chapter)
    val images = vm.imagesFor(layout.book, layout.chapter)
    val notes = vm.notesFor(layout.book, layout.chapter)

    withTransform({
        translate(panel.panX, panel.panY)
        scale(zoom, zoom, pivot = Offset.Zero)
    }) {
        // Paper and margins
        drawRect(theme.page, size = Size(g.width, g.height))
        if (g.left) {
            drawRect(theme.margin, topLeft = Offset.Zero, size = Size(g.leftW, g.height))
            drawLine(theme.rule, Offset(g.leftW, 0f), Offset(g.leftW, g.height), strokeWidth = 1.5f)
        }
        if (g.right) {
            drawRect(theme.margin, topLeft = Offset(g.colRight, 0f), size = Size(Page.MARGIN_W, g.height))
            drawLine(theme.rule, Offset(g.colRight, 0f), Offset(g.colRight, g.height), strokeWidth = 1.5f)
        }

        // Highlights sit beneath the text.
        for (layerId in order) {
            translate(g.textLeft, Page.TEXT_TOP) {
                for (h in highlights) {
                    if (h.layerId == layerId) drawPath(ctl.highlightPath(h, layout), Color(h.color).copy(alpha = HIGHLIGHT_ALPHA))
                }
            }
            drawStrokes(vm, g, view, textStrokes, marginStrokes, layerId, highlighter = true)
        }

        // Scripture text
        drawText(layout.title, color = theme.text, topLeft = Offset(g.textLeft, Page.TITLE_TOP))
        drawText(layout.text, color = theme.text, topLeft = Offset(g.textLeft, Page.TEXT_TOP))

        // Verse markers: typed note (dot) and bookmark (ribbon)
        for (v in notes.keys) {
            drawCircle(Color(0xFFA07B45), radius = 6f, center = Offset(g.textLeft - 24f, layout.verseTop(v) + 22f))
        }
        for (b in vm.bookmarks) {
            if (b.book == layout.book && b.chapter == layout.chapter) {
                val y = layout.verseTop(b.verse) + 8f
                drawRect(Color(0xFFC62828), topLeft = Offset(g.textLeft - 44f, y), size = Size(8f, 26f))
            }
        }

        // Margin images, then pen ink, layer by layer.
        for (layerId in order) {
            for (img in images) {
                if (img.layerId != layerId || !g.visible(img.region)) continue
                val r = ctl.imageRect(g, img)
                if (!ReaderController.overlaps(r, view)) continue
                val bmp = vm.bitmap(img.file)
                if (bmp == null) {
                    drawRect(theme.rule, topLeft = r.topLeft, size = r.size)
                } else {
                    drawImage(
                        bmp,
                        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                        dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
            drawStrokes(vm, g, view, textStrokes, marginStrokes, layerId, highlighter = false)
        }

        // Stroke in progress
        ctl.live?.let { ink ->
            ink.tick // redraw on every new point
            val r = buildRender(ink.toArray(), ink.width, ink.highlighter)
            val c = Color(ink.color).let { if (ink.highlighter) it.copy(alpha = HIGHLIGHT_ALPHA) else it }
            drawStrokeRender(r, c, ink.ox, ink.oy)
        }

        // Selection outline and resize handle
        ctl.selectedImage()?.let { img ->
            val r = ctl.imageRect(g, img)
            drawRect(Color(0xFF1E88E5), topLeft = r.topLeft, size = r.size, style = Stroke(width = 2.5f / zoom))
            drawCircle(Color(0xFF1E88E5), radius = 12f / zoom, center = r.bottomRight)
        }
    }
}

private fun DrawScope.drawStrokes(
    vm: StudyViewModel,
    g: PageGeometry,
    view: androidx.compose.ui.geometry.Rect,
    textStrokes: List<com.biblestudy.app.model.InkStroke>,
    marginStrokes: List<com.biblestudy.app.model.InkStroke>,
    layerId: Long,
    highlighter: Boolean,
) {
    for (s in textStrokes) {
        if (s.layerId != layerId || s.highlighter != highlighter) continue
        val r = vm.render(s)
        if (!ReaderController.overlaps(r.bounds.translate(g.textLeft, Page.TEXT_TOP), view)) continue
        drawStrokeRender(r, strokeColor(s.color, highlighter), g.textLeft, Page.TEXT_TOP)
    }
    for (s in marginStrokes) {
        if (s.layerId != layerId || s.highlighter != highlighter || !g.visible(s.region)) continue
        val ox = g.originX(s.region)
        val oy = g.originY(s.region, s.verse)
        val r = vm.render(s)
        if (!ReaderController.overlaps(r.bounds.translate(ox, oy), view)) continue
        drawStrokeRender(r, strokeColor(s.color, highlighter), ox, oy)
    }
}

private fun strokeColor(c: Int, highlighter: Boolean) =
    Color(c).let { if (highlighter) it.copy(alpha = HIGHLIGHT_ALPHA) else it }

// ---------------------------------------------------------------------------------------------
// Input: pen draws, fingers scroll/zoom/tap. Touches right after pen use are treated as palm.
// ---------------------------------------------------------------------------------------------

private fun PointerInputChange.isPen() =
    type == PointerType.Stylus || type == PointerType.Eraser || type == PointerType.Mouse

private suspend fun PointerInputScope.readerGestures(ctl: ReaderController, fingerDraw: () -> Boolean) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        ctl.touched()
        if (down.isPen() || (down.type == PointerType.Touch && fingerDraw())) {
            trackPen(down, ctl)
            return@awaitEachGesture
        }
        if (ctl.recentlyPenned()) {
            consumeUntilUp() // palm resting while writing
            return@awaitEachGesture
        }
        var travelled = 0f
        var multiTouch = false
        var penDown: PointerInputChange? = null
        while (true) {
            val event = awaitPointerEvent()
            penDown = event.changes.firstOrNull { it.pressed && !it.previousPressed && it.isPen() }
            if (penDown != null) break
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } > 1) multiTouch = true
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val centroid = event.calculateCentroid(useCurrent = true)
            travelled += pan.getDistance()
            if (centroid.isSpecified && (zoom != 1f || pan != Offset.Zero)) ctl.transform(centroid, pan, zoom)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
        val pen = penDown
        if (pen != null) {
            trackPen(pen, ctl) // pen touched down while palm/finger was resting
        } else if (!multiTouch && travelled < viewConfiguration.touchSlop) {
            ctl.onTap(down.position)
        }
    }
}

private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun AwaitPointerEventScope.trackPen(first: PointerInputChange, ctl: ReaderController) {
    ctl.penStart(first.position, first.pressure, first.type == PointerType.Eraser)
    first.consume()
    try {
        while (true) {
            val event = awaitPointerEvent()
            val c = event.changes.firstOrNull { it.id == first.id }
            if (c == null || !c.pressed) break
            for (h in c.historical) ctl.penMove(h.position, c.pressure)
            ctl.penMove(c.position, c.pressure)
            event.changes.forEach { it.consume() }
        }
    } finally {
        ctl.penEnd()
    }
}
