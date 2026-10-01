package com.biblestudy.app.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.biblestudy.app.model.Annotation
import com.biblestudy.app.model.Edit
import com.biblestudy.app.model.Highlight
import com.biblestudy.app.model.InkStroke
import com.biblestudy.app.model.MarginImage
import com.biblestudy.app.data.RefLinks
import com.biblestudy.app.data.Passage
import com.biblestudy.app.model.MarginText
import com.biblestudy.app.model.Region
import com.biblestudy.app.model.Sketch
import com.biblestudy.app.model.Tool
import com.biblestudy.app.model.VerseId
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** The stroke currently being drawn. Points are in the local coordinates of its region. */
class LiveInk(
    val page: PlacedPage,
    val region: Region,
    val verse: Int,
    val ox: Float,
    val oy: Float,
    val highlighter: Boolean,
    val color: Int,
    val width: Float,
    val layerId: Long,
) {
    private var data = FloatArray(384)
    var size = 0
        private set
    var tick by mutableIntStateOf(0)
        private set

    fun add(x: Float, y: Float, pressure: Float) {
        if (frozen) return
        if (size >= 3) {
            val dx = x - data[size - 3]; val dy = y - data[size - 2]
            if (dx * dx + dy * dy < 0.36f) return
        }
        if (size + 3 > data.size) data = data.copyOf(data.size * 2)
        data[size] = x; data[size + 1] = y; data[size + 2] = pressure
        size += 3
        tick++
    }

    fun toArray(): FloatArray = data.copyOf(size)

    /** Replaces the points (a stroke snapped to a shape, INK-12); later points are ignored. */
    fun replaceAll(points: FloatArray) {
        data = points.copyOf(maxOf(points.size, 3))
        size = points.size
        frozen = true
        tick++
    }

    /** Set once the stroke has snapped to a shape. */
    var frozen = false
        private set
}

/** A lasso being drawn: points in page-local coordinates of [page]. */
class LiveLasso(val page: PlacedPage) {
    val points = ArrayList<Offset>()
    var tick by mutableIntStateOf(0)
        private set

    fun add(p: Offset) {
        val last = points.lastOrNull()
        if (last != null && (last - p).getDistanceSquared() < 4f) return
        points.add(p)
        tick++
    }
}

/** Text chosen with a long press: a character range of one laid-out chapter. [anchor] is the first word. */
data class TextSel(
    val layout: ChapterLayout,
    val anchor: IntRange,
    val start: Int,
    val end: Int,
    /** Set when the long press landed on a highlight: the selection is that highlight. */
    val highlight: Highlight? = null,
)

private class ImageDrag(val page: PlacedPage, val original: MarginImage, val resize: Boolean, val start: Offset) {
    var current: MarginImage = original
}

/** A margin text box being moved, or widened from its corner (MRG-12). */
private class TextDrag(val page: PlacedPage, val original: MarginText, val resize: Boolean, val start: Offset) {
    var current: MarginText = original
}

private class LassoDrag(val start: Offset)

/** Resizing the lasso selection from its corner (INK-11): [bounds] on [page] when it started. */
private class ResizeDrag(val page: PlacedPage, val bounds: Rect, val start: Offset)

/** Turning a lasso selection by its handle above the outline (INK-11). */
private class RotateDrag(val page: PlacedPage, val centre: Offset, val start: Offset)

/**
 * Turns pen and finger input on one panel into drawing, erasing, selecting, panning and zooming.
 *
 * Coordinates:
 *  - "screen" = pixels inside the panel;
 *  - "strip" = Study Layout units in the panel's continuous strip of chapters. y = 0 is the top of
 *    the panel's current chapter; the previous chapter sits above it and the next one below;
 *  - "page" = Study Layout units inside one chapter's page (strip y minus the page's top).
 */
class ReaderController(private val vm: StudyViewModel, val panel: PanelState) {
    var panelIndex = 0
    /** Screen pixels per dp, for touch targets. */
    var density = 1f

    /** Laid-out chapters for this panel, keyed by [layoutKey]. Filled in by the panel as it loads. */
    val layouts = mutableStateMapOf<String, ChapterLayout>()
    private val geoCache = HashMap<ChapterLayout, PageGeometry>()

    var lastPageW = -1f
    var lastViewW = -1f
    var lastNavGen = -1
    /** Headings/spacing the cached layouts were built with. */
    var layoutSpec: String? = null

    var live by mutableStateOf<LiveInk?>(null)
        private set
    var lasso by mutableStateOf<LiveLasso?>(null)
        private set
    var selectedImageId by mutableStateOf<Long?>(null)
    var textSel by mutableStateOf<TextSel?>(null)
        private set
    /** Pen drag offset (page units) applied to the lasso selection while it is being moved. */
    var moveOffset by mutableStateOf(Offset.Zero)
    /** The scale of a selection being resized from its corner, and the corner it grows from. */
    var resizeScale by mutableFloatStateOf(1f)
    /** The turn (radians, clockwise) being dragged on the selection, for the outline preview. */
    var rotateAngle by mutableFloatStateOf(0f)
    private var selRotate: RotateDrag? = null
        private set
    private var selResize: ResizeDrag? = null
        private set
    /** Which margin edge is being dragged (true = left), or null. */
    var resizing by mutableStateOf<Boolean?>(null)
        private set

    private var mode: Tool? = null
    private var penPanLast: Offset? = null
    private var lastPenUp = Long.MIN_VALUE / 2 // "never", even right after the device boots
    private val erased = ArrayList<Annotation>()
    /** Pieces created by partial erasing during the current swipe (they may be cut again). */
    private val erasedAdded = LinkedHashMap<Long, Annotation>()
    private var drag: ImageDrag? = null
    private var lassoDrag: LassoDrag? = null


    // ---------- pages ----------

    fun layoutKey(version: String, book: Int, chapter: Int) = "$version|$book|$chapter"

    /** Expand-to-fit gaps per chapter layout (MRG-10), kept when a chapter is laid out again. */
    val spacers = HashMap<String, Map<Int, Float>>()

