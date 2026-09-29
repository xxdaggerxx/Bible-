package com.biblestudy.app.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlin.math.hypot
import kotlin.math.max

/** The stroke currently being drawn. Points are in the local coordinates of its region. */
class LiveInk(
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

private class ImageDrag(val original: MarginImage, val resize: Boolean, val start: Offset) {
    var current: MarginImage = original
}

/**
 * Turns pen and finger input on one panel into drawing, erasing, selecting, panning and zooming.
 * Coordinates: "screen" = pixels inside the panel; "page" = Study Layout units.
 */
class ReaderController(private val vm: StudyViewModel, val panel: PanelState) {
    var panelIndex = 0
    var geo: PageGeometry? = null
    var lastPageW = -1f
    var lastViewW = -1f
    var lastLayout: ChapterLayout? = null

    var live by mutableStateOf<LiveInk?>(null)
        private set
    var selectedImageId by mutableStateOf<Long?>(null)

    private var mode: Tool? = null
    private var penPanLast: Offset? = null
    private var lastPenUp = 0L
    private val erased = ArrayList<Annotation>()
    private var drag: ImageDrag? = null

    private val hlPaths = HashMap<Long, Path>()
    private var hlPathsLayout: ChapterLayout? = null

    fun highlightPath(h: Highlight, layout: ChapterLayout): Path {
        if (hlPathsLayout !== layout) { hlPaths.clear(); hlPathsLayout = layout }
        return hlPaths.getOrPut(h.id) {
            val len = layout.textLength
            layout.text.getPathForRange(h.start.coerceIn(0, len), h.end.coerceIn(0, len))
        }
    }

    fun touched() { vm.activePanel = panelIndex }

    fun recentlyPenned() = SystemClock.uptimeMillis() - lastPenUp < 600

    fun toPage(p: Offset) = Offset((p.x - panel.panX) / panel.zoom, (p.y - panel.panY) / panel.zoom)

    // ---------- view transform ----------

    private fun fitZoom(g: PageGeometry) = if (panel.viewW > 0f) panel.viewW / g.width else 1f

    fun fitWidth() {
        val g = geo ?: return
        if (panel.viewW <= 0f) return
        panel.zoom = fitZoom(g)
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
        val g = geo ?: return
        val sw = g.width * panel.zoom
        val sh = g.height * panel.zoom
        panel.panX = if (sw <= panel.viewW) (panel.viewW - sw) / 2f else panel.panX.coerceIn(panel.viewW - sw, 0f)
        panel.panY = if (sh <= panel.viewH) 0f else panel.panY.coerceIn(panel.viewH - sh, 0f)
        panel.topVerse = g.layout.verseAtY(-panel.panY / panel.zoom + 80f)
    }

    // ---------- finger tap ----------

    fun onTap(pos: Offset) {
        val g = geo ?: return
        val p = toPage(pos)
        if (g.regionAt(p.x) != Region.TEXT) return
        val localY = p.y - Page.TEXT_TOP
        if (localY < 0f || localY > g.layout.text.size.height) return
        val off = g.layout.text.getOffsetForPosition(Offset(p.x - g.textLeft, localY))
        vm.openVerse(g.layout.book, g.layout.chapter, g.layout.verseAtOffset(off))
    }

    // ---------- pen ----------

    fun penStart(pos: Offset, pressure: Float, eraserTip: Boolean) {
        touched()
        val g = geo ?: return
        val p = toPage(pos)
        val tool = if (eraserTip) Tool.ERASER else vm.tool
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
                    region = region, verse = verse,
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
                eraseAt(p)
            }
            Tool.SELECT -> startSelect(p, pos)
        }
    }

    fun penMove(pos: Offset, pressure: Float) {
        val p = toPage(pos)
        when (mode) {
            Tool.PEN, Tool.HIGHLIGHTER -> live?.let { it.add(p.x - it.ox, p.y - it.oy, pressure) }
            Tool.ERASER -> eraseAt(p)
            Tool.SELECT -> moveSelect(p, pos)
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
        val g = geo ?: return
        var pts = ink.toArray()
        if (pts.size == 3) pts = floatArrayOf(pts[0], pts[1], pts[2], pts[0] + 0.5f, pts[1], pts[2])
        val layout = g.layout

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
            highlighter = ink.highlighter, color = ink.color, width = ink.width, points = pts,
        )
        vm.addItem(s)
        vm.record(Edit(listOf(s), emptyList()))
    }

    /** Snaps a highlighter swipe to whole words on the text lines it crosses. */
    private fun snapHighlight(layout: ChapterLayout, pts: FloatArray, ink: LiveInk): Highlight? {
        val t = layout.text
        val h = t.size.height.toFloat()
        val a = Offset(pts[0], pts[1])
        val b = Offset(pts[pts.size - 3], pts[pts.size - 2])
        if (a.y < 0f || a.y > h || b.y < 0f || b.y > h) return null
        var s = t.getOffsetForPosition(a)
        var e = t.getOffsetForPosition(b)
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

    private fun eraseAt(p: Offset) {
        val g = geo ?: return
        val layout = g.layout
        val usable = vm.layers.filter { it.visible && !it.locked }.mapTo(HashSet()) { it.id }
        val r = max(8f, 20f / panel.zoom)

        fun hits(s: InkStroke, ox: Float, oy: Float): Boolean {
            val pts = s.points
            val reach = r + s.width / 2f
            val reach2 = reach * reach
            val lx = p.x - ox; val ly = p.y - oy
            val n = pts.size / 3
            if (n == 1) return (pts[0] - lx) * (pts[0] - lx) + (pts[1] - ly) * (pts[1] - ly) <= reach2
            for (i in 1 until n) {
                if (distSqToSegment(lx, ly, pts[3 * i - 3], pts[3 * i - 2], pts[3 * i], pts[3 * i + 1]) <= reach2) return true
            }
            return false
        }

        for (s in vm.textStrokesFor(layout.version, layout.book, layout.chapter).toList()) {
            if (s.layerId in usable && hits(s, g.textLeft, Page.TEXT_TOP)) { vm.removeItem(s); erased += s }
        }
        for (s in vm.marginStrokesFor(layout.book, layout.chapter).toList()) {
            if (s.layerId in usable && g.visible(s.region) && hits(s, g.originX(s.region), g.originY(s.region, s.verse))) {
                vm.removeItem(s); erased += s
            }
        }
        val local = Offset(p.x - g.textLeft, p.y - Page.TEXT_TOP)
        if (local.y >= 0f && local.y <= layout.text.size.height && local.x >= -r && local.x <= Page.TEXT_W + r) {
            val off = layout.text.getOffsetForPosition(local)
            val line = layout.text.getLineForOffset(off)
            if (local.y >= layout.text.getLineTop(line) && local.y <= layout.text.getLineBottom(line)) {
                for (h in vm.highlightsFor(layout.version, layout.book, layout.chapter).toList()) {
                    if (h.layerId in usable && off >= h.start && off <= h.end) { vm.removeItem(h); erased += h }
                }
            }
        }
    }

    // ---------- select (images) ----------

    fun imageRect(g: PageGeometry, img: MarginImage): Rect {
        val ox = g.originX(img.region); val oy = g.originY(img.region, img.verse)
        return Rect(ox + img.x, oy + img.y, ox + img.x + img.w, oy + img.y + img.h)
    }

    private fun startSelect(p: Offset, screen: Offset) {
        val g = geo ?: return
        val layout = g.layout
        val usable = vm.layers.filter { it.visible && !it.locked }.mapTo(HashSet()) { it.id }
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
        drag = ImageDrag(hit, handle, p)
    }

    private fun moveSelect(p: Offset, screen: Offset) {
        val d = drag
        if (d == null) {
            val last = penPanLast ?: return
            transform(screen, screen - last, 1f)
            penPanLast = screen
            return
        }
        val dx = p.x - d.start.x; val dy = p.y - d.start.y
        val o = d.original
        d.current = if (d.resize) {
            val w = max(60f, o.w + dx)
            o.copy(w = w, h = w * (o.h / o.w))
        } else {
            o.copy(x = o.x + dx, y = o.y + dy)
        }
        vm.replaceImageLive(d.current)
    }

    fun selectedImage(): MarginImage? {
        val id = selectedImageId ?: return null
        val g = geo ?: return null
        return vm.imagesFor(g.layout.book, g.layout.chapter).firstOrNull { it.id == id }
    }

    fun visibleRect(): Rect {
        val l = -panel.panX / panel.zoom
        val t = -panel.panY / panel.zoom
        return Rect(l, t, l + panel.viewW / panel.zoom, t + panel.viewH / panel.zoom)
    }

    companion object {
        fun overlaps(a: Rect, b: Rect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    }
}
