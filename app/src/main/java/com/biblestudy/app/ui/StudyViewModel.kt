package com.biblestudy.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biblestudy.app.data.BibleRepository
import com.biblestudy.app.data.Passage
import com.biblestudy.app.data.UserDb
import com.biblestudy.app.model.Annotation
import com.biblestudy.app.model.Bookmark
import com.biblestudy.app.model.CrossHighlight
import com.biblestudy.app.model.HighlightEntry
import com.biblestudy.app.model.Edit
import com.biblestudy.app.model.Heading
import com.biblestudy.app.model.Highlight
import com.biblestudy.app.model.InkStroke
import com.biblestudy.app.model.Layer
import com.biblestudy.app.model.MarginImage
import com.biblestudy.app.model.Region
import com.biblestudy.app.model.SideButton
import com.biblestudy.app.model.TextFont
import com.biblestudy.app.model.TypedNote
import com.biblestudy.app.model.Tool
import com.biblestudy.app.model.VerseId
import com.biblestudy.app.model.VerseTarget
import com.biblestudy.app.model.translated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Items picked with the lasso, all on one chapter of one version. */
data class Selection(val version: String, val book: Int, val chapter: Int, val ids: Set<Long>) {
    fun isOn(layout: ChapterLayout) = layout.version == version && layout.book == book && layout.chapter == chapter
}

/** One reading panel (the screen can show one or two side by side). */
class PanelState(book: Int, chapter: Int) {
    var book by mutableIntStateOf(book)
    var chapter by mutableIntStateOf(chapter)
    var version by mutableStateOf("KJV")
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var pendingVerse by mutableStateOf<Int?>(null)
    var viewW by mutableFloatStateOf(0f)
    var viewH by mutableFloatStateOf(0f)
    /** The verse at the top of the view (observable, so a cross-references pane can follow it). */
    var topVerse by mutableIntStateOf(1)
    /** Bumped on every explicit jump (picker, search, arrows) so the panel scrolls to the top. */
    var navGen by mutableIntStateOf(0)

    /** Places visited before and after jumps (READ-5). */
    val back = mutableStateListOf<Place>()
    val forward = mutableStateListOf<Place>()

    fun here() = Place(book, chapter, topVerse)

    /** Zoom relative to fit-width, remembered per orientation (ANCH-7). 1 = fit width. */
    val zoomRel = mutableMapOf("land" to 1f, "port" to 1f)
    /** The zoom a double-tap returns to from fit-width. */
    var lastZoomRel = 2f
}

/**
 * Where a panel is scrolled to, for linked split view (SPLIT-3): the verse at the top of the view
 * and how far into it ([frac] of the verse's height, negative above the first verse). Versions lay
 * out differently, so linked panels match verses rather than pixels.
 */
data class ScrollPos(val source: Int, val book: Int, val chapter: Int, val verse: Int, val frac: Float)

/** A passage pop-over open over panel [panel], pointing at [anchor] (pixels in that panel). */
data class PassagePop(val panel: Int, val passage: Passage, val anchor: Offset)