    private fun geoFor(book: Int, chapter: Int): PageGeometry? {
        val layout = layouts[layoutKey(panel.version, book, chapter)] ?: return null
        vm.sketchOf(book)?.let { sk ->
            // A sketch page: all drawing space, no text column or margins (SKT-1).
            val cached = geoCache[layout]
            if (cached != null && cached.height == sk.height) return cached
            return PageGeometry(layout, 0f, Sketch.WIDTH, colW = 0f, fixedHeight = sk.height).also { geoCache[layout] = it }
        }
        // Margins in every panel, or only the first (MRG-13).
        val margins = vm.marginsAllPanels || panelIndex == 0
        val lw = if (margins) vm.marginWidth(left = true) else 0f
        val rw = if (margins) vm.marginWidth(left = false) else 0f
        val cached = geoCache[layout]
        if (cached != null && cached.leftW == lw && cached.rightW == rw) return cached
        return PageGeometry(layout, lw, rw).also { geoCache[layout] = it }
    }

    /** Forget geometry for layouts that are no longer loaded. */
    fun dropUnused() {
        geoCache.keys.retainAll(layouts.values.toSet())
    }

    /** The current chapter's page, with the previous and next chapter above and below when loaded. */
    fun pages(): List<PlacedPage> {
        val cur = geoFor(panel.book, panel.chapter) ?: return emptyList()
        val out = ArrayList<PlacedPage>(3)
        vm.neighbor(panel.book, panel.chapter, -1)?.let { (b, c) ->
            geoFor(b, c)?.let { out.add(PlacedPage(it, -it.height)) }
        }
        out.add(PlacedPage(cur, 0f))
        vm.neighbor(panel.book, panel.chapter, 1)?.let { (b, c) ->
            geoFor(b, c)?.let { out.add(PlacedPage(it, cur.height)) }
        }
        return out
    }

    val geo: PageGeometry? get() = geoFor(panel.book, panel.chapter)

    private fun pageAt(y: Float, pages: List<PlacedPage> = pages()): PlacedPage? =
        pages.firstOrNull { y >= it.top && y < it.bottom }
            ?: pages.minByOrNull { if (y < it.top) it.top - y else y - it.bottom }

    fun highlightPath(h: Highlight, layout: ChapterLayout): Path = layout.highlightPath(h.id, h.start, h.end)

    /** Drawn points of a stroke in its region's local coordinates (display units). */
    fun strokePoints(s: InkStroke, layout: ChapterLayout): FloatArray =
        if (s.region == Region.TEXT) layout.render(s).points else s.points

    fun strokeRender(s: InkStroke, layout: ChapterLayout): StrokeRender =
        if (s.region == Region.TEXT) layout.render(s) else vm.render(s)

    fun touched() { vm.activePanel = panelIndex }

    fun recentlyPenned() = SystemClock.uptimeMillis() - lastPenUp < 600

    fun toStrip(p: Offset): Offset {
        val x = (p.x - panel.panX) / panel.zoom
        val y = (p.y - panel.panY) / panel.zoom
        // Over an open margin drawer, the pen is in the margin, not the text beneath (MRG-14).
        val g = geo
        if (g != null && drawer != null && drawerMode) {
            val right = drawerShift(Region.RIGHT)
            if (right < 0f && x >= g.colRight + right) return Offset(x - right, y)
            val left = drawerShift(Region.LEFT)
            if (left > 0f && x < g.leftW + left) return Offset(x - left, y)
        }
        return Offset(x, y)
    }

    // ---------- view transform ----------

    /**
     * A narrow panel in portrait (MRG-14): fit-width fits just the text column, and the margins
     * slide in from the side like drawers instead of shrinking the text.
     */
    val drawerMode: Boolean get() = !Sketch.isSketch(panel.book) && !vm.landscape && panel.viewW > 0f && panel.viewW / density < DRAWER_BELOW_DP

    private fun fitZoom(g: PageGeometry): Float {
        if (panel.viewW <= 0f) return 1f
        val w = if (drawerMode && (g.left || g.right)) Page.COL_W else g.width
        return panel.viewW / w
    }

    /** Where the page sits sideways with the text in view. */
    private fun textPanX(g: PageGeometry) = -g.leftW * panel.zoom

    /** The margin drawer showing over the text (MRG-14), and how far it has slid in (0 to 1). */
    var drawer by mutableStateOf<Region?>(null)
    var drawerT by mutableFloatStateOf(0f)

    /** Which margin drawer is open, if any. */
    val openDrawer: Region?
        get() = if (drawerMode && drawerT > 0.5f) drawer else null

    /**
     * How far (in page units) a margin is drawn from its place while its drawer slides over the
     * text: the right margin moves left, the left one right.
     */
    fun drawerShift(region: Region): Float {
        val g = geo ?: return 0f
        if (!drawerMode || drawer != region || drawerT <= 0f) return 0f
        return when (region) {
            Region.RIGHT -> -g.rightW * drawerT
            Region.LEFT -> g.leftW * drawerT
            Region.TEXT -> 0f
        }
    }

    /** Slides a margin drawer in over the text, or away again if it is already open. */
    suspend fun toggleDrawer(region: Region) {
        if (openDrawer == region) {
            androidx.compose.animation.core.animate(drawerT, 0f) { v, _ -> drawerT = v }
            drawer = null
        } else {
            if (drawer != null && drawer != region) drawerT = 0f
            drawer = region
            androidx.compose.animation.core.animate(drawerT, 1f) { v, _ -> drawerT = v }
        }
    }

    /** Zooms so the page fills the panel's width, keeping the same line at the top of the view. */
    fun fitWidth() {
        panel.zoomRel[vm.orientationKey] = 1f
        applyRememberedZoom()
    }

    /** Applies this panel's remembered zoom for the current orientation (relative to fit-width). */
    fun applyRememberedZoom() {
        val g = geo ?: return
        if (panel.viewW <= 0f) return
        val old = panel.zoom
        val new = fitZoom(g) * (panel.zoomRel[vm.orientationKey] ?: 1f)
        panel.panY *= new / old
        panel.panX *= new / old
        panel.zoom = new
        if (drawerMode) panel.panX = textPanX(g) // text in view, margins tucked away
        clamp()
    }

    /** Double-tap: fit-width if zoomed, otherwise back to the last zoom, centred on [at]. */
    fun toggleFit(at: Offset) {
        val g = geo ?: return
        val fit = fitZoom(g)
        val rel = panel.zoom / fit
        val target = if (abs(rel - 1f) < 0.02f) panel.lastZoomRel.coerceAtLeast(1.25f) else {
            panel.lastZoomRel = rel
            1f
        }
        transform(at, Offset.Zero, target * fit / panel.zoom)
    }

