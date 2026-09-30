package com.biblestudy.app.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.biblestudy.app.model.Region
import com.biblestudy.app.model.Tool
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

private class ImageDrag(val page: PlacedPage, val original: MarginImage, val resize: Boolean, val start: Offset) {
    var current: MarginImage = original
}

private class LassoDrag(val start: Offset)

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
    /** Pen drag offset (page units) applied to the lasso selection while it is being moved. */
    var moveOffset by mutableStateOf(Offset.Zero)
        private set
    /** Which margin edge is being dragged (true = left), or null. */
    var resizing by mutableStateOf<Boolean?>(null)
        private set

    private var mode: Tool? = null
    private var penPanLast: Offset? = null
    private var lastPenUp = Long.MIN_VALUE / 2 // "never", even right after the device boots
    private val erased = ArrayList<Annotation>()
    private var drag: ImageDrag? = null
    private var lassoDrag: LassoDrag? = null


    // ---------- pages ----------

    fun layoutKey(version: String, book: Int, chapter: Int) = "$version|$book|$chapter"

    private fun geoFor(book: Int, chapter: Int): PageGeometry? {
        val layout = layouts[layoutKey(panel.version, book, chapter)] ?: return null
        val lw = vm.marginWidth(left = true)
        val rw = vm.marginWidth(left = false)
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

    fun toStrip(p: Offset) = Offset((p.x - panel.panX) / panel.zoom, (p.y - panel.panY) / panel.zoom)

    // ---------- view transform ----------

    private fun fitZoom(g: PageGeometry) = if (panel.viewW > 0f) panel.viewW / g.width else 1f

    /** Zooms so the page fills the panel's width, keeping the same line at the top of the view. */
    fun fitWidth() {
        val g = geo ?: return
        if (panel.viewW <= 0f) return
        val old = panel.zoom
        val new = fitZoom(g)
        panel.panY *= new / old
        panel.zoom = new
        panel.panX = 0f
        clamp()
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
        clamp()
    }

    fun clamp() {
        val pages = pages()
        val cur = pages.firstOrNull { it.top == 0f } ?: return
        val z = panel.zoom
        val sw = cur.geo.width * z
        panel.panX = if (sw <= panel.viewW) (panel.viewW - sw) / 2f else panel.panX.coerceIn(panel.viewW - sw, 0f)

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
    }

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

    // ---------- margin resizing ----------

    /** Screen x of a margin's inner edge (where the grip is drawn), or null if that margin is hidden. */
    fun marginEdgeX(left: Boolean): Float? {
        val g = geo ?: return null
        if (left && !g.left || !left && !g.right) return null
        return panel.panX + (if (left) g.leftW else g.colRight) * panel.zoom
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
        val s = toStrip(pos)
        val page = pageAt(s.y) ?: return
        val g = page.geo
        if (g.regionAt(s.x) != Region.TEXT) return
        val localY = s.y - page.top - Page.TEXT_TOP
        if (!g.layout.isOnText(localY)) return
        val off = g.layout.offsetAt(s.x - g.textLeft, localY)
        vm.openVerse(g.layout.book, g.layout.chapter, g.layout.verseAtOffset(off))
    }

    // ---------- pen ----------

    fun sideButtonTool(): Tool? = vm.sideButton.tool

    /** [override] replaces the selected tool for this stroke (eraser end or side button). */
    fun penStart(pos: Offset, pressure: Float, override: Tool?) {
        touched()
        val s = toStrip(pos)
        val page = pageAt(s.y) ?: return
        val g = page.geo
        val p = Offset(s.x, s.y - page.top)

        // A pen touching inside the lasso selection moves it, whatever tool is picked.
        val sel = vm.selection
        if (sel != null && override != Tool.ERASER) {
            val selPage = pages().firstOrNull { sel.isOn(it.layout) }
            if (selPage != null && selectionBounds(selPage)?.inflate(12f / panel.zoom)?.contains(Offset(s.x, s.y - selPage.top)) == true) {
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
                    vm.message = "Layer “${layer.name}” is locked. Unlock it in Layers to draw."
                    mode = null
                    return
                }
                if (!layer.visible) {
                    vm.setLayerVisible(layer.id, true)
                    vm.message = "Showing layer “${layer.name}”."
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
            }
            Tool.ERASER -> {
                erased.clear()
                eraseAt(s)
            }
            Tool.LASSO -> {
                val l = LiveLasso(page)
                l.add(p)
                lasso = l
            }
            Tool.SELECT -> startSelect(page, p, pos)
        }
    }

    fun penMove(pos: Offset, pressure: Float) {
        val s = toStrip(pos)
        when (mode) {
            Tool.PEN, Tool.HIGHLIGHTER -> live?.let {
                it.add(s.x - it.ox, s.y - it.page.top - it.oy, pressure)
            }
            Tool.ERASER -> eraseAt(s)
            Tool.LASSO -> {
                val d = lassoDrag
                val l = lasso
                if (d != null) {
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

    fun penEnd() {
        lastPenUp = SystemClock.uptimeMillis()
        when (mode) {
            Tool.PEN, Tool.HIGHLIGHTER -> finishStroke()
            Tool.ERASER -> {
                if (erased.isNotEmpty()) vm.record(Edit(emptyList(), erased.toList()))
                erased.clear()
            }
            Tool.LASSO -> {
                if (lassoDrag != null) {
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
                penPanLast = null
            }
            null -> {}
        }
        mode = null
    }

    private fun finishStroke() {
        val ink = live ?: return
        live = null
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

        for (st in vm.textStrokesFor(layout.version, layout.book, layout.chapter).toList()) {
            if (st.layerId in usable && hits(st, g.textLeft, Page.TEXT_TOP)) { vm.removeItem(st); erased += st }
        }
        for (st in vm.marginStrokesFor(layout.book, layout.chapter).toList()) {
            if (st.layerId in usable && g.visible(st.region) && hits(st, g.originX(st.region), g.originY(st.region, st.verse))) {
                vm.removeItem(st); erased += st
            }
        }
        val local = Offset(p.x - g.textLeft, p.y - Page.TEXT_TOP)
        if (layout.isOnText(local.y) && local.x >= -r && local.x <= Page.TEXT_W + r) {
            val off = layout.offsetAt(local.x, local.y)
            for (h in vm.highlightsFor(layout.version, layout.book, layout.chapter).toList()) {
                if (h.layerId in usable && off >= h.start && off <= h.end) { vm.removeItem(h); erased += h }
            }
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
            }
        }
        return r
    }

    // ---------- select (images) ----------

    fun imageRect(g: PageGeometry, img: MarginImage): Rect {
        val ox = g.originX(img.region); val oy = g.originY(img.region, img.verse)
        return Rect(ox + img.x, oy + img.y, ox + img.x + img.w, oy + img.y + img.h)
    }

    private fun startSelect(page: PlacedPage, p: Offset, screen: Offset) {
        val g = page.geo
        val layout = g.layout
        val usable = vm.usableLayerIds()
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

    companion object {
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