/**
 * Window width classes (ADP-1), as in Material guidance: compact below 600dp (phones, small
 * tablets in portrait), medium to 840dp, expanded above. The layout adapts to them:
 *  - compact: margins become drawers in portrait (MRG-14), panel headers go compact;
 *  - medium: two Bible panels, one above the other in portrait;
 *  - expanded: two Bible panels side by side, three from 1200dp in landscape (ADP-3).
 */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED;
    companion object {
        fun of(widthDp: Float) = when {
            widthDp < 600f -> COMPACT
            widthDp < 840f -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** What the study pane beside the Bible panels shows (SPLIT-2). */
enum class PaneKind(val label: String) { SEARCH("Search"), CROSSREFS("Cross-references"), NOTES("My notes") }

/** A spot to return to with Back / Forward. */
data class Place(val book: Int, val chapter: Int, val verse: Int)

class StudyViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("study", Context.MODE_PRIVATE)
    /** The KJV: book names, chapter counts and cross-references come from here for every version. */
    val bible = BibleRepository(app, BibleRepository.KJV)
    private val texts = HashMap<String, BibleRepository>().apply { put(bible.code, bible) }

    /** The text of one version, opened (and copied out of the APK) the first time it's needed. */
    @Synchronized
    fun text(code: String): BibleRepository = texts.getOrPut(code) {
        val v = BibleRepository.ALL.firstOrNull { it.code == code } ?: BibleRepository.KJV
        if (v.code == bible.code) bible else BibleRepository(getApplication(), v)
    }
    val user = UserDb(app)
    private val imagesDir = File(app.filesDir, "images").apply { mkdirs() }

    /** All database writes go through one thread, so they happen in order. */
    private val dbDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // ---------- tool & display settings ----------
    var tool by mutableStateOf(runCatching { Tool.valueOf(prefs.getString("tool", "PEN")!!) }.getOrDefault(Tool.PEN))
    var penColor by mutableIntStateOf(prefs.getInt("penColor", PEN_COLORS[0]))
    var highlightColor by mutableIntStateOf(prefs.getInt("hlColor", HIGHLIGHT_COLORS[0]))
    var penSize by mutableIntStateOf(prefs.getInt("penSize", 1))
    var highlightSize by mutableIntStateOf(prefs.getInt("hlSize", 1))
    var snapHighlights by mutableStateOf(prefs.getBoolean("snap", true))
    var fingerDraw by mutableStateOf(prefs.getBoolean("fingerDraw", false))
    var showHeadings by mutableStateOf(prefs.getBoolean("headings", true))
    /** Split panels scroll together (SPLIT-3). */
    var linkPanels by mutableStateOf(prefs.getBoolean("linkPanels", false))
    val linked: Boolean get() = linkPanels && panels.size > 1
    /** The latest position announced by a linked panel; the other panel follows it. */
    var linkPos by mutableStateOf<ScrollPos?>(null)
        private set

    fun announceScroll(pos: ScrollPos) {
        if (linked) linkPos = pos
    }
    /** Eraser removes only what it touches (INK-7), instead of whole strokes. */
    var partialEraser by mutableStateOf(prefs.getBoolean("partialEraser", false))
    /** Draw pen strokes straight to the screen for the lowest latency (INK-4). */
    var fastInk by mutableStateOf(prefs.getBoolean("fastInk", true))
    /** The version a new Bible panel opens in; null = the same as the panel it comes from. */
    var newPanelVersion by mutableStateOf(prefs.getString("newPanelVersion", null))
    /**
     * The Bible text's typeface (READ-3). Changing it reflows the lines, so ink on the words is
     * reloaded and moved to the same characters in the new layout.
     */
    var textFont by mutableStateOf(runCatching { TextFont.valueOf(prefs.getString("textFont", "BOOK")!!) }.getOrDefault(TextFont.BOOK))
        private set

    /**
     * Puts every setting back to its default (SET-4). Notes, ink, highlights, bookmarks, layers and
     * the open passages are not touched.
     */
    fun resetSettings() {
        theme = PageTheme.LIGHT
        changeTextFont(TextFont.BOOK)
        lineSpacing = LineSpacing.NORMAL
        showHeadings = true
        newPanelVersion = null
        fingerDraw = false
        sideButton = SideButton.entries.first()
        partialEraser = false
        snapHighlights = true
        fastInk = true
        penSize = 1; highlightSize = 1
        highlightsAllVersions = true
        marginLeft = false; marginRight = true
        linkPanels = false
        compareVersions = false
        savePrefs()
        message = "Settings reset to their defaults."
    }

    fun changeTextFont(f: TextFont) {
        if (f == textFont) return
        textFont = f
        selection = null
        undoStack.clear(); redoStack.clear(); editVersion++
        loaded.removeAll { it.startsWith("t") }
        textStrokes.values.forEach { it.clear() }
    }
    /** Show highlights from other translations over whole verses (HL-10). */
    var highlightsAllVersions by mutableStateOf(prefs.getBoolean("hlAllVersions", true))
    /** The verse window shows the verse in every version, stacked (SPLIT-4). */
    var compareVersions by mutableStateOf(prefs.getBoolean("compareVersions", false))
    var lineSpacing by mutableStateOf(
        runCatching { LineSpacing.valueOf(prefs.getString("lineSpacing", "NORMAL")!!) }.getOrDefault(LineSpacing.NORMAL)
    )
    var sideButton by mutableStateOf(
        runCatching { SideButton.valueOf(prefs.getString("sideButton", "ERASER")!!) }.getOrDefault(SideButton.ERASER)
    )
    var marginLeft by mutableStateOf(prefs.getBoolean("marginLeft", false))
    var marginRight by mutableStateOf(prefs.getBoolean("marginRight", true))
    var theme by mutableStateOf(runCatching { PageTheme.valueOf(prefs.getString("theme", "LIGHT")!!) }.getOrDefault(PageTheme.LIGHT))
    var splitFraction by mutableFloatStateOf(prefs.getFloat("split", 0.5f))
    /** Relative widths (or heights) of the Bible panels, one per panel. */
    val panelWeights = mutableStateListOf<Float>()
    /** How many Bible panels fit: 3 on large screens in landscape, otherwise 2 (ADP-3). */
    var maxPanels by mutableIntStateOf(2)
    /** The window's width class (ADP-1). */
    var widthClass by mutableStateOf(WidthClass.EXPANDED)
    /** The study pane beside the Bible panels, if open (SPLIT-2), and its share of the screen. */
    var sidePane by mutableStateOf(prefs.getString("sidePane", null)?.let { n -> PaneKind.entries.firstOrNull { it.name == n } })
    var paneFraction by mutableFloatStateOf(prefs.getFloat("paneFraction", 0.32f))
    /** A search the search pane should run when it opens. */
    var paneSearch by mutableStateOf<String?>(null)
    /** The verse the cross-references pane shows; null follows the top of the active panel. */
    var paneVerse by mutableStateOf<VerseTarget?>(null)

    /** Set by the UI; margin widths and zoom are remembered separately for landscape and portrait. */
    var landscape by mutableStateOf(true)
    val orientationKey: String get() = if (landscape) "land" else "port"
    private val marginWidths = mutableStateMapOf<String, Float>()

    private fun marginKey(left: Boolean) = "mw_" + (if (left) "L" else "R") + if (landscape) "_land" else "_port"

    /** Current width of a margin in page units, or 0 if it is switched off. */
    fun marginWidth(left: Boolean): Float {
        if (!(if (left) marginLeft else marginRight)) return 0f
        return marginWidths[marginKey(left)] ?: Page.MARGIN_W
    }

    fun setMarginWidth(left: Boolean, width: Float) {
        marginWidths[marginKey(left)] = width.coerceIn(Page.MARGIN_MIN, Page.MARGIN_MAX)
    }

    // ---------- panels ----------
    val panels = mutableStateListOf<PanelState>()
    var activePanel by mutableIntStateOf(0)

    // ---------- layers ----------
    val layers = mutableStateListOf<Layer>()
    var activeLayerId by mutableLongStateOf(prefs.getLong("activeLayer", 1L))

    // ---------- other UI state ----------
    var message by mutableStateOf<String?>(null)
    var verseSheet by mutableStateOf<VerseTarget?>(null)
    /** The book whose introduction is open (STD-13), if any. */
    var introBook by mutableStateOf<Int?>(null)
    var lastSearch by mutableStateOf("")
    /** Bumped after a restore so panels reload their data. */
    var dataGeneration by mutableIntStateOf(0)
    val bookmarks = mutableStateListOf<Bookmark>()
    var selection by mutableStateOf<Selection?>(null)
        private set
    /** The passage pop-over opened from a Bible hyperlink (LINK-2), if any. */
    var passagePop by mutableStateOf<PassagePop?>(null)

    // ---------- undo ----------
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var editVersion by mutableIntStateOf(0)
    /** Changes whenever an annotation is added, removed or changed through undoable edits. */
    val editCount: Int get() = editVersion
    val canUndo: Boolean get() = editVersion >= 0 && undoStack.isNotEmpty()
    val canRedo: Boolean get() = editVersion >= 0 && redoStack.isNotEmpty()

    // ---------- caches ----------
    private val textStrokes = HashMap<String, SnapshotStateList<InkStroke>>()
    private val highlights = HashMap<String, SnapshotStateList<Highlight>>()
    private val marginStrokes = HashMap<String, SnapshotStateList<InkStroke>>()
    private val images = HashMap<String, SnapshotStateList<MarginImage>>()
    private val notes = HashMap<String, SnapshotStateMap<Int, TypedNote>>()
    private val loaded = HashSet<String>()
    private val renders = HashMap<Long, StrokeRender>()
    val bitmaps = mutableStateMapOf<String, ImageBitmap>()
    private val requestedBitmaps = HashSet<String>()
    private var lastId = 0L

    init {
        val count = prefs.getInt("panels", 1).coerceIn(1, 3)
        for (i in 0 until count) {
            val b = prefs.getInt("p${i}b", 43).coerceIn(1, 66)
            val c = prefs.getInt("p${i}c", if (b == 43) 3 else 1).coerceIn(1, bible.book(b).chapters)
            panels.add(PanelState(b, c).apply {
                version = validVersion(prefs.getString("p${i}v", null))
                for (o in listOf("land", "port")) zoomRel[o] = prefs.getFloat("p${i}z_$o", 1f)
                lastZoomRel = prefs.getFloat("p${i}zl", 2f)
            })
        }
        layers.addAll(user.layers())
        if (layers.isEmpty()) {
            val l = Layer(1L, "My Notes", LAYER_COLORS[0], visible = true, locked = false, sort = 0)
            layers.add(l); user.saveLayer(l)
        }
        if (layers.none { it.id == activeLayerId }) activeLayerId = layers.first().id
        bookmarks.addAll(user.bookmarks())
        for (k in listOf("mw_L_land", "mw_R_land", "mw_L_port", "mw_R_port")) {
            if (prefs.contains(k)) marginWidths[k] = prefs.getFloat(k, Page.MARGIN_W)
        }
    }

    fun newId(): Long {
        val t = System.currentTimeMillis() * 1000
        lastId = if (t > lastId) t else lastId + 1
        return lastId
    }

    fun savePrefs() {
        prefs.edit {
            putString("tool", tool.name)
            putInt("penColor", penColor); putInt("hlColor", highlightColor)
            putInt("penSize", penSize); putInt("hlSize", highlightSize)
            putBoolean("snap", snapHighlights); putBoolean("fingerDraw", fingerDraw)
            putString("sideButton", sideButton.name)
            putBoolean("headings", showHeadings); putString("lineSpacing", lineSpacing.name)
            putBoolean("partialEraser", partialEraser); putBoolean("fastInk", fastInk); putString("newPanelVersion", newPanelVersion); putString("textFont", textFont.name); putBoolean("hlAllVersions", highlightsAllVersions); putBoolean("compareVersions", compareVersions); putBoolean("linkPanels", linkPanels)
            putBoolean("marginLeft", marginLeft); putBoolean("marginRight", marginRight)
            putString("theme", theme.name); putLong("activeLayer", activeLayerId)
            putFloat("split", splitFraction)
            putString("sidePane", sidePane?.name); putFloat("paneFraction", paneFraction)
            putInt("panels", panels.size)
            marginWidths.forEach { (k, v) -> putFloat(k, v) }
            panels.forEachIndexed { i, p ->
                putInt("p${i}b", p.book); putInt("p${i}c", p.chapter); putString("p${i}v", p.version)
                p.zoomRel.forEach { (o, z) -> putFloat("p${i}z_$o", z) }
                putFloat("p${i}zl", p.lastZoomRel)
            }
        }
    }

    fun currentWidth(highlighter: Boolean) =
        if (highlighter) HIGHLIGHT_SIZES[highlightSize.coerceIn(0, 2)] else PEN_SIZES[penSize.coerceIn(0, 2)]

    // ---------- navigation ----------

    /**
     * Jumps a panel to a passage. Jumps from the picker, search, cross-references and bookmarks
     * are remembered for Back; the chapter arrows pass [remember] = false.
     */
    fun goTo(index: Int, book: Int, chapter: Int, verse: Int? = null, remember: Boolean = true) {
        val p = panels.getOrNull(index) ?: return
        if (remember) {
            val here = p.here()
            if (p.back.lastOrNull() != here) p.back.add(here)
            while (p.back.size > MAX_HISTORY) p.back.removeAt(0)
            p.forward.clear()
        }
        jump(p, book, chapter, verse)
    }

    private fun jump(p: PanelState, book: Int, chapter: Int, verse: Int?) {
        val b = book.coerceIn(1, 66)
        p.book = b
        p.chapter = chapter.coerceIn(1, bible.book(b).chapters)
        p.pendingVerse = verse
        p.navGen++
    }

    private fun validVersion(code: String?) =
        BibleRepository.ALL.firstOrNull { it.code == code }?.code ?: BibleRepository.KJV.code

    /** Switches a panel to another version, staying at the verse at the top of the view. */
    fun setVersion(index: Int, code: String) {
        val p = panels.getOrNull(index) ?: return
        if (p.version == code) return
        selection = null
        p.pendingVerse = p.topVerse
        p.version = validVersion(code)
    }

    /** Section headings for a chapter. Only the BSB has them; being public domain, they are shown in every version. */
    fun headings(book: Int, chapter: Int): List<Heading> = text(BibleRepository.BSB.code).headings(book, chapter)

    /** The version shown in the active panel (used by search and the verse popup). */
    val activeVersion: String get() = panels.getOrNull(activePanel)?.version ?: bible.code

    /** The chapter before (dir = -1) or after (dir = 1), or null at either end of the Bible. */
    fun neighbor(book: Int, chapter: Int, dir: Int): Pair<Int, Int>? = when {
        dir > 0 && chapter < bible.book(book).chapters -> book to chapter + 1
        dir > 0 && book < 66 -> book + 1 to 1
        dir < 0 && chapter > 1 -> book to chapter - 1
        dir < 0 && book > 1 -> (book - 1) to bible.book(book - 1).chapters
        else -> null
    }

    /** Continuous scrolling moved into the next/previous chapter; keep the scroll position. */
    fun shiftChapter(p: PanelState, dir: Int) {
        val (b, c) = neighbor(p.book, p.chapter, dir) ?: return
        p.book = b
        p.chapter = c
    }

    fun nextChapter(index: Int) {
        val p = panels.getOrNull(index) ?: return
        neighbor(p.book, p.chapter, 1)?.let { (b, c) -> goTo(index, b, c, remember = false) }
    }

    fun prevChapter(index: Int) {
        val p = panels.getOrNull(index) ?: return
        neighbor(p.book, p.chapter, -1)?.let { (b, c) -> goTo(index, b, c, remember = false) }
    }

    fun goBack(index: Int) {
        val p = panels.getOrNull(index) ?: return
        val to = p.back.removeLastOrNull() ?: return
        p.forward.add(p.here())
        jump(p, to.book, to.chapter, to.verse)
    }

    fun goForward(index: Int) {
        val p = panels.getOrNull(index) ?: return
        val to = p.forward.removeLastOrNull() ?: return
        p.back.add(p.here())
        jump(p, to.book, to.chapter, to.verse)
    }

    fun toggleSplit() {
        if (panels.size == 1) addPanel() else while (panels.size > 1) closePanel(panels.lastIndex)
    }

    /** Opens another Bible panel showing the active one's passage (up to [maxPanels]). */
    fun addPanel() {
        if (panels.size >= maxPanels) {
            message = "No room for another panel on this screen."
            return
        }
        val p = panels[activePanel.coerceIn(0, panels.lastIndex)]
        panels.add(PanelState(p.book, p.chapter).apply { version = validVersion(newPanelVersion ?: p.version) })
        panelWeights.clear()
        activePanel = panels.lastIndex
    }

    fun closePanel(index: Int) {
        if (panels.size > 1 && index in panels.indices) {
            panels.removeAt(index)
            panelWeights.clear()
        }
        activePanel = 0
    }

    /** The weights of the panels (equal, or the saved split for two, until a divider is dragged). */
    fun weights(): List<Float> =
        if (panelWeights.size == panels.size) panelWeights.toList()
        else if (panels.size == 2) listOf(splitFraction, 1f - splitFraction)
        else List(panels.size) { 1f }

    /** Moves the divider after panel [i] by [delta] (a fraction of the panels' total size). */
    fun dragDivider(i: Int, delta: Float) {
        val w = weights().toMutableList()
        if (i + 1 >= w.size) return
        val total = w.sum()
        val d = delta * total
        val min = 0.15f * total
        val a = (w[i] + d).coerceIn(min, w[i] + w[i + 1] - min)
        w[i + 1] = w[i] + w[i + 1] - a
        w[i] = a
        panelWeights.clear(); panelWeights.addAll(w)
        if (w.size == 2) splitFraction = w[0] / total
    }

    /** Opens (or switches) the study pane; the same kind again closes it. */
    fun togglePane(kind: PaneKind) {
        sidePane = if (sidePane == kind) null else kind
    }

    fun openVerse(book: Int, chapter: Int, verse: Int) {
        verseSheet = VerseTarget(book, chapter, verse)
        paneVerse = verseSheet
    }

    fun refLabel(start: Int, end: Int = start): String {
        val b = VerseId.book(start); val c = VerseId.chapter(start); val v = VerseId.verse(start)
        val base = "${bible.book(b).name} $c:$v"
        if (end == start) return base
        val eb = VerseId.book(end); val ec = VerseId.chapter(end); val ev = VerseId.verse(end)
        return when {
            eb == b && ec == c -> "$base\u2013$ev"
            eb == b -> "$base\u2013$ec:$ev"
            else -> "$base \u2013 ${bible.book(eb).name} $ec:$ev"
        }
    }

    // ---------- layers ----------

    fun activeLayer(): Layer? = layers.firstOrNull { it.id == activeLayerId } ?: layers.firstOrNull()

    private fun updateLayer(id: Long, change: (Layer) -> Layer) {
        val i = layers.indexOfFirst { it.id == id }
        if (i < 0) return
        val l = change(layers[i])
        layers[i] = l
        io { user.saveLayer(l) }
    }

    fun toggleLayerVisible(id: Long) = updateLayer(id) { it.copy(visible = !it.visible) }
    fun toggleLayerLocked(id: Long) = updateLayer(id) { it.copy(locked = !it.locked) }
    fun renameLayer(id: Long, name: String) { if (name.isNotBlank()) updateLayer(id) { it.copy(name = name.trim()) } }
    fun setLayerVisible(id: Long, visible: Boolean) = updateLayer(id) { it.copy(visible = visible) }

    fun setAllLayersVisible(visible: Boolean) {
        layers.map { it.id }.forEach { id -> setLayerVisible(id, visible) }
    }

    fun showOnlyLayer(id: Long) {
        layers.map { it.id }.forEach { setLayerVisible(it, it == id) }
    }

    fun addLayer(name: String) {
        val id = newId()
        val l = Layer(
            id, name.ifBlank { "Layer ${layers.size + 1}" }.trim(), LAYER_COLORS[layers.size % LAYER_COLORS.size],
            visible = true, locked = false, sort = (layers.maxOfOrNull { it.sort } ?: 0) + 1,
        )
        layers.add(l)
        activeLayerId = id
        io { user.saveLayer(l) }
    }

    /** Layers are drawn in list order: later layers draw on top of earlier ones. */
    fun moveLayer(id: Long, towardTop: Boolean) {
        val i = layers.indexOfFirst { it.id == id }
        val j = if (towardTop) i + 1 else i - 1
        if (i < 0 || j !in layers.indices) return
        val item = layers.removeAt(i)
        layers.add(j, item)
        for (k in layers.indices) {
            val l = layers[k]
            if (l.sort != k) layers[k] = l.copy(sort = k)
        }
        val snapshot = layers.toList()
        io { snapshot.forEach { user.saveLayer(it) } }
    }

    fun deleteLayer(id: Long) {
        if (layers.size <= 1) { message = "You need at least one layer."; return }
        layers.removeAll { it.id == id }
        if (activeLayerId == id) activeLayerId = layers.first().id
        textStrokes.values.forEach { l -> l.removeAll { it.layerId == id } }
        highlights.values.forEach { l -> l.removeAll { it.layerId == id } }
        marginStrokes.values.forEach { l -> l.removeAll { it.layerId == id } }
        images.values.forEach { l -> l.removeAll { it.layerId == id } }
        undoStack.clear(); redoStack.clear(); editVersion++
        selection = null
        io {
            val files = user.deleteLayer(id)
            files.forEach { File(imagesDir, it).delete() }
        }
    }

    // ---------- annotations ----------

    private fun tk(version: String, book: Int, chapter: Int) = "$version|$book|$chapter"
    private fun mk(book: Int, chapter: Int) = "$book|$chapter"

    fun textStrokesFor(version: String, book: Int, chapter: Int) =
        textStrokes.getOrPut(tk(version, book, chapter)) { mutableStateListOf() }

    fun highlightsFor(version: String, book: Int, chapter: Int) =
        highlights.getOrPut(tk(version, book, chapter)) { mutableStateListOf() }

    fun marginStrokesFor(book: Int, chapter: Int) =
        marginStrokes.getOrPut(mk(book, chapter)) { mutableStateListOf() }

    fun imagesFor(book: Int, chapter: Int) = images.getOrPut(mk(book, chapter)) { mutableStateListOf() }

    /** Typed notes in a chapter, by the verse each starts on. */
    fun notesFor(book: Int, chapter: Int): SnapshotStateMap<Int, TypedNote> {
        val key = mk(book, chapter)
        val map = notes.getOrPut(key) { mutableStateMapOf() }
        if (loaded.add("n$key")) {
            viewModelScope.launch {
                val data = withContext(dbDispatcher) { user.notes(book, chapter) }
                data.forEach { (v, t) -> if (!map.containsKey(v)) map[v] = t }
            }
        }
        return map
    }

    /** How many chapters' annotations are still being read from the database. */
    var pendingLoads by mutableIntStateOf(0)
        private set

    /**
     * Loads a chapter's annotations. [plainLayout] builds the chapter's layout in a font at normal
     * line spacing without headings; it is only called to convert ink saved before 0.4 to line
     * coordinates, or ink drawn in another font (READ-3).
     */
    fun ensureLoaded(version: String, book: Int, chapter: Int, plainLayout: (TextFont) -> ChapterLayout) {
        val t = tk(version, book, chapter)
        if (loaded.add("t$t")) {
            pendingLoads++
            viewModelScope.launch {
                try {
                    val (loadedStrokes, h) = withContext(dbDispatcher) { user.loadText(version, book, chapter) }
                    var s = loadedStrokes
                    val font = textFont
                    if (s.any { !it.lineAnchored || it.font != font.name }) {
                        val layouts = HashMap<TextFont, ChapterLayout>()
                        fun plain(f: TextFont) = layouts.getOrPut(f) { plainLayout(f) }
                        s = s.map { st ->
                            var c = st
                            if (!c.lineAnchored) {
                                // Ink from before 0.4: page y at normal spacing in the book font.
                                c = c.copyAs(points = plain(TextFont.BOOK).linePoints(c.points), lineAnchored = true, font = TextFont.BOOK.name)
                            }
                            if (c.font != font.name) {
                                val from = runCatching { TextFont.valueOf(c.font) }.getOrDefault(TextFont.BOOK)
                                c = c.copyAs(points = reflowPoints(c.points, plain(from), plain(font)), font = font.name)
                            }
                            if (c !== st) io { user.insert(c) }
                            c
                        }
                    }
                    merge(textStrokesFor(version, book, chapter), s)
                    merge(highlightsFor(version, book, chapter), h)
                } finally {
                    pendingLoads--
                }
            }
        }
        val m = mk(book, chapter)
        if (loaded.add("h$m")) {
            // Highlights made in the other translations, shown here over whole verses (HL-10).
            pendingLoads++
            viewModelScope.launch {
                try {
                    val all = withContext(dbDispatcher) {
                        user.chapterHighlights(book, chapter).filter { it.version != version }
                            .groupBy { it.version }
                            .onEach { (v, _) -> verseStarts(v, book, chapter) }
                    }
                    for ((v, list) in all) {
                        val target = highlightsFor(v, book, chapter)
                        val have = target.mapTo(HashSet()) { it.id }
                        target.addAll(list.filter { it.id !in have })
                    }
                } finally {
                    pendingLoads--
                }
            }
        }
        if (loaded.add("m$m")) {
            pendingLoads++
            viewModelScope.launch {
                try {
                    val (s, i) = withContext(dbDispatcher) { user.loadMargin(book, chapter) }
                    merge(marginStrokesFor(book, chapter), s)
                    merge(imagesFor(book, chapter), i)
                } finally {
                    pendingLoads--
                }
            }
        }
    }

    /** Verse start offsets and numbers of a chapter in [version] (cached). */
    private val verseStartCache = HashMap<String, Pair<IntArray, IntArray>>()

    private fun verseStarts(version: String, book: Int, chapter: Int): Pair<IntArray, IntArray> {
        val key = tk(version, book, chapter)
        synchronized(verseStartCache) { verseStartCache[key]?.let { return it } }
        val verses = text(version).chapter(book, chapter)
        val v = verseStartOffsets(verses) to IntArray(verses.size) { verses[it].verse }
        synchronized(verseStartCache) { verseStartCache[key] = v }
        return v
    }

    /** The verse holding character [offset] of a chapter's text in [version]. */
    private fun verseOf(version: String, book: Int, chapter: Int, offset: Int): Int {
        val (starts, numbers) = verseStarts(version, book, chapter)
        if (numbers.isEmpty()) return 1
        var i = 0
        while (i + 1 < starts.size && starts[i + 1] <= offset) i++
        return numbers[i]
    }

    /**
     * Highlights made in the other translations of a chapter, as the whole verses they cover
     * (HL-10). Empty when the setting is off.
     */
    fun crossHighlights(version: String, book: Int, chapter: Int): List<CrossHighlight> {
        if (!highlightsAllVersions) return emptyList()
        val out = ArrayList<CrossHighlight>()
        for (v in BibleRepository.ALL) {
            if (v.code == version) continue
            for (h in highlightsFor(v.code, book, chapter)) {
                val from = verseOf(v.code, book, chapter, h.start)
                val to = verseOf(v.code, book, chapter, maxOf(h.start, h.end - 1))
                out += CrossHighlight(h, from, to)
            }
        }
        return out
    }

    /** Every highlight with its words, in Bible order, for the Highlights list (HL-8). */
    suspend fun highlightEntries(): List<HighlightEntry> = withContext(dbDispatcher) {
        val chapters = HashMap<String, String>()
        user.allHighlights().map { h ->
            val text = chapters.getOrPut(tk(h.version, h.book, h.chapter)) {
                text(h.version).chapter(h.book, h.chapter).joinToString("") { "${it.verse}\u2009${it.text}\n" }
            }
            val words = text.substring(h.start.coerceIn(0, text.length), h.end.coerceIn(0, text.length))
                .replace(Regex("\\n\\d+\u2009"), " ").replace(Regex("^\\d+\u2009"), "").trim()
            HighlightEntry(h, verseOf(h.version, h.book, h.chapter, h.start), words)
        }
    }

    private fun <T : Annotation> merge(list: SnapshotStateList<T>, fromDb: List<T>) {
        val ids = fromDb.mapTo(HashSet()) { it.id }
        val extra = list.filter { it.id !in ids }
        list.clear(); list.addAll(fromDb); list.addAll(extra)
    }

    /**
     * Cached drawing paths for a margin stroke; rebuilt if its points change (e.g. moved with the
     * lasso). Strokes on the words are drawn through their [ChapterLayout.render] instead.
     */
    fun render(s: InkStroke): StrokeRender {
        renders[s.id]?.let { if (it.source === s.points) return it }
        return buildRender(s.points, s.width, s.highlighter).also { renders[s.id] = it }
    }

    fun usableLayerIds(): Set<Long> = layers.filter { it.visible && !it.locked }.mapTo(HashSet()) { it.id }

    /** Swaps an item for a changed copy with the same id, keeping its place in the drawing order. */
    private fun replaceItem(after: Annotation) {
        fun <T : Annotation> SnapshotStateList<T>.swap(item: T) {
            val i = indexOfFirst { it.id == item.id }
            if (i >= 0) this[i] = item else add(item)
        }
        when (after) {
            is InkStroke ->
                if (after.region == Region.TEXT) textStrokesFor(after.version ?: bible.code, after.book, after.chapter).swap(after)
                else marginStrokesFor(after.book, after.chapter).swap(after)
            is Highlight -> highlightsFor(after.version, after.book, after.chapter).swap(after)
            is MarginImage -> imagesFor(after.book, after.chapter).swap(after)
        }
        io { user.insert(after) }
    }

    fun addItem(a: Annotation) {
        when (a) {
            is InkStroke ->
                if (a.region == Region.TEXT) textStrokesFor(a.version ?: bible.code, a.book, a.chapter).add(a)
                else marginStrokesFor(a.book, a.chapter).add(a)
            is Highlight -> highlightsFor(a.version, a.book, a.chapter).add(a)
            is MarginImage -> imagesFor(a.book, a.chapter).add(a)
        }
        io { user.insert(a) }
    }

    fun removeItem(a: Annotation) {
        when (a) {
            is InkStroke ->
                if (a.region == Region.TEXT) textStrokesFor(a.version ?: bible.code, a.book, a.chapter).removeAll { it.id == a.id }
                else marginStrokesFor(a.book, a.chapter).removeAll { it.id == a.id }
            is Highlight -> highlightsFor(a.version, a.book, a.chapter).removeAll { it.id == a.id }
            is MarginImage -> imagesFor(a.book, a.chapter).removeAll { it.id == a.id }
        }
        io { user.delete(a) }
    }

    fun record(e: Edit) {
        if (e.added.isEmpty() && e.removed.isEmpty()) return
        undoStack.addLast(e)
        while (undoStack.size > 300) undoStack.removeFirst()
        redoStack.clear()
        editVersion++
    }

    fun undo() {
        val e = undoStack.removeLastOrNull() ?: return
        selection = null
        e.added.forEach { removeItem(it) }
        e.removed.forEach { addItem(it) }
        redoStack.addLast(e)
        editVersion++
    }

    fun redo() {
        val e = redoStack.removeLastOrNull() ?: return
        selection = null
        e.removed.forEach { removeItem(it) }
        e.added.forEach { addItem(it) }
        undoStack.addLast(e)
        editVersion++
    }

    /** Highlights a character range chosen by selecting text with a finger (NOTE-2). */
    fun addHighlight(layout: ChapterLayout, start: Int, end: Int) {
        val layer = activeLayer() ?: return
        if (layer.locked) { message = "Layer \u201c${layer.name}\u201d is locked."; return }
        if (!layer.visible) setLayerVisible(layer.id, true)
        val h = Highlight(newId(), layer.id, layout.version, layout.book, layout.chapter, start, end, highlightColor)
        addItem(h)
        record(Edit(listOf(h), emptyList()))
    }

    /** Changes a highlight's colour (undoable). Returns the changed highlight, or null if its layer is locked. */
    fun recolorHighlight(h: Highlight, color: Int): Highlight? {
        if (layers.firstOrNull { it.id == h.layerId }?.locked == true) { message = "That highlight's layer is locked."; return null }
        if (h.color == color) return h
        val after = h.copy(color = color)
        replaceItem(after)
        record(Edit(listOf(after), listOf(h)))
        return after
    }

    /** Removes a highlight (undoable). */
    fun removeHighlight(h: Highlight) {
        if (layers.firstOrNull { it.id == h.layerId }?.locked == true) { message = "That highlight's layer is locked."; return }
        removeItem(h)
        record(Edit(emptyList(), listOf(h)))
        message = "Highlight removed."
    }

    // ---------- lasso selection ----------

    fun select(s: Selection) { selection = s }

    fun clearSelection() { selection = null }

    fun selectedItems(): List<Annotation> = selection?.let { selectedItems(it) } ?: emptyList()

    fun selectedItems(sel: Selection): List<Annotation> = buildList {
        textStrokesFor(sel.version, sel.book, sel.chapter).filterTo(this) { it.id in sel.ids }
        marginStrokesFor(sel.book, sel.chapter).filterTo(this) { it.id in sel.ids }
        highlightsFor(sel.version, sel.book, sel.chapter).filterTo(this) { it.id in sel.ids }
        imagesFor(sel.book, sel.chapter).filterTo(this) { it.id in sel.ids }
    }

    private fun changeSelection(change: (Annotation) -> Annotation?) {
        val before = selectedItems()
        val pairs = before.mapNotNull { a -> change(a)?.let { a to it } }
        if (pairs.isEmpty()) return
        pairs.forEach { replaceItem(it.second) }
        record(Edit(pairs.map { it.second }, pairs.map { it.first }))
    }

    /** Moves selected ink and images by [off] page units. Highlights stay on their words. */
    fun moveSelection(off: Offset, layout: ChapterLayout) {
        val items = selectedItems()
        if (items.isNotEmpty() && items.all { it is Highlight }) {
            message = "Highlights stay on their words; only ink and images can be moved."
            return
        }
        changeSelection { a ->
            when (a) {
                is InkStroke -> a.withPoints(shifted(a, off.x, off.y, layout))
                is MarginImage -> a.copy(x = a.x + off.x, y = a.y + off.y)
                is Highlight -> null
            }
        }
    }

    /** A stroke's points moved by (dx, dy) page units; ink on the words goes through line coordinates. */
    private fun shifted(s: InkStroke, dx: Float, dy: Float, layout: ChapterLayout): FloatArray =
        if (s.region == Region.TEXT) layout.linePoints(layout.render(s).points.translated(dx, dy))
        else s.points.translated(dx, dy)

    fun recolorSelection(color: Int) = changeSelection { a ->
        when (a) {
            is InkStroke -> a.withColor(color)
            is Highlight -> a.copy(color = color)
            is MarginImage -> null
        }
    }

    fun moveSelectionToLayer(layerId: Long) {
        val layer = layers.firstOrNull { it.id == layerId } ?: return
        if (!layer.visible) setLayerVisible(layerId, true)
        changeSelection { a ->
            if (a.layerId == layerId) null else when (a) {
                is InkStroke -> a.withLayer(layerId)
                is Highlight -> a.copy(layerId = layerId)
                is MarginImage -> a.copy(layerId = layerId)
            }
        }
        message = "Moved to \u201c${layer.name}\u201d."
    }

    fun deleteSelection() {
        val items = selectedItems()
        items.forEach { removeItem(it) }
        record(Edit(emptyList(), items))
        selection = null
    }

    /** Duplicates the selected ink and images a little below and to the right, and selects the copies. */
    fun copySelection(layout: ChapterLayout) {
        val sel = selection ?: return
        val copies = selectedItems().mapNotNull { a ->
            when (a) {
                is InkStroke -> a.copyAs(newId(), shifted(a, COPY_SHIFT, COPY_SHIFT, layout))
                is MarginImage -> a.copy(id = newId(), x = a.x + COPY_SHIFT, y = a.y + COPY_SHIFT)
                is Highlight -> null
            }
        }
        if (copies.isEmpty()) return
        copies.forEach { addItem(it) }
        record(Edit(copies, emptyList()))
        selection = sel.copy(ids = copies.mapTo(HashSet()) { it.id })
    }

    // ---------- images ----------

    /** Live update while dragging; saved by [commitImageChange]. */
    fun replaceImageLive(img: MarginImage) {
        val list = imagesFor(img.book, img.chapter)
        val i = list.indexOfFirst { it.id == img.id }
        if (i >= 0) list[i] = img
    }

    fun commitImageChange(before: MarginImage, after: MarginImage) {
        if (before == after) return
        io { user.insert(after) }
        record(Edit(listOf(after), listOf(before)))
    }

    fun deleteImage(img: MarginImage) {
        removeItem(img)
        record(Edit(emptyList(), listOf(img)))
    }

    fun insertImage(uri: Uri) {
        val index = activePanel.coerceIn(0, panels.lastIndex)
        val p = panels[index]
        val layer = activeLayer() ?: return
        if (layer.locked) { message = "Layer \u201c${layer.name}\u201d is locked."; return }
        if (!layer.visible) setLayerVisible(layer.id, true)
        val region = when {
            marginRight -> Region.RIGHT
            marginLeft -> Region.LEFT
            else -> { marginRight = true; Region.RIGHT }
        }
        val marginW = marginWidth(region == Region.LEFT)
        val book = p.book; val chapter = p.chapter; val verse = p.topVerse
        viewModelScope.launch {
            val id = newId()
            val saved = withContext(Dispatchers.IO) { importImage(uri, id) }
            if (saved == null) { message = "Couldn't open that image."; return@launch }
            val (file, aspect) = saved
            val w = marginW - 48f
            val img = MarginImage(id, layer.id, book, chapter, region, verse, 24f, 8f, w, w * aspect, file)
            addItem(img)
            record(Edit(listOf(img), emptyList()))
            tool = Tool.SELECT
            message = "Image added beside verse $verse. Use Select to move or resize it."
        }
    }

    /** Puts a picture copied to the clipboard (e.g. a screenshot) into the margin (MRG-7). */
    fun pasteImage() {
        val app = getApplication<Application>()
        val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
        val uri = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        val isImage = uri != null && (clip.description.hasMimeType("image/*") || app.contentResolver.getType(uri)?.startsWith("image/") == true)
        if (uri == null || !isImage) {
            message = "There's no picture on the clipboard. Copy an image first, then paste."
            return
        }
        insertImage(uri)
    }

    /** A file for the camera to save a photo into, shared with it through the app's FileProvider. */
    fun newCameraUri(): Uri {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // earlier photos have already been copied
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        return androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
    }

    private fun importImage(uri: Uri, id: Long): Pair<String, Float>? = try {
        val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val scale = 1600f / max(w, h).toFloat()
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val png = bmp.hasAlpha()
        val name = "img_$id." + if (png) "png" else "jpg"
        File(imagesDir, name).outputStream().use { out ->
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, out)
        }
        name to (bmp.height.toFloat() / bmp.width.toFloat())
    } catch (e: Exception) {
        null
    }

    /** Returns the decoded image if ready; otherwise starts loading it. */
    fun bitmap(file: String): ImageBitmap? {
        bitmaps[file]?.let { return it }
        if (requestedBitmaps.add(file)) {
            viewModelScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val f = File(imagesDir, file)
                    if (!f.exists()) null else {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(f.path, opts)
                        var sample = 1
                        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= 1200) sample *= 2
                        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }
                if (bmp != null) bitmaps[file] = bmp.asImageBitmap()
            }
        }
        return null
    }

    // ---------- Bible hyperlinks ----------

    /** "Mark 1:9\u201311", "Psalms 23" or "Psalms 1\u201341" for a passage. */
    fun passageLabel(p: Passage): String {
        val name = bible.book(p.book).name
        return when {
            p.verse == 1 && p.endVerse >= 999 && p.chapter == p.endChapter -> "$name ${p.chapter}"
            p.verse == 1 && p.endVerse >= 999 -> "$name ${p.chapter}\u2013${p.endChapter}"
            else -> refLabel(p.startId, p.endId)
        }
    }

    /** The passage's verses in [version], up to [PASSAGE_LIMIT] of them. */
    fun passageVerses(p: Passage, version: String): List<Pair<Int, String>> =
        text(version).versesBetween(p.startId, p.endId, PASSAGE_LIMIT)

    /**
     * Opens a linked passage: in the panel it came from, or [beside] it in the other panel (opening
     * split view if needed) to read parallel accounts side by side (LINK-3).
     */
    fun openPassage(p: Passage, from: Int, beside: Boolean) {
        passagePop = null
        var target = from.coerceIn(0, panels.lastIndex)
        if (beside) {
            if (panels.size == 1) addPanel()
            if (linkPanels) {
                linkPanels = false
                message = "Panels unlinked to show the passage beside."
            }
            target = panels.indices.first { it != target }
        }
        activePanel = target
        goTo(target, p.book, p.chapter, p.verse)
    }

    // ---------- book picker markers ----------

    /** Where the user has notes, for the book picker (read from the database, after pending writes). */
    suspend fun loadMarkers(version: String): MarkerIndex =
        withContext(dbDispatcher) { MarkerIndex(user.markerRows(version), user.notedVerses()) }

    /** Visible layers, in drawing order: their items are the ones marked in the book picker. */
    fun visibleLayerIds(): List<Long> = layers.filter { it.visible }.map { it.id }

    // ---------- notes & bookmarks ----------

    /** Saves a typed note on verses [t]..[endVerse] of one chapter (NOTE-1); blank text deletes it. */
    fun setNote(t: VerseTarget, text: String, endVerse: Int = t.verse) {
        val map = notesFor(t.book, t.chapter)
        if (text.isBlank()) map.remove(t.verse) else map[t.verse] = TypedNote(t.verse, maxOf(t.verse, endVerse), text)
        io { user.setNote(t.book, t.chapter, t.verse, text, endVerse) }
    }

    // ---------- bookmark folders (NOTE-3) ----------

    /** Folder names, including empty folders the user has made. */
    val bookmarkFolders = mutableStateListOf<String>().apply {
        addAll(prefs.getStringSet("bmFolders", emptySet())!!.sorted())
    }

    private fun saveFolders() {
        prefs.edit { putStringSet("bmFolders", bookmarkFolders.toSet()) }
    }

    /** Every folder: the ones made here plus any that bookmarks are in (e.g. after a restore). */
    fun allBookmarkFolders(): List<String> =
        (bookmarkFolders + bookmarks.map { it.folder }.filter { it.isNotEmpty() }).distinct().sortedBy { it.lowercase() }

    fun addBookmarkFolder(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        if (n !in bookmarkFolders) {
            bookmarkFolders.add(n); bookmarkFolders.sort(); saveFolders()
        }
        return n
    }

    fun moveBookmark(b: Bookmark, folder: String) {
        val i = bookmarks.indexOfFirst { it.id == b.id }
        if (i < 0) return
        val moved = bookmarks[i].copy(folder = folder)
        bookmarks[i] = moved
        if (folder.isNotEmpty()) addBookmarkFolder(folder)
        io { user.addBookmark(moved) }
    }

    fun renameBookmarkFolder(old: String, new: String) {
        val n = new.trim()
        if (n.isEmpty() || n == old) return
        bookmarks.filter { it.folder == old }.forEach { moveBookmark(it, n) }
        bookmarkFolders.remove(old); addBookmarkFolder(n); saveFolders()
    }

    /** Deletes a folder; its bookmarks are kept, outside any folder. */
    fun deleteBookmarkFolder(name: String) {
        bookmarks.filter { it.folder == name }.forEach { moveBookmark(it, "") }
        bookmarkFolders.remove(name); saveFolders()
    }

    fun isBookmarked(t: VerseTarget) = bookmarks.any { it.book == t.book && it.chapter == t.chapter && it.verse == t.verse }

    fun toggleBookmark(t: VerseTarget) {
        val existing = bookmarks.firstOrNull { it.book == t.book && it.chapter == t.chapter && it.verse == t.verse }
        if (existing != null) {
            bookmarks.remove(existing)
            io { user.deleteBookmark(existing.id) }
        } else {
            val b = Bookmark(newId(), t.book, t.chapter, t.verse, System.currentTimeMillis())
            bookmarks.add(0, b)
            io { user.addBookmark(b) }
        }
    }

    fun deleteBookmark(b: Bookmark) {
        bookmarks.remove(b)
        io { user.deleteBookmark(b.id) }
    }

    // ---------- backup & restore ----------

    fun backup(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(dbDispatcher) {
                runCatching {
                    user.checkpoint()
                    val app = getApplication<Application>()
                    val dbFile = app.getDatabasePath(UserDb.NAME)
                    app.contentResolver.openOutputStream(uri)?.use { os ->
                        ZipOutputStream(os).use { zip ->
                            zip.putNextEntry(ZipEntry("userdata.db"))
                            dbFile.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            imagesDir.listFiles()?.forEach { f ->
                                zip.putNextEntry(ZipEntry("images/${f.name}"))
                                f.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    } ?: error("Couldn't write file")
                }.isSuccess
            }
            message = if (ok) "Backup saved." else "Backup failed."
        }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(dbDispatcher) {
                runCatching {
                    val app = getApplication<Application>()
                    val tmp = File(app.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        ZipInputStream(input).use { zip ->
                            while (true) {
                                val e = zip.nextEntry ?: break
                                val name = e.name
                                val safe = name == "userdata.db" ||
                                    (name.startsWith("images/") && !name.contains("..") && name.count { it == '/' } == 1)
                                if (!e.isDirectory && safe) {
                                    val out = File(tmp, name)
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { zip.copyTo(it) }
                                }
                            }
                        }
                    } ?: error("Couldn't read file")
                    val newDb = File(tmp, "userdata.db")
                    require(newDb.exists()) { "Not a Bible Study backup" }
                    user.close()
                    val dbFile = app.getDatabasePath(UserDb.NAME)
                    File(dbFile.path + "-wal").delete()
                    File(dbFile.path + "-shm").delete()
                    newDb.copyTo(dbFile, overwrite = true)
                    imagesDir.listFiles()?.forEach { it.delete() }
                    File(tmp, "images").listFiles()?.forEach { it.copyTo(File(imagesDir, it.name), overwrite = true) }
                    tmp.deleteRecursively()
                    user.layers() to user.bookmarks()
                }
            }
            val result = ok.getOrNull()
            if (result == null) {
                message = "Restore failed: ${ok.exceptionOrNull()?.message ?: "unknown error"}"
                return@launch
            }
            textStrokes.values.forEach { it.clear() }
            highlights.values.forEach { it.clear() }
            marginStrokes.values.forEach { it.clear() }
            images.values.forEach { it.clear() }
            notes.values.forEach { it.clear() }
            loaded.clear(); renders.clear(); bitmaps.clear(); requestedBitmaps.clear()
            selection = null
            undoStack.clear(); redoStack.clear(); editVersion++
            layers.clear(); layers.addAll(result.first)
            if (layers.none { it.id == activeLayerId }) activeLayerId = layers.firstOrNull()?.id ?: 1L
            bookmarks.clear(); bookmarks.addAll(result.second)
            dataGeneration++
            message = "Notes restored."
        }
    }

    companion object {
        private const val COPY_SHIFT = 30f
        private const val MAX_HISTORY = 100
        const val PASSAGE_LIMIT = 80
    }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(dbDispatcher) { block() }
    }

    override fun onCleared() {
        super.onCleared()
        dbDispatcher.close()
    }
}