    fun transform(centroid: Offset, pan: Offset, zoomChange: Float) {
        val g = geo ?: return
        val fit = fitZoom(g)
        val old = panel.zoom
        val new = (old * zoomChange).coerceIn(fit * 0.6f, max(fit * 6f, 3f))
        val k = new / old
        panel.panX = centroid.x - (centroid.x - panel.panX) * k + pan.x
        panel.panY = centroid.y - (centroid.y - panel.panY) * k + pan.y
        panel.zoom = new
        if (zoomChange != 1f) panel.zoomRel[vm.orientationKey] = new / fit
        clamp()
        announceScroll()
    }

    // ---------- linked panels ----------

    /** Set while applying another panel's position, so this panel doesn't echo it back. */
    private var following = false
    /** A position to apply once this panel's chapter is laid out. */
    var pendingFollow: ScrollPos? = null

    /**
     * Tells a linked panel which verse is at the top of this one, and how far into it. Only the
     * active panel (the one being used) leads, so the two panels never pull each other back and forth.
     */
    fun announceScroll() {
        if (!vm.linked || following || vm.activePanel != panelIndex) return
        val topY = -panel.panY / panel.zoom
        val page = pageAt(topY) ?: return
        val layout = page.layout
        val y = topY - page.top
        val verse = layout.verseAtY(y)
        val (top, bottom) = layout.verseSpan(verse)
        vm.announceScroll(ScrollPos(panelIndex, layout.book, layout.chapter, verse, (y - top) / (bottom - top)))
    }

    /** Scrolls to the same verse (and point within it) as the linked panel. */
    fun follow(pos: ScrollPos) {
        if (pos.source == panelIndex) return
        following = true
        try {
            if (panel.book != pos.book || panel.chapter != pos.chapter) {
                panel.book = pos.book
                panel.chapter = pos.chapter
            }
            val g = geo
            if (g == null) {
                pendingFollow = pos // chapter still loading
                return
            }
            pendingFollow = null
            val (top, bottom) = g.layout.verseSpan(pos.verse)
            panel.panY = -(top + pos.frac * (bottom - top)) * panel.zoom
            clamp()
        } finally {
            following = false
        }
    }

    fun clamp() {
        val pages = pages()
        val cur = pages.firstOrNull { it.top == 0f } ?: return
        val z = panel.zoom
        if (drawerMode && (cur.geo.left || cur.geo.right)) {
            // Margins are drawers here (MRG-14): only the text column pans into view.
            val l = -cur.geo.leftW * z
            val r = panel.viewW - cur.geo.colRight * z
            panel.panX = if (r >= l) l else panel.panX.coerceIn(r, l)
        } else {
            val sw = cur.geo.width * z
            panel.panX = if (sw <= panel.viewW) (panel.viewW - sw) / 2f else panel.panX.coerceIn(panel.viewW - sw, 0f)
        }

        val stripTop = pages.first().top
        val stripBottom = pages.last().bottom
        val hi = -stripTop * z // strip top at the top of the view
        val lo = panel.viewH - stripBottom * z // strip bottom at the bottom of the view
        if (lo >= hi) {
            panel.panY = 0f // everything fits: show the current chapter from its top
        } else {
            panel.panY = panel.panY.coerceIn(lo, hi)
            // Re-anchor on whichever chapter is at the top of the view. Not mid-stroke: the pen's
            // coordinates would jump.
            if (mode == null && lasso == null) reanchor(cur)
        }
        val topY = -panel.panY / z
        pageAt(topY)?.let { panel.topVerse = it.layout.verseAtY(topY - it.top + 80f) }
        // How far into the current chapter the reader has seen (ANL-2).
        val bottomY = topY + panel.viewH / z
        val cur2 = pages.firstOrNull { it.top == 0f }
        if (cur2 != null) {
            val seen = if (bottomY >= cur2.bottom - 40f) Int.MAX_VALUE else cur2.layout.verseAtY(bottomY - cur2.top - 40f)
            val key = cur2.layout.book * 1000 + cur2.layout.chapter
            if (key != seenKey) { seenKey = key; panel.seenTo = 0 }
            if (seen > panel.seenTo) panel.seenTo = seen
        }
    }
    private var seenKey = -1

    /** Continuous scrolling: when the view's top edge leaves the current chapter, make its neighbour current. */
    private fun reanchor(cur: PlacedPage) {
        val z = panel.zoom
        val topY = -panel.panY / z
        if (topY >= cur.bottom) {
            val next = vm.neighbor(panel.book, panel.chapter, 1) ?: return
            if (geoFor(next.first, next.second) == null) return
            panel.panY += cur.geo.height * z
            vm.shiftChapter(panel, 1)
        } else if (topY < 0f) {
            val prev = vm.neighbor(panel.book, panel.chapter, -1) ?: return
            val g = geoFor(prev.first, prev.second) ?: return
            panel.panY -= g.height * z
            vm.shiftChapter(panel, -1)
        }
    }

    /** Scrolls so that [verse] of the current chapter is near the top of the view. */
    fun scrollToVerse(verse: Int) {
        val g = geo ?: return
        panel.panY = -(g.layout.verseTop(verse) - 40f) * panel.zoom
        clamp()
    }

    // ---------- text selection (long press) ----------

    private fun textPoint(pos: Offset): Pair<PlacedPage, Offset>? {
        val s = toStrip(pos)
        val page = pageAt(s.y) ?: return null
        return page to Offset(s.x - page.geo.textLeft, s.y - page.top - Page.TEXT_TOP)
    }

    /** Selects the word under a long-pressed finger. Returns false if the finger isn't on the text. */
    fun startTextSelect(pos: Offset): Boolean {
        val (page, local) = textPoint(pos) ?: return false
        val layout = page.layout
        if (page.geo.regionAt(local.x + page.geo.textLeft) != Region.TEXT || !layout.isOnText(local.y)) return false
        val offset = layout.offsetAt(local.x, local.y)
        // On a highlight (on a visible layer), select the whole highlight so it can be changed or removed.
        val visible = vm.layers.filter { it.visible }.mapTo(HashSet()) { it.id }
        val h = vm.highlightsFor(layout.version, layout.book, layout.chapter)
            .lastOrNull { it.layerId in visible && offset >= it.start && offset <= it.end }
        if (h != null) {
            textSel = TextSel(layout, h.start until h.end, h.start, h.end, h)
            return true
        }
        // A highlight from another translation covers whole verses here (HL-10); it can be changed too.
        for (x in vm.crossHighlights(layout.version, layout.book, layout.chapter).asReversed()) {
            if (x.source.layerId !in visible) continue
            val r = layout.versesRange(x.fromVerse, x.toVerse) ?: continue
            if (offset in r) {
                textSel = TextSel(layout, r, r.first, r.last + 1, x.source)
                return true
            }
        }
        val w = layout.text.getWordBoundary(offset)
        if (w.end <= w.start) return false
        textSel = TextSel(layout, w.start until w.end, w.start, w.end)
        return true
    }

    /** Extends the selection to the word under the finger, in either direction from the first word. */
    fun extendTextSelect(pos: Offset) {
        val ts = textSel ?: return
        val (page, local) = textPoint(pos) ?: return
        if (page.layout !== ts.layout) return // selections stay within one chapter
        val w = ts.layout.text.getWordBoundary(ts.layout.offsetAt(local.x, local.y.coerceIn(0f, ts.layout.displayHeight)))
        val start = minOf(ts.anchor.first, w.start)
        val end = maxOf(ts.anchor.last + 1, w.end)
        // Dragging past a highlight turns it into an ordinary selection of more words.
        val h = ts.highlight?.takeIf { start == ts.anchor.first && end == ts.anchor.last + 1 }
        textSel = ts.copy(start = start, end = end, highlight = h)
    }

    /** Keeps the selection pointing at a highlight after it was recoloured. */
    fun updateSelectedHighlight(h: Highlight) {
        textSel = textSel?.copy(highlight = h)
    }

    fun clearTextSelect() { textSel = null }

    /** "John 3:16\u201317 (KJV)" for the selected verses. */
    fun selectionLabel(ts: TextSel): String {
        val l = ts.layout
        val a = l.verseAtOffset(ts.start)
        val b = l.verseAtOffset((ts.end - 1).coerceAtLeast(ts.start))
        return vm.refLabel(VerseId.of(l.book, l.chapter, a), VerseId.of(l.book, l.chapter, b)) + " (${l.version})"
    }

    /** The selected words with their reference, for copying or sharing. */
    fun selectionText(ts: TextSel): String =
        selectionLabel(ts) + "\n" + ts.layout.textOf(ts.start, ts.end).replace('\u2009', ' ').trim()

    // ---------- margin resizing ----------

    /** Screen x of a margin's inner edge (where the grip is drawn), or null if that margin is hidden. */
    fun marginEdgeX(left: Boolean): Float? {
        val g = geo ?: return null
        if (g.sketch) return null // a sketch page has no margins to resize
        if (left && !g.left || !left && !g.right) return null
        val edge = if (left) g.leftW else g.colRight
        return panel.panX + (edge + drawerShift(if (left) Region.LEFT else Region.RIGHT)) * panel.zoom
    }

    /** Which margin grip (if any) is under a finger at [pos]. The grip sits halfway down the panel. */
    fun marginGripAt(pos: Offset): Boolean? {
        val halfW = 28f * density
        val halfH = 56f * density
        if (abs(pos.y - panel.viewH / 2f) > halfH) return null
        for (left in listOf(true, false)) {
            val x = marginEdgeX(left) ?: continue
            if (abs(pos.x - x) <= halfW) return left
        }
        return null
    }

    fun startResize(left: Boolean) {
        resizing = left
    }

    /**
     * Moves a margin's inner edge to follow the finger. The page is kept at fit-width, so the text
     * column shrinks or grows on screen while the margin takes up the rest.
     */
    fun resizeTo(screenX: Float) {
        val left = resizing ?: return
        val g = geo ?: return
        val w = panel.viewW
        val x = screenX.coerceIn(w * 0.1f, w * 0.95f)
        val newW = if (left) {
            val rest = Page.COL_W + g.rightW
            x * rest / (w - x)
        } else {
            g.colRight * (w - x) / x
        }
        vm.setMarginWidth(left, newW)
        fitWidth()
    }

    fun endResize() {
        resizing = null
        // The page was re-fitted while dragging; record that so the panel doesn't fit it again.
        geo?.let { lastPageW = it.width }
        lastViewW = panel.viewW
        clamp()
        vm.savePrefs()
    }

    // ---------- finger tap ----------

    fun onTap(pos: Offset) {
        if (textSel != null) { textSel = null; return } // a tap away from the selection clears it
        val s = toStrip(pos)
        val page = pageAt(s.y) ?: return
        val g = page.geo
        if (tapText(page, Offset(s.x, s.y - page.top), pos)) return
        // A sketch page's badge opens it (SKT-2).
        if (!g.sketch) {
            val local = Offset(s.x, s.y - page.top)
            vm.sketchesIn(g.layout.book, g.layout.chapter).firstOrNull {
                (sketchBadgeCenter(g, g.layout, it) - local).getDistance() < 30f
            }?.let { vm.openSketch(it, panelIndex); return }
        }
        if (g.regionAt(s.x) != Region.TEXT) return
        val localY = s.y - page.top - Page.TEXT_TOP
        // A parallel-passage link under a heading opens its pop-over (LINK-1, LINK-2).
        g.layout.headingLinkAt(s.x - g.textLeft, localY)?.let { link ->
            vm.passagePop = PassagePop(panelIndex, link.passage, pos)
            return
        }
        if (!g.layout.isOnText(localY)) return
        val off = g.layout.offsetAt(s.x - g.textLeft, localY)
        vm.openVerse(g.layout.book, g.layout.chapter, g.layout.verseAtOffset(off), g.layout.wordAt(off))
    }

    // ---------- pen ----------

    fun sideButtonTool(): Tool? = vm.sideButton.tool

    /** [override] replaces the selected tool for this stroke (eraser end or side button). */
    /** The front-buffered layer for fast pen ink (INK-4), when the device supports it. */
    var fastInk: FastInkView? = null
    /** Whether the stroke being written is drawn on [fastInk] (and so not by the live layer). */
    var fastStroke = false
        private set
    private var lastPos = Offset.Zero

    /** Draws the newest piece of a fast stroke, in the panel's pixels. */
    private fun fastSegment(to: Offset, pressure: Float) {
        val ink = live ?: return
        val f = fastInk ?: return
        val w = penWidth(ink.width, pressure) * panel.zoom
        f.draw(InkSegment(lastPos.x, lastPos.y, to.x, to.y, w, ink.color))
        lastPos = to
    }

    fun penStart(pos: Offset, pressure: Float, override: Tool?) {
        fastStroke = false
        touched()
        textSel = null
        val s = toStrip(pos)
        val page = pageAt(s.y) ?: return
        val g = page.geo
        val p = Offset(s.x, s.y - page.top)

        // A pen touching inside the lasso selection moves it, whatever tool is picked.
        val sel = vm.selection
        if (sel != null && override != Tool.ERASER) {
            val selPage = pages().firstOrNull { sel.isOn(it.layout) }
            val b = selPage?.let { selectionBounds(it) }
            val local = selPage?.let { Offset(s.x, s.y - it.top) }
            // The corner handle resizes the selection (INK-11).
            // The handle above it turns it.
            if (b != null && local != null && (local - rotateHandle(b)).getDistance() < 36f / panel.zoom) {
                mode = Tool.LASSO
                selRotate = RotateDrag(selPage, b.center, local)
                rotateAngle = 0f
                return
            }
            if (b != null && local != null && (local - b.inflate(10f / panel.zoom).bottomRight).getDistance() < 36f / panel.zoom) {
                mode = Tool.LASSO
                selResize = ResizeDrag(selPage, b, local)
                resizeScale = 1f
                return
            }
            if (selPage != null && b?.inflate(12f / panel.zoom)?.contains(Offset(s.x, s.y - selPage.top)) == true) {
                mode = Tool.LASSO
                lassoDrag = LassoDrag(Offset(s.x, s.y - selPage.top))
                return
            }
            vm.clearSelection()
        }

        val tool = override ?: vm.tool
        mode = tool
        when (tool) {
            Tool.PEN, Tool.HIGHLIGHTER -> {
                val layer = vm.activeLayer()
                if (layer == null) { mode = null; return }
                if (layer.locked) {
                    vm.message = "Layer \u201c${layer.name}\u201d is locked. Unlock it in Layers to draw."
                    mode = null
                    return
                }
                if (!layer.visible) {
                    vm.setLayerVisible(layer.id, true)
                    vm.message = "Showing layer \u201c${layer.name}\u201d."
                }
                val region = g.regionAt(p.x)
                val verse = g.layout.verseAtY(p.y)
                val hl = tool == Tool.HIGHLIGHTER
                val ink = LiveInk(
                    page = page, region = region, verse = verse,
                    ox = g.originX(region), oy = g.originY(region, verse),
                    highlighter = hl,
                    color = if (hl) vm.highlightColor else vm.penColor,
                    width = vm.currentWidth(hl),
                    layerId = layer.id,
                )
                ink.add(p.x - ink.ox, p.y - ink.oy, pressure)
                live = ink
                // Pen ink goes straight to the screen; the translucent highlighter stays on the page,
                // where overlapping pieces don't darken.
                val f = fastInk
                if (!hl && vm.fastInk && f != null && f.ready) {
                    fastStroke = true
                    f.startStroke()
                    lastPos = pos
                    fastSegment(pos + Offset(0.1f, 0f), pressure)
                }
            }
            Tool.ERASER -> {
                erased.clear()
                erasedAdded.clear()
                eraseAt(s)
            }
            Tool.LASSO -> {
                val l = LiveLasso(page)
                l.add(p)
                lasso = l
            }
            Tool.SELECT -> startSelect(page, p, pos)
        }
        // Writing sounds (INK-15) for the tools that touch the paper.
        val texture = when (mode) {
            Tool.PEN -> WritingSound.Texture.PEN
            Tool.HIGHLIGHTER -> WritingSound.Texture.HIGHLIGHTER
            Tool.ERASER -> WritingSound.Texture.ERASER
            else -> null
        }
        if (texture != null) vm.sound.start(texture, pos.x, pos.y, android.os.SystemClock.uptimeMillis())
    }

    /** The pen moved at [timeMs]: the writing sound follows its speed and pressure (INK-15). */
    fun soundMove(pos: Offset, pressure: Float, timeMs: Long, density: Float) {
        if (mode == Tool.PEN || mode == Tool.HIGHLIGHTER || mode == Tool.ERASER) vm.sound.move(pos.x, pos.y, pressure, timeMs, density)
    }

    fun penMove(pos: Offset, pressure: Float) {
        val s = toStrip(pos)
        when (mode) {
            Tool.PEN, Tool.HIGHLIGHTER -> live?.let {
                it.add(s.x - it.ox, s.y - it.page.top - it.oy, pressure)
                if (fastStroke) fastSegment(pos, pressure)
            }
            Tool.ERASER -> eraseAt(s)
            Tool.LASSO -> {
                val d = lassoDrag
                val l = lasso
                val rz = selResize
                val rt = selRotate
                if (rt != null) {
                    val local = Offset(s.x, s.y - rt.page.top)
                    rotateAngle = snapAngle(angleOf(local - rt.centre) - angleOf(rt.start - rt.centre))
                } else if (rz != null) {
                    val local = Offset(s.x, s.y - rz.page.top)
                    val from = (rz.start - rz.bounds.topLeft).getDistance()
                    if (from > 1f) resizeScale = ((local - rz.bounds.topLeft).getDistance() / from).coerceIn(0.2f, 5f)
                } else if (d != null) {
                    val selPage = vm.selection?.let { sel -> pages().firstOrNull { sel.isOn(it.layout) } }
                    if (selPage != null) moveOffset = Offset(s.x, s.y - selPage.top) - d.start
                } else if (l != null) {
                    l.add(Offset(s.x, s.y - l.page.top))
                }
            }
            Tool.SELECT -> moveSelect(s, pos)
            null -> {}
        }
    }

    /**
     * The pen has been held still while drawing (INK-12): a pen stroke snaps to a line, arrow, box
     * or oval if it looks like one.
     */
    fun penHold() {
        if (mode != Tool.PEN) return
        val ink = live ?: return
        if (ink.frozen) return
        val (_, shape) = ShapeSnap.recognize(ink.toArray()) ?: return
        ink.replaceAll(shape)
        if (fastStroke) {
            // The freehand stroke on the fast layer gives way to the clean shape on the live layer.
            fastStroke = false
            fastInk?.clearNow()
        }
    }

    fun penEnd() {
        vm.sound.stop()
        lastPenUp = SystemClock.uptimeMillis()
        when (mode) {
            Tool.PEN, Tool.HIGHLIGHTER -> finishStroke()
            Tool.ERASER -> {
                // One undo step per swipe: pieces left over by partial erasing, and what was removed.
                if (erased.isNotEmpty() || erasedAdded.isNotEmpty()) vm.record(Edit(erasedAdded.values.toList(), erased.toList()))
                erased.clear()
                erasedAdded.clear()
            }
            Tool.LASSO -> {
                val rz = selResize
                val rt = selRotate
                if (rt != null) {
                    selRotate = null
                    val a = rotateAngle
                    rotateAngle = 0f
                    vm.rotateSelection(a, rt.centre, rt.page.geo)
                } else if (rz != null) {
                    selResize = null
                    val k = resizeScale
                    resizeScale = 1f
                    vm.scaleSelection(k, rz.bounds.topLeft, rz.page.geo)
                } else if (lassoDrag != null) {
                    val off = moveOffset
                    lassoDrag = null
                    moveOffset = Offset.Zero
                    val selPage = vm.selection?.let { sel -> pages().firstOrNull { sel.isOn(it.layout) } }
                    if (off.getDistance() > 0.5f && selPage != null) vm.moveSelection(off, selPage.layout)
                } else {
                    lasso?.let { finishLasso(it) }
                    lasso = null
                }
            }
            Tool.SELECT -> {
                drag?.let { vm.commitImageChange(it.original, it.current) }
                drag = null
                textDrag?.let { vm.commitTextChange(it.original, it.current) }
                textDrag = null
                penPanLast = null
            }
            null -> {}
        }
        mode = null
    }

    private fun finishStroke() {
        val ink = live ?: return
        live = null
        if (fastStroke) {
            fastStroke = false
            fastInk?.clearSoon() // the page draws the saved stroke; then the fast layer clears
        }
        var pts = ink.toArray()
        if (pts.size == 3) pts = floatArrayOf(pts[0], pts[1], pts[2], pts[0] + 0.5f, pts[1], pts[2])
        val layout = ink.page.layout

        if (ink.highlighter && vm.snapHighlights && ink.region == Region.TEXT) {
            val h = snapHighlight(layout, pts, ink)
            if (h != null) {
                vm.addItem(h)
                vm.record(Edit(listOf(h), emptyList()))
                return
            }
        }
        val s = InkStroke(
            id = vm.newId(), layerId = ink.layerId,
            version = if (ink.region == Region.TEXT) layout.version else null,
            book = layout.book, chapter = layout.chapter,
            region = ink.region, verse = ink.verse,
            highlighter = ink.highlighter, color = ink.color, width = ink.width,
            points = if (ink.region == Region.TEXT) layout.linePoints(pts) else pts,
            font = vm.styleKey().encode(),
        )
        vm.addItem(s)
        vm.record(Edit(listOf(s), emptyList()))
    }

    /** Snaps a highlighter swipe to whole words on the text lines it crosses. */
    private fun snapHighlight(layout: ChapterLayout, pts: FloatArray, ink: LiveInk): Highlight? {
        val t = layout.text
        val h = layout.displayHeight
        val a = Offset(pts[0], pts[1])
        val b = Offset(pts[pts.size - 3], pts[pts.size - 2])
        if (a.y < 0f || a.y > h || b.y < 0f || b.y > h) return null
        var s = layout.offsetAt(a.x, a.y)
        var e = layout.offsetAt(b.x, b.y)
        if (s > e) { val tmp = s; s = e; e = tmp }
        val start = t.getWordBoundary(s.coerceIn(0, layout.textLength)).start
        val end = t.getWordBoundary(e.coerceIn(0, layout.textLength)).end
        if (end - start < 1) return null
        return Highlight(
            id = vm.newId(), layerId = ink.layerId, version = layout.version,
            book = layout.book, chapter = layout.chapter, start = start, end = end, color = ink.color,
            underline = vm.underlineMode,
        )
    }

    // ---------- eraser ----------

    private fun eraseAt(s: Offset) {
        val page = pageAt(s.y) ?: return
        val g = page.geo
        val p = Offset(s.x, s.y - page.top)
        val layout = g.layout
        val usable = vm.usableLayerIds()
        val r = max(8f, 20f / panel.zoom)

        fun hits(st: InkStroke, ox: Float, oy: Float): Boolean {
            val pts = strokePoints(st, layout)
            val reach = r + st.width / 2f
            val reach2 = reach * reach
            val lx = p.x - ox; val ly = p.y - oy
            val n = pts.size / 3
            if (n == 1) return (pts[0] - lx) * (pts[0] - lx) + (pts[1] - ly) * (pts[1] - ly) <= reach2
            for (i in 1 until n) {
                if (distSqToSegment(lx, ly, pts[3 * i - 3], pts[3 * i - 2], pts[3 * i], pts[3 * i + 1]) <= reach2) return true
            }
            return false
        }

        val partial = vm.partialEraser
        fun erase(st: InkStroke, ox: Float, oy: Float) {
            if (!hits(st, ox, oy)) return
            val pieces = if (partial) cut(st, layout, p.x - ox, p.y - oy, r) else emptyList()
            eraseItem(st)
            pieces.forEach { vm.addItem(it); erasedAdded[it.id] = it }
        }

        for (st in vm.textStrokesFor(layout.version, layout.book, layout.chapter).toList()) {
            if (st.layerId in usable) erase(st, g.textLeft, Page.TEXT_TOP)
        }
        for (st in vm.marginStrokesFor(layout.book, layout.chapter).toList()) {
            if (st.layerId in usable && g.visible(st.region)) erase(st, g.originX(st.region), g.originY(st.region, st.verse))
        }
        val local = Offset(p.x - g.textLeft, p.y - Page.TEXT_TOP)
        if (layout.isOnText(local.y) && local.x >= -r && local.x <= Page.TEXT_W + r) {
            val off = layout.offsetAt(local.x, local.y)
            for (h in vm.highlightsFor(layout.version, layout.book, layout.chapter).toList()) {
                if (h.layerId !in usable || off < h.start || off > h.end) continue
                eraseItem(h)
                if (partial) {
                    // Take out just the word under the eraser.
                    val word = layout.text.getWordBoundary(off.coerceIn(0, layout.textLength))
                    listOf(h.start to word.start, word.end to h.end).filter { (a, b) -> b - a > 1 }.forEach { (a, b) ->
                        val piece = h.copy(id = vm.newId(), start = a, end = b)
                        vm.addItem(piece)
                        erasedAdded[piece.id] = piece
                    }
                }
            }
            // A highlight made in another version shows here over whole verses (HL-10). Erasing it
            // here erases it there too: all of it, or with the partial eraser just this verse of it.
            val verse = layout.verseAtOffset(off)
            for (x in vm.crossHighlights(layout.version, layout.book, layout.chapter)) {
                val h = x.source
                if (h.layerId !in usable || verse !in x.fromVerse..x.toVerse) continue
                eraseItem(h)
                if (partial) {
                    val span = vm.verseSpan(h.version, h.book, h.chapter, verse) ?: continue
                    listOf(h.start to minOf(h.end, span.first), maxOf(h.start, span.last + 1) to h.end)
                        .filter { (a, b) -> b - a > 1 }.forEach { (a, b) ->
                            val piece = h.copy(id = vm.newId(), start = a, end = b)
                            vm.addItem(piece)
                            erasedAdded[piece.id] = piece
                        }
                }
            }
        }
    }

    /** Removes an item for this swipe's undo step (a piece made earlier in the swipe just disappears). */
    private fun eraseItem(a: Annotation) {
        vm.removeItem(a)
        if (erasedAdded.remove(a.id) == null) erased += a
    }

    /**
     * Partial erase: the parts of [st] outside the eraser circle at local ([lx], [ly]) with radius [r],
     * as new strokes. Points inside the circle are dropped, and the stroke is also split where a
     * segment passes through the circle.
     */
    private fun cut(st: InkStroke, layout: ChapterLayout, lx: Float, ly: Float, r: Float): List<InkStroke> {
        val pts = strokePoints(st, layout)
        val n = pts.size / 3
        val reach = r + st.width / 2f
        val reach2 = reach * reach
        fun inside(i: Int) = (pts[3 * i] - lx).let { it * it } + (pts[3 * i + 1] - ly).let { it * it } <= reach2
        val runs = ArrayList<FloatArray>()
        var start = -1
        fun close(end: Int) {
            if (start >= 0 && end - start >= 2) runs += pts.copyOfRange(3 * start, 3 * end)
            start = -1
        }
        for (i in 0 until n) {
            if (inside(i)) { close(i); continue }
            if (start >= 0 && distSqToSegment(lx, ly, pts[3 * i - 3], pts[3 * i - 2], pts[3 * i], pts[3 * i + 1]) <= reach2) close(i)
            if (start < 0) start = i
        }
        close(n)
        return runs.map { run ->
            st.copyAs(id = vm.newId(), points = if (st.region == Region.TEXT) layout.linePoints(run) else run)
        }
    }

    // ---------- lasso ----------

    private fun finishLasso(l: LiveLasso) {
        if (l.points.size < 3) return
        val poly = l.points
        val g = l.page.geo
        val layout = g.layout
        val usable = vm.usableLayerIds()
        val picked = ArrayList<Annotation>()

        fun strokeInside(st: InkStroke, ox: Float, oy: Float): Boolean {
            val pts = strokePoints(st, layout)
            val n = pts.size / 3
            if (n == 0) return false
            var inside = 0
            for (i in 0 until n) if (pointInPolygon(pts[3 * i] + ox, pts[3 * i + 1] + oy, poly)) inside++
            return inside * 10 >= n * 6 // most of the stroke is inside the loop
        }

        for (st in vm.textStrokesFor(layout.version, layout.book, layout.chapter)) {
            if (st.layerId in usable && strokeInside(st, g.textLeft, Page.TEXT_TOP)) picked += st
        }
        for (st in vm.marginStrokesFor(layout.book, layout.chapter)) {
            if (st.layerId in usable && g.visible(st.region) && strokeInside(st, g.originX(st.region), g.originY(st.region, st.verse))) {
                picked += st
            }
        }
        for (h in vm.highlightsFor(layout.version, layout.book, layout.chapter)) {
            if (h.layerId !in usable) continue
            val len = layout.textLength
            val a = h.start.coerceIn(0, len); val b = h.end.coerceIn(0, len)
            if (b <= a) continue
            val step = max(1, (b - a) / 24)
            var total = 0; var inside = 0
            for (i in a until b step step) {
                val c = layout.charCenter(i)
                total++
                if (pointInPolygon(c.x + g.textLeft, c.y + Page.TEXT_TOP, poly)) inside++
            }
            if (total > 0 && inside * 2 >= total) picked += h
        }
        for (img in vm.imagesFor(layout.book, layout.chapter)) {
            if (img.layerId !in usable || !g.visible(img.region)) continue
            val c = imageRect(g, img).center
            if (pointInPolygon(c.x, c.y, poly)) picked += img
        }
        for (t in vm.textsFor(layout.book, layout.chapter)) {
            if (t.layerId !in usable || !g.visible(t.region)) continue
            val c = textRect(g, t).center
            if (pointInPolygon(c.x, c.y, poly)) picked += t
        }
        if (picked.isEmpty()) {
            vm.message = "Nothing inside the lasso. Draw a loop around ink, highlights or images."
            return
        }
        vm.select(Selection(layout.version, layout.book, layout.chapter, picked.map { it.id }.toSet()))
    }

    /** Bounds of the selected items in page units on [page]. */
    fun selectionBounds(page: PlacedPage): Rect? {
        val sel = vm.selection ?: return null
        val g = page.geo
        val layout = g.layout
        var r: Rect? = null
        fun add(b: Rect) { r = r?.let { Rect(minOf(it.left, b.left), minOf(it.top, b.top), maxOf(it.right, b.right), maxOf(it.bottom, b.bottom)) } ?: b }
        for (a in vm.selectedItems(sel)) {
            when (a) {
                is InkStroke -> if (a.region == Region.TEXT || g.visible(a.region)) {
                    add(strokeRender(a, layout).bounds.translate(g.originX(a.region), g.originY(a.region, a.verse)))
                }
                is Highlight -> add(highlightPath(a, layout).getBounds().translate(g.textLeft, Page.TEXT_TOP))
                is MarginImage -> if (g.visible(a.region)) add(imageRect(g, a))
                is MarginText -> if (g.visible(a.region)) add(textRect(g, a))
            }
        }
        return r
    }

    // ---------- select (images) ----------

    fun imageRect(g: PageGeometry, img: MarginImage): Rect {
        val ox = g.originX(img.region); val oy = g.originY(img.region, img.verse)
        return Rect(ox + img.x, oy + img.y, ox + img.x + img.w, oy + img.y + img.h)
    }

    // ---------- margin text boxes (MRG-12) ----------

    /** The text box picked by a tap, showing its bar. */
    var selectedTextId by mutableStateOf<Long?>(null)
    private var textDrag: TextDrag? = null

    fun textRect(g: PageGeometry, t: MarginText): Rect {
        val ox = g.originX(t.region); val oy = g.originY(t.region, t.verse)
        val h = vm.textHeights[t.id] ?: (t.size * 1.6f)
        return Rect(ox + t.x, oy + t.y, ox + t.x + t.w, oy + t.y + h)
    }

    private fun textAt(page: PlacedPage, p: Offset, layers: Set<Long>): MarginText? {
        val g = page.geo
        return vm.textsFor(g.layout.book, g.layout.chapter).lastOrNull {
            it.layerId in layers && g.visible(it.region) && textRect(g, it).inflate(6f / panel.zoom).contains(p)
        }
    }

    /** The passage linked at a point inside a text box, if the point is on a Bible reference. */
    private fun textLinkAt(t: MarginText, local: Offset): Passage? {
        val layout = vm.textLayouts[t.id] ?: return null
        val inner = local - Offset(TEXT_PAD, TEXT_PAD)
        if (inner.y < 0f || inner.y > layout.size.height) return null
        val off = layout.getOffsetForPosition(inner)
        return RefLinks.find(t.text, vm.bible.books).firstOrNull { off >= it.start && off < it.end }?.passage
    }

    /**
     * A finger tap on a text box: a reference opens its passage pop-over; otherwise the first tap
     * selects the box and a second starts typing in it. Returns false if no box was tapped.
     */
    private fun tapText(page: PlacedPage, p: Offset, pos: Offset): Boolean {
        val hit = textAt(page, p, vm.visibleLayerIds().toSet())
        if (hit == null) {
            // A tap away from a box ends typing in it, or clears its selection.
            if (vm.editingText != null || selectedTextId != null) {
                vm.editingText = null
                selectedTextId = null
                return true
            }
            return false
        }
        val r = textRect(page.geo, hit)
        if (vm.editingText != hit.id) {
            textLinkAt(hit, p - r.topLeft)?.let {
                vm.passagePop = PassagePop(panelIndex, it, pos)
                return true
            }
        }
        if (selectedTextId == hit.id && vm.editingText == null) vm.editingText = hit.id
        selectedTextId = hit.id
        return true
    }

    /** The selected text box and the page it is on, if visible in this panel. */
    fun selectedText(): Pair<PlacedPage, MarginText>? {
        val id = vm.editingText ?: selectedTextId ?: return null
        for (page in pages()) {
            val t = vm.textsFor(page.layout.book, page.layout.chapter).firstOrNull { it.id == id }
            if (t != null) return page to t
        }
        return null
    }

    private fun startSelect(page: PlacedPage, p: Offset, screen: Offset) {
        val g = page.geo
        val layout = g.layout
        val usable = vm.usableLayerIds()
        textAt(page, p, usable)?.let { t ->
            selectedTextId = t.id
            selectedImageId = null
            val rect = textRect(g, t)
            val handle = hypot(p.x - rect.right, p.y - rect.bottom) < 40f / panel.zoom
            textDrag = TextDrag(page, t, handle, p)
            return
        }
        val hit = vm.imagesFor(layout.book, layout.chapter).lastOrNull {
            it.layerId in usable && g.visible(it.region) && imageRect(g, it).inflate(4f / panel.zoom).contains(p)
        }
        if (hit == null) {
            selectedImageId = null
            penPanLast = screen // pen pans the page when nothing is selected
            return
        }
        selectedImageId = hit.id
        val rect = imageRect(g, hit)
        val handle = hypot(p.x - rect.right, p.y - rect.bottom) < 40f / panel.zoom
        drag = ImageDrag(page, hit, handle, p)
    }

    private fun moveSelect(s: Offset, screen: Offset) {
        textDrag?.let { td ->
            val dx = s.x - td.start.x; val dy = s.y - td.page.top - td.start.y
            val o = td.original
            td.current = if (td.resize) o.copy(w = max(80f, o.w + dx)) else o.copy(x = o.x + dx, y = o.y + dy)
            vm.replaceTextLive(td.current)
            return
        }
        val d = drag
        if (d == null) {
            val last = penPanLast ?: return
            transform(screen, screen - last, 1f)
            penPanLast = screen
            return
        }
        val dx = s.x - d.start.x; val dy = s.y - d.page.top - d.start.y
        val o = d.original
        d.current = if (d.resize) {
            val w = max(60f, o.w + dx)
            o.copy(w = w, h = w * (o.h / o.w))
        } else {
            o.copy(x = o.x + dx, y = o.y + dy)
        }
        vm.replaceImageLive(d.current)
    }

    /** The selected image and the page it is on, if it is still visible in this panel. */
    fun selectedImage(): Pair<PlacedPage, MarginImage>? {
        val id = selectedImageId ?: return null
        for (page in pages()) {
            val img = vm.imagesFor(page.layout.book, page.layout.chapter).firstOrNull { it.id == id }
            if (img != null) return page to img
        }
        return null
    }

    /** The part of the strip visible in this panel, in strip units. */
    fun visibleRect(): Rect {
        val l = -panel.panX / panel.zoom
        val t = -panel.panY / panel.zoom
        return Rect(l, t, l + panel.viewW / panel.zoom, t + panel.viewH / panel.zoom)
    }

    /** Where the turn handle sits: just above the middle of the selection's outline. */
    fun rotateHandle(b: Rect): Offset = Offset(b.center.x, b.top - 10f / panel.zoom - 36f / panel.zoom)

    private fun angleOf(v: Offset) = kotlin.math.atan2(v.y, v.x)

    /** Wraps to -π..π and settles on 15° steps when close to one. */
    private fun snapAngle(a: Float): Float {
        val pi = Math.PI.toFloat()
        var x = a
        while (x > pi) x -= 2 * pi
        while (x < -pi) x += 2 * pi
        val step = pi / 12f
        val snapped = kotlin.math.round(x / step) * step
        return if (kotlin.math.abs(x - snapped) < pi / 36f) snapped else x
    }

    companion object {
        /**
         * Where a sketch page's badge sits on its passage's page: in the right margin beside its
         * verse when the margin is shown, otherwise just left of the text.
         */
        fun sketchBadgeCenter(g: PageGeometry, layout: ChapterLayout, s: Sketch): Offset {
            val y = layout.verseTop(s.linkVerse.coerceAtLeast(1)) + 22f
            return if (g.right) Offset(g.colRight + 26f, y) else Offset(g.textLeft - 34f, y + 34f)
        }

        /** Space between a text box's edge and its text, in page units. */
        const val TEXT_PAD = 8f

        /** Panels narrower than this in portrait show the margins as drawers (MRG-14). */
        const val DRAWER_BELOW_DP = 600f

        fun overlaps(a: Rect, b: Rect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

        /** Even-odd rule point-in-polygon test. */
        fun pointInPolygon(x: Float, y: Float, poly: List<Offset>): Boolean {
            var inside = false
            var j = poly.lastIndex
            for (i in poly.indices) {
                val a = poly[i]; val b = poly[j]
                if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
                j = i
            }
            return inside
        }
    }
}
