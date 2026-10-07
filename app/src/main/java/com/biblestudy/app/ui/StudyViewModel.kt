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
import com.biblestudy.app.data.RefLinks
import com.biblestudy.app.data.StudyEntry
import com.biblestudy.app.data.StudyRepository
import com.biblestudy.app.data.UserDb
import com.biblestudy.app.model.Annotation
import com.biblestudy.app.model.Drawn
import com.biblestudy.app.model.DrawnBox
import com.biblestudy.app.model.DrawnLine
import com.biblestudy.app.model.DrawnVerse
import com.biblestudy.app.model.CrossHighlight
import com.biblestudy.app.model.HighlightEntry
import com.biblestudy.app.model.Edit
import com.biblestudy.app.model.Heading
import com.biblestudy.app.model.Highlight
import com.biblestudy.app.model.InkStroke
import com.biblestudy.app.model.Layer
import com.biblestudy.app.model.MarginImage
import com.biblestudy.app.model.MarginText
import com.biblestudy.app.model.Region
import com.biblestudy.app.model.SideButton
import com.biblestudy.app.model.Sketch
import com.biblestudy.app.model.Paper
import com.biblestudy.app.model.TextFont
import com.biblestudy.app.model.TextStyleKey
import com.biblestudy.app.model.TypedNote
import com.biblestudy.app.model.Tool
import com.biblestudy.app.model.VerseId
import com.biblestudy.app.model.VerseTarget
import com.biblestudy.app.model.translated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
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
    /** Where this panel was scrolled to, kept while its tab is out of view (not observable). */
    var resume: ScrollPos? = null
        /** The verse at the top of the view (observable, so a cross-references pane can follow it). */
    var topVerse by mutableIntStateOf(1)
    /** The last verse of the current chapter that has been in view (ANL-2); past the end means it was all seen. */
    var seenTo by mutableIntStateOf(0)
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

/**
 * A saved layout (SPLIT-6): every tab with its panels, as [TabState.listToJson] writes them, and the
 * tab that was in front. Layouts saved before 1.2 (Bible panels and a study pane) open as tabs of
 * at most two panels.
 */
class Workspace(val name: String, private val tabsJson: String, val active: Int) {
    fun toJson(): String = org.json.JSONObject().put("tabs", org.json.JSONArray(tabsJson)).put("active", active).toString()

    /** The layout's tabs, made fresh each time it is opened. */
    fun tabs(place: (Int, Int, String?) -> Triple<Int, Int, String>): List<TabState> =
        TabState.listFromJson(tabsJson, place).orEmpty()

    companion object {
        fun fromJson(name: String, json: String): Workspace? = runCatching {
            val o = org.json.JSONObject(json)
            if (o.has("tabs")) return@runCatching Workspace(name, o.getJSONArray("tabs").toString(), o.optInt("active"))
            // Before 1.2: up to three Bible panels, a study pane, linking and sizes.
            val p = o.getJSONArray("panels")
            val pane = PaneKind.entries.firstOrNull { it.name == o.optString("pane") }
            val w = o.optJSONArray("weights")
            fun panel(i: Int) = p.getJSONObject(i).let {
                org.json.JSONObject().put("b", it.getInt("b")).put("c", it.getInt("c")).put("v", it.getString("v"))
            }
            val tabs = org.json.JSONArray()
            val first = org.json.JSONObject()
            val firstPanels = org.json.JSONArray()
            for (i in 0 until minOf(p.length(), 2)) firstPanels.put(panel(i))
            first.put("panels", firstPanels)
            first.put("linked", o.optBoolean("linked") && p.length() >= 2)
            if (w != null && w.length() >= 2) first.put("split", w.getDouble(0) / (w.getDouble(0) + w.getDouble(1)))
            if (pane != null && p.length() == 1) first.put("studies", org.json.JSONArray().put(pane.name))
            tabs.put(first)
            if (p.length() > 2 || (pane != null && p.length() > 1)) {
                val second = org.json.JSONObject()
                second.put("panels", org.json.JSONArray().put(panel(if (p.length() > 2) 2 else 0)))
                if (pane != null && p.length() > 1) second.put("studies", org.json.JSONArray().put(pane.name))
                tabs.put(second)
            }
            Workspace(name, tabs.toString(), 0)
        }.getOrNull()
    }
}

/** How often notes are backed up automatically (DATA-6). */
enum class AutoBackup(val label: String, val days: Int) { OFF("Off", 0), DAILY("Daily", 1), WEEKLY("Weekly", 7) }

/** A chapter to export as a PDF or picture (DATA-5), handled by the active panel. */
/** Export the active panel's chapter (DATA-5); with [layer], only that layer's notes (LAY-11). */
data class ExportRequest(val uri: Uri, val pdf: Boolean, val layer: Long? = null)

/** What the study pane beside the Bible panels shows (SPLIT-2). */
enum class PaneKind(val label: String) {
    SEARCH("Search"), CROSSREFS("Cross-references"), NOTES("My notes"),
    DICTIONARY("Dictionary"), TOPICS("Topics"), COMMENTARY("Commentary"), NAMES("Names & places"),
    SKETCHES("Sketch pages"),
    VERSE("Verse details"), COMPARE("Compare versions"), ORIGINAL("Hebrew/Greek"), WORDSTUDY("Word study"), INTRO("About the book"),
    CHAT("AI chat"),
    ;

    companion object {
        /** The views under headings, as the panel menus list them (SPLIT-7). The Bible itself goes first. */
        val groups: List<Pair<String, List<PaneKind>>> = listOf(
            "Reading" to listOf(SKETCHES),
            "This verse" to listOf(VERSE, COMPARE, ORIGINAL, WORDSTUDY, CROSSREFS),
            "Study" to listOf(COMMENTARY, DICTIONARY, TOPICS, NAMES, INTRO),
            "Notes, search and AI" to listOf(NOTES, SEARCH, CHAT),
        )
    }
}

/** A spot to return to with Back / Forward. */
data class Place(val book: Int, val chapter: Int, val verse: Int)

/** A chapter read lately (READ-8): where you were in it, and when. */
data class RecentRead(val book: Int, val chapter: Int, val verse: Int, val at: Long)

class StudyViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("study", Context.MODE_PRIVATE)
    init { BibleRepository.loadImported(app) } // before panels restore their versions
    /** The KJV: book names, chapter counts and cross-references come from here for every version. */
    val bible = BibleRepository(app, BibleRepository.KJV)
    private val texts = HashMap<String, BibleRepository>().apply { put(bible.code, bible) }

    /** The text of one version, opened (and copied out of the APK) the first time it's needed. */
    @Synchronized
    fun text(code: String): BibleRepository = texts.getOrPut(code) {
        val v = BibleRepository.ALL.firstOrNull { it.code == code } ?: BibleRepository.KJV
        if (v.code == bible.code) bible else BibleRepository(getApplication(), v).also { r ->
            // An online Bible's chapters get word tags, words of Jesus and paragraphs as they come (BIB-12).
            r.online?.bookVerses = bookVerses
            r.online?.study = { db, parsed ->
                com.biblestudy.app.data.ImportStudy.addChapter(db, r.code, parsed, study, onlineTagger, guessRed = !r.online.marksRed(), web = text(BibleRepository.WEB.code))
            }
        }
    }

    /** Verses in each book of the KJV (index = book id), for the ESV's half-a-book limit. */
    private val bookVerses: IntArray by lazy {
        IntArray(67).also { a -> for (b in bible.books) a[b.id] = bible.versesBetween(b.id * 1_000_000, b.id * 1_000_000 + 999_999, 100_000).size }
    }

    /** Tags the words of online Bibles' chapters for word studies; made the first time one comes. */
    private val onlineTagger by lazy { com.biblestudy.app.data.ImportStudy.tagger(study, BibleRepository.BUNDLED.map { text(it.code) }) }

    // ---------- online Bibles (BIB-12) ----------

    /** Goes up when an online Bible's chapter has come, so pages waiting for it are laid out again. */
    var onlineArrivals by mutableIntStateOf(0)
        private set

    /**
     * A verse's text for the screen, without waiting: an online Bible's verse not downloaded yet
     * shows "Loading…" (and comes in the background); else the KJV's when a version lacks it.
     */
    fun verseTextNow(version: String, id: Int): String =
        text(version).verseText(id) ?: if (isOnline(version)) "Loading\u2026" else bible.verseText(id) ?: ""

    /** Whether [code] is an online Bible. */
    fun isOnline(code: String) = BibleRepository.ALL.any { it.code == code && it.online > 0 }

    /** Chapters that couldn't be fetched (no internet): tried again when the tablet is back online. */
    private var onlineFailed = false
    private var lastOnlineWarning = 0L

    private val onlineEvents = object : BibleRepository.OnlineEvents {
        override fun arrived(code: String, book: Int, chapter: Int) {
            viewModelScope.launch(Dispatchers.Main) { onlineArrivals++ }
        }
        override fun failed(code: String, book: Int, chapter: Int, error: Exception) {
            viewModelScope.launch(Dispatchers.Main) {
                onlineFailed = true
                val now = System.currentTimeMillis()
                if (now - lastOnlineWarning < 30_000) return@launch
                lastOnlineWarning = now
                val where = "$code ${bible.book(book).name} $chapter"
                message = when {
                    error is com.biblestudy.app.data.YouVersion.HttpException -> "Couldn't load $where: ${error.message}."
                    error.message?.startsWith("No YouVersion key") == true -> error.message
                    else -> "$where isn't on this tablet yet and there's no internet. It will show when you're back online."
                }
            }
        }
    }

    init {
        com.biblestudy.app.data.YouVersion.key = prefs.getString("youversionKey", null)?.takeIf { it.isNotBlank() } ?: com.biblestudy.app.BuildConfig.YOUVERSION_KEY
        com.biblestudy.app.data.Esv.key = prefs.getString("esvKey", null)?.takeIf { it.isNotBlank() } ?: com.biblestudy.app.BuildConfig.ESV_KEY
        com.biblestudy.app.data.Nlt.key = prefs.getString("nltKey", null)?.takeIf { it.isNotBlank() } ?: com.biblestudy.app.BuildConfig.NLT_KEY
        BibleRepository.onlineEvents = onlineEvents
        // Back online: chapters that couldn't be fetched are tried again.
        runCatching {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    viewModelScope.launch(Dispatchers.Main) { retryOnline() }
                }
            })
        }
    }

    /** Tries again the online chapters that couldn't be fetched: the pages waiting for them are laid out again. */
    fun retryOnline() {
        cardFetches.clear()
        if (onlineFailed) { onlineFailed = false; onlineArrivals++ }
    }

    /** Online chapters verse cards have asked for this session (see [cardVerses]). */
    internal val cardFetches = HashSet<String>()

    /** Keeps verse cards' verses one version at a time, so two runs don't fetch the same verses. */
    private val cardKeeper = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "card-verses").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    /**
     * The ESV and NLT keep at most 500 verses (BIB-12), so the verses of every verse card in
     * [version] are kept on the tablet while chapters come and go (SKT-6), and those missing are
     * downloaded by themselves. Runs in the background; cards are redrawn when they come.
     */
    fun keepCardVerses(version: String) {
        val online = text(version).online ?: return
        if (!online.limited) return
        viewModelScope.launch {
            // After the notes waiting to be saved, so a card just made is included.
            val bodies = withContext(dbDispatcher) { user.allTextBodies() }
            val ok = withContext(cardKeeper) {
                try {
                    val ids = bodies.mapNotNull { cardSpecOf(it) }.filter { it.version == version }
                        .flatMap { s -> bible.versesBetween(s.passage.startId, s.passage.endId, PASSAGE_LIMIT).map { it.first } }
                    online.keep(ids)
                    val missing = online.missingKept()
                    if (missing.isNotEmpty()) online.fetchVerses(missing)
                    missing.isNotEmpty()
                } catch (e: Exception) {
                    null // no internet: tried again when the tablet is back online (retryOnline)
                }
            }
            when (ok) {
                null -> onlineFailed = true
                true -> onlineArrivals++
                false -> {}
            }
        }
    }

    /** A verse card was made or changed: its verses are kept from now on. */
    private fun cardChanged(body: String) {
        val spec = cardSpecOf(body) ?: return
        if (text(spec.version).online?.limited == true) { cardFetches.remove("keep ${spec.version}") }
    }

    /** The YouVersion key typed in Settings, or empty for the one built in. */
    var youVersionKey by mutableStateOf(prefs.getString("youversionKey", "") ?: "")
        private set

    fun changeYouVersionKey(k: String) {
        youVersionKey = k.trim()
        prefs.edit { putString("youversionKey", youVersionKey) }
        com.biblestudy.app.data.YouVersion.key = youVersionKey.ifEmpty { com.biblestudy.app.BuildConfig.YOUVERSION_KEY }
        onlineBibles = null
    }

    /** The NLT key typed in Settings, or empty for the one built in. */
    var nltKey by mutableStateOf(prefs.getString("nltKey", "") ?: "")
        private set

    fun changeNltKey(k: String) {
        nltKey = k.trim()
        prefs.edit { putString("nltKey", nltKey) }
        com.biblestudy.app.data.Nlt.key = nltKey.ifEmpty { com.biblestudy.app.BuildConfig.NLT_KEY }
        onlineBibles = null
    }

    /** The ESV key typed in Settings, or empty for the one built in. */
    var esvKey by mutableStateOf(prefs.getString("esvKey", "") ?: "")
        private set

    fun changeEsvKey(k: String) {
        esvKey = k.trim()
        prefs.edit { putString("esvKey", esvKey) }
        com.biblestudy.app.data.Esv.key = esvKey.ifEmpty { com.biblestudy.app.BuildConfig.ESV_KEY }
        onlineBibles = null
    }

    /** The online Bibles YouVersion offers (English), once looked up; null until then. */
    var onlineBibles by mutableStateOf<List<com.biblestudy.app.data.YouVersion.Info>?>(null)
        private set
    var onlineListError by mutableStateOf<String?>(null)
        private set

    fun loadOnlineBibles() {
        onlineListError = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { com.biblestudy.app.data.YouVersion.bibles() } }
            // The ESV comes from Crossway, not YouVersion, when there's a key for it.
            val esv = listOfNotNull(
                com.biblestudy.app.data.Esv.info.takeIf { com.biblestudy.app.data.Esv.key.isNotBlank() },
                com.biblestudy.app.data.Nlt.info.takeIf { com.biblestudy.app.data.Nlt.key.isNotBlank() },
            )
            r.onSuccess { list ->
                // Not the ones built in (the BSB and WEB are here already).
                val builtIn = BibleRepository.BUNDLED.map { it.code }.toSet() + "WEBUS"
                onlineBibles = (esv + list.filter { it.code !in builtIn }).sortedBy { it.title }
            }.onFailure {
                if (esv.isNotEmpty()) onlineBibles = esv
                onlineListError = "Couldn't reach YouVersion: ${it.message ?: "no internet"}"
            }
        }
    }

    /** Adds online Bible [info] to the version list; its chapters download as they're read. */
    fun addOnlineBible(info: com.biblestudy.app.data.YouVersion.Info) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val full = if (info.id >= com.biblestudy.app.data.Esv.ID) info // the ESV and NLT carry their own
                        else com.biblestudy.app.data.YouVersion.bible(info.id) ?: info // with its copyright
                    val v = com.biblestudy.app.data.OnlineBible.create(getApplication(), full, bible.books)
                    synchronized(this@StudyViewModel) { texts.remove(v.code) }
                    BibleRepository.addImported(getApplication(), v)
                    v
                }
            }
            message = r.fold({ "${it.code} added. Pick it from the version menu; chapters download as you read." }, { "Couldn't add ${info.code}: ${it.message}" })
        }
    }

    /** Save for offline: every chapter of an online Bible, downloading; [code] → done (0–1). */
    val onlineDownloads = mutableStateMapOf<String, Float>()
    private val downloadJobs = HashMap<String, kotlinx.coroutines.Job>()

    fun saveForOffline(code: String) {
        if (code in onlineDownloads) return
        val repo = text(code)
        val o = repo.online ?: return
        if (o.limited) return // the ESV may only keep 500 verses
        val chapters = bible.books.filter { it.id in o.books }.flatMap { b -> (1..b.chapters).map { b.id to it } }
        onlineDownloads[code] = o.savedChapters().toFloat() / chapters.size
        downloadJobs[code] = viewModelScope.launch {
            var failed: Throwable? = null
            withContext(Dispatchers.IO) {
                var done = 0
                for ((b, c) in chapters) {
                    if (!isActive) break
                    val r = runCatching { o.fetch(b, c) }
                    if (r.isFailure) { failed = r.exceptionOrNull(); break }
                    if (++done % 10 == 0) withContext(Dispatchers.Main) { onlineDownloads[code] = done.toFloat() / chapters.size }
                }
            }
            onlineDownloads.remove(code)
            downloadJobs.remove(code)
            onlineArrivals++
            message = when {
                failed != null -> "Saving $code stopped: ${failed?.message ?: "no internet"}. Tap Save for offline to carry on."
                o.savedChapters() >= chapters.size -> "$code is saved on this tablet and works offline."
                else -> null
            }
        }
    }

    fun stopSavingForOffline(code: String) {
        downloadJobs.remove(code)?.cancel()
        onlineDownloads.remove(code)
    }

    /** How much of online Bible [code] is on the tablet: saved chapters, out of all. */
    fun onlineSaved(code: String): Pair<Int, Int>? {
        val o = text(code).online ?: return null
        return o.savedChapters() to bible.books.filter { it.id in o.books }.sumOf { it.chapters }
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
    /** The tab's two Bible panels scroll together (SPLIT-3); each tab has its own setting. */
    var linkPanels: Boolean
        get() = tab.linked
        set(v) { tab.linked = v }
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
    /** The snapping highlighter draws a line under the words instead of a fill (HL-4). */
    var underlineMode by mutableStateOf(prefs.getBoolean("underline", false))
    /** Read mode (PEN-4): the pen scrolls and taps like a finger, so nothing is marked by accident. */
    var readMode by mutableStateOf(prefs.getBoolean("readMode", false))
    /** The version a new Bible panel opens in; null = the same as the panel it comes from. */
    var newPanelVersion by mutableStateOf(prefs.getString("newPanelVersion", null))
    /**
     * The Bible text's typeface (READ-3). Changing it reflows the lines, so ink on the words is
     * reloaded and moved to the same characters in the new layout.
     */
    var textFont by mutableStateOf(runCatching { TextFont.valueOf(prefs.getString("textFont", "BOOK")!!) }.getOrDefault(TextFont.BOOK))
        private set

    /**
     * Puts every setting back to its default (SET-4). Notes, ink, highlights, sketch pages, layers and
     * the open passages are not touched.
     */
    fun resetSettings() {
        theme = PageTheme.LIGHT
        changeTextFont(TextFont.BOOK)
        changeParagraphs(false)
        changeVerseNumbers(true)
        expandToFit = false
        marginsAllPanels = true
        lineSpacing = LineSpacing.NORMAL
        showHeadings = true
        newPanelVersion = null
        fingerDraw = false
        sideButton = SideButton.entries.first()
        partialEraser = false
        snapHighlights = true
        fastInk = true
        underlineMode = false
        readMode = false
        penSize = 1; highlightSize = 1
        highlightsAllVersions = true
        marginLeft = false; marginRight = true
        linkPanels = false
        compareVersions = false; originalView = false; redLetters = false; verseInPanel = false; verseCommentary = true; showGlance = true; hardWords = true
        changeWritingSounds(true); changeSoundVolume(0.6f)
        savePrefs()
        message = "Settings reset to their defaults."
    }

    fun changeTextFont(f: TextFont) {
        if (f == textFont) return
        textFont = f
        relayout()
    }

    /** Open space below a verse when its margin notes are taller than it (MRG-10). */
    var expandToFit by mutableStateOf(prefs.getBoolean("expandToFit", false))
    /** The expand-to-fit gaps each laid-out chapter has now ("KJV|43|3" → verse → height). */
    val fitGaps = mutableStateMapOf<String, Map<Int, Float>>()
    /** Margins in every Bible panel, or only the first (MRG-13). */
    var marginsAllPanels by mutableStateOf(prefs.getBoolean("marginsAllPanels", true))

    /**
     * The gaps a chapter needs so each verse's margin notes end before the next verse starts
     * (MRG-10): verse → extra height above it. Only notes on shown layers and margins count.
     */
    fun fitSpacers(layout: ChapterLayout): Map<Int, Float> {
        if (!expandToFit) return emptyMap()
        val visible = visibleLayerIds()
        fun shown(r: Region) = (r == Region.LEFT && marginLeft) || (r == Region.RIGHT && marginRight)
        val bottoms = HashMap<Int, Float>()
        fun need(v: Int, bottom: Float) { bottoms[v] = maxOf(bottoms[v] ?: 0f, bottom) }
        for (st in marginStrokesFor(layout.book, layout.chapter)) {
            if (st.layerId !in visible || !shown(st.region) || st.points.isEmpty()) continue
            var maxY = -Float.MAX_VALUE
            for (i in 1 until st.points.size step 3) maxY = maxOf(maxY, st.points[i])
            need(st.verse, maxY + st.width)
        }
        for (img in imagesFor(layout.book, layout.chapter)) if (img.layerId in visible && shown(img.region)) need(img.verse, img.y + img.h)
        for (t in textsFor(layout.book, layout.chapter)) if (t.layerId in visible && shown(t.region)) {
            need(t.verse, t.y + (textHeights[t.id] ?: estimateTextHeight(t)))
        }
        val vs = layout.verses
        val out = HashMap<Int, Float>()
        for (i in 0 until vs.size - 1) {
            val b = bottoms[vs[i]] ?: continue
            val next = vs[i + 1]
            val space = layout.verseTop(next) - layout.verseTop(vs[i]) - (layout.spacers[next] ?: 0f)
            val gap = b + 16f - space
            if (gap > 8f) out[next] = kotlin.math.ceil(gap / 8f) * 8f
        }
        return out
    }

    /** Paragraphs instead of one verse per line (READ-6). */
    var paragraphMode by mutableStateOf(prefs.getBoolean("paragraphs", false))
        private set
    /** Verse numbers shown (READ-6). */
    var verseNumbers by mutableStateOf(prefs.getBoolean("verseNumbers", true))
        private set

    /** Pen-on-paper sounds while writing (INK-15), and how loud (0..1). */
    val sound = WritingSound { WritingSound.loadLoops(getApplication<Application>().assets) }
    var writingSounds by mutableStateOf(prefs.getBoolean("writingSounds", true))
        private set
    var soundVolume by mutableFloatStateOf(prefs.getFloat("soundVolume", 0.6f))
        private set

    init { sound.enabled = writingSounds; sound.volume = soundVolume }

    fun changeWritingSounds(on: Boolean) { writingSounds = on; sound.enabled = on }
    fun changeSoundVolume(v: Float) { soundVolume = v.coerceIn(0f, 1f); sound.volume = soundVolume }

    /** Mark words that differ when two versions are side by side (SPLIT-5). */
    var markDifferences by mutableStateOf(prefs.getBoolean("markDifferences", false))

    /** The version to compare [p] with: another panel's, when it shows the same book in a different version. */
    fun diffVersionFor(p: PanelState): String? {
        if (!markDifferences || Sketch.isSketch(p.book)) return null
        return panels.firstOrNull { it !== p && it.book == p.book && it.version != p.version }?.version
    }

    /** Chapter at a glance (STD-22): the card under each Bible panel's header; on unless switched off. */
    var showGlance by mutableStateOf(prefs.getBoolean("glance", true))
    /** Whether the chapter cards are open (folded to one line to start with). */
    var glanceOpen by mutableStateOf(false)

    /** Hard words explained (STD-23): a dotted line under hard words; tap one for its meaning. */
    var hardWords by mutableStateOf(prefs.getBoolean("hardWords", true))

    /** The words of Jesus in red (BIB-8); only colours change, so ink stays where it is. */
    var redLetters by mutableStateOf(prefs.getBoolean("redLetters", false))

    fun changeParagraphs(on: Boolean) { if (on != paragraphMode) { paragraphMode = on; relayout() } }
    fun changeVerseNumbers(on: Boolean) { if (on != verseNumbers) { verseNumbers = on; relayout() } }

    /** The layout ink on the words is drawn in now. */
    fun styleKey() = TextStyleKey(textFont, paragraphMode, verseNumbers)

    /** The words move to new lines: ink on them is reloaded and moved along (READ-3, READ-6). */
    private fun relayout() {
        selection = null
        undoStack.clear(); redoStack.clear(); editVersion++
        loaded.removeAll { it.startsWith("t") }
        textStrokes.values.forEach { it.clear() }
    }
    /** Show highlights from other translations over whole verses (HL-10). */
    var highlightsAllVersions by mutableStateOf(prefs.getBoolean("hlAllVersions", true))
    /** The verse window shows the verse in every version, stacked (SPLIT-4). */
    var compareVersions by mutableStateOf(prefs.getBoolean("compareVersions", false))
    /** The verse window shows the Hebrew or Greek word by word (STD-4). */
    var originalView by mutableStateOf(prefs.getBoolean("originalView", false))
    /**
     * Tapping a verse shows its details in a panel beside the text rather than the pop-up window
     * (SPLIT-9). Off by default from 1.7: the pop-up is simpler (a new key, so everyone starts off).
     */
    var verseInPanel by mutableStateOf(prefs.getBoolean("versePanel", false))
    var lineSpacing by mutableStateOf(
        runCatching { LineSpacing.valueOf(prefs.getString("lineSpacing", "NORMAL")!!) }.getOrDefault(LineSpacing.NORMAL)
    )
    var sideButton by mutableStateOf(
        runCatching { SideButton.valueOf(prefs.getString("sideButton", "ERASER")!!) }.getOrDefault(SideButton.ERASER)
    )
    var marginLeft by mutableStateOf(prefs.getBoolean("marginLeft", false))
    var marginRight by mutableStateOf(prefs.getBoolean("marginRight", true))
    var theme by mutableStateOf(runCatching { PageTheme.valueOf(prefs.getString("theme", "LIGHT")!!) }.getOrDefault(PageTheme.LIGHT))
    /** A tab holds at most two panels (SPLIT-8); more go in other tabs. */
    val maxPanels = 2
    /** The window's width class (ADP-1). */
    var widthClass by mutableStateOf(WidthClass.EXPANDED)
    /**
     * The study view in the tab in front, if one is shown (SPLIT-2, SPLIT-7). Setting it shows that
     * view in a panel (see [showStudy]); null closes the tab's study views.
     */
    var sidePane: PaneKind?
        get() = tab.studies.firstOrNull()
        set(v) { if (v == null) closeStudies() else showStudy(v) }
    /** A search the search pane should run when it opens. */
    var paneSearch by mutableStateOf<String?>(null)
    /** The verse the cross-references pane shows; null follows the top of the active panel. */
    var paneVerse by mutableStateOf<VerseTarget?>(null)
    /** The verse at the top of the page when a verse was last tapped: a linked commentary follows the tap until the page moves (STD-18). */
    var paneVerseTop by mutableStateOf(-1)

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

    // ---------- tabs and panels (TAB-1, SPLIT-7, SPLIT-8) ----------
    /** The open tabs; each has its own panels. */
    val tabs = mutableStateListOf<TabState>()
    var activeTab by mutableIntStateOf(0)
    /** The tab in front. */
    val tab: TabState get() = tabs[activeTab.coerceIn(0, tabs.lastIndex)]
    /** The Bible panels of the tab in front. */
    val panels: androidx.compose.runtime.snapshots.SnapshotStateList<PanelState> get() = tab.panels
    var activePanel: Int
        get() = tab.activePanel
        set(v) { tab.activePanel = v }

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
    private val marginTexts = HashMap<String, SnapshotStateList<MarginText>>()
    private val notes = HashMap<String, SnapshotStateMap<Int, TypedNote>>()
    private val loaded = HashSet<String>()
    private val renders = HashMap<Long, StrokeRender>()
    val bitmaps = mutableStateMapOf<String, ImageBitmap>()
    private val requestedBitmaps = HashSet<String>()
    private var lastId = 0L

    init {
        val saved = prefs.getString("tabs", null)?.let { TabState.listFromJson(it, ::validPlace) }.orEmpty()
        if (saved.isNotEmpty()) {
            tabs.addAll(saved)
            activeTab = prefs.getInt("activeTab", 0).coerceIn(0, tabs.lastIndex)
        } else {
            tabs.addAll(tabsFromOldPrefs())
        }
        layers.addAll(user.layers())
        if (layers.isEmpty()) {
            val l = Layer(1L, "My Notes", LAYER_COLORS[0], visible = true, locked = false, sort = 0)
            layers.add(l); user.saveLayer(l)
        }
        if (layers.none { it.id == activeLayerId }) activeLayerId = layers.first().id
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
            putBoolean("partialEraser", partialEraser); putBoolean("fastInk", fastInk); putBoolean("trackReading", trackReading); putString("autoBackup", autoBackup.name); putString("backupFolder", backupFolder); putBoolean("underline", underlineMode); putBoolean("readMode", readMode); putString("newPanelVersion", newPanelVersion); putString("textFont", textFont.name); putBoolean("paragraphs", paragraphMode); putBoolean("expandToFit", expandToFit); putBoolean("marginsAllPanels", marginsAllPanels); putBoolean("verseNumbers", verseNumbers); putBoolean("redLetters", redLetters); putBoolean("writingSounds", writingSounds); putFloat("soundVolume", soundVolume); putBoolean("markDifferences", markDifferences); putBoolean("hlAllVersions", highlightsAllVersions); putBoolean("compareVersions", compareVersions); putBoolean("originalView", originalView); putBoolean("versePanel", verseInPanel); putBoolean("verseCommentary2", verseCommentary); putBoolean("glance", showGlance); putString("lastBibleVersion", lastBibleVersion); putBoolean("hardWords", hardWords)
            putBoolean("marginLeft", marginLeft); putBoolean("marginRight", marginRight)
            putString("theme", theme.name); putLong("activeLayer", activeLayerId)
            marginWidths.forEach { (k, v) -> putFloat(k, v) }
            putString("tabs", savedTabsJson())
            putInt("activeTab", activeTab)
        }
    }

    fun currentWidth(highlighter: Boolean) =
        if (highlighter) HIGHLIGHT_SIZES[highlightSize.coerceIn(0, 2)] else PEN_SIZES[penSize.coerceIn(0, 2)]

    // ---------- Back and Forward for the whole screen (NAV-1) ----------

    /** The screen as it was: every tab, what its panels show and where, and the tab in front. */
    class Screen(val tabs: String, val active: Int, val key: String)

    /** Screens to go back to (newest last), and forward to after going back. */
    val backSteps = mutableStateListOf<Screen>()
    val forwardSteps = mutableStateListOf<Screen>()
    private var stepDepth = 0
    private var restoring = false

    private fun screenNow(): Screen {
        val json = TabState.listToJson(tabs)
        // Jumps within a chapter change no saved field, so each panel's jump count is part of the key.
        return Screen(json, activeTab, json + activeTab + tabs.joinToString { t -> t.panels.joinToString { "${it.navGen}" } })
    }

    /**
     * Runs a change to what's on screen (a jump, a panel opened, closed or changed, a tab opened,
     * switched or closed) as one step Back can undo. Changes made inside it are part of the same step.
     */
    private inline fun step(block: () -> Unit) {
        val outer = stepDepth == 0 && !restoring
        val before = if (outer) screenNow() else null
        stepDepth++
        try {
            block()
        } finally {
            stepDepth--
            if (before != null && screenNow().key != before.key) {
                backSteps.add(before)
                while (backSteps.size > MAX_HISTORY) backSteps.removeAt(0)
                forwardSteps.clear()
            }
        }
    }

    /** Back: the screen as it was before the last change (NAV-1). */
    fun back() {
        val to = backSteps.removeLastOrNull() ?: return
        forwardSteps.add(screenNow())
        restore(to)
    }

    /** Forward: undoes Back. */
    fun forward() {
        val to = forwardSteps.removeLastOrNull() ?: return
        backSteps.add(screenNow())
        restore(to)
    }

    fun clearSteps() { backSteps.clear(); forwardSteps.clear() }

    /** Back several steps at once, to [i] in [backSteps] (from the list under a held ←). */
    fun backTo(i: Int) {
        if (i !in backSteps.indices) return
        forwardSteps.add(screenNow())
        while (backSteps.size > i + 1) forwardSteps.add(backSteps.removeAt(backSteps.lastIndex))
        restore(backSteps.removeAt(i))
    }

    /** Forward several steps at once, to [i] in [forwardSteps] (from the list under a held →). */
    fun forwardTo(i: Int) {
        if (i !in forwardSteps.indices) return
        backSteps.add(screenNow())
        while (forwardSteps.size > i + 1) backSteps.add(forwardSteps.removeAt(forwardSteps.lastIndex))
        restore(forwardSteps.removeAt(i))
    }

    /** A screen in words, for the Back and Forward lists: "John 3 and Commentary · 2 tabs". */
    fun screenLabel(s: Screen): String = runCatching {
        val all = org.json.JSONArray(s.tabs)
        val t = all.getJSONObject(s.active.coerceIn(0, all.length() - 1))
        val parts = ArrayList<String>()
        val hidden = t.optBoolean("hidden")
        val ps = t.getJSONArray("panels")
        if (!hidden) for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            val b = p.optInt("b", 43)
            parts += sketchOf(b)?.name ?: "${bible.book(b.coerceIn(1, 66)).name} ${p.optInt("c", 1)}"
        }
        val st = t.optJSONArray("studies") ?: org.json.JSONArray()
        for (i in 0 until st.length()) PaneKind.entries.firstOrNull { it.name == st.optString(i) }?.let { parts += it.label }
        parts.joinToString(" and ") + if (all.length() > 1) " \u00b7 ${all.length()} tabs" else ""
    }.getOrDefault("Earlier screen")

    /**
     * Start fresh (NAV-2): one tab with one Bible panel, at the passage being read. Back undoes it.
     */
    fun startFresh() {
        val p = panels.getOrNull(activePanel.coerceIn(0, panels.lastIndex)) ?: return
        step {
            selection = null; passagePop = null; linkPos = null; commentaryPos = null
            chatWindow = false
            val (b, c) = if (Sketch.isSketch(p.book)) biblePlaceBefore(p).let { it.first to it.second } else p.book to p.chapter
            val t = TabState()
            t.panels.add(PanelState(b, c).apply { version = p.version; pendingVerse = if (b == p.book) p.topVerse else null; navGen = p.navGen + 1 })
            tabs.clear(); tabs.add(t)
            activeTab = 0
        }
    }

    /** A saved place made valid; sketch pages that still exist stay sketch pages. */
    private fun screenPlace(book: Int, chapter: Int, version: String?): Triple<Int, Int, String> =
        if (Sketch.isSketch(book) && sketchOf(book) != null) Triple(book, 1, validVersion(version)) else validPlace(book, chapter, version)

    /** A tab's arrangement without where its panels are, to tell whether only the passages differ. */
    private fun shapeOf(o: org.json.JSONObject): String {
        val c = org.json.JSONObject(o.toString())
        val ps = c.getJSONArray("panels")
        for (i in 0 until ps.length()) ps.getJSONObject(i).let { p -> listOf("b", "c", "t", "v", "zl", "zland", "zport").forEach { p.remove(it) } }
        return c.toString()
    }

    private fun restore(s: Screen) {
        restoring = true
        try {
            selection = null; passagePop = null; linkPos = null; commentaryPos = null
            val saved = org.json.JSONArray(s.tabs)
            val now = org.json.JSONArray(TabState.listToJson(tabs))
            val sameShape = saved.length() == now.length() && (0 until saved.length()).all { shapeOf(saved.getJSONObject(it)) == shapeOf(now.getJSONObject(it)) }
            if (sameShape) {
                // Only the passages differ: move the panels there, keeping everything else as it is.
                for (t in 0 until saved.length()) {
                    val ps = saved.getJSONObject(t).getJSONArray("panels")
                    tabs[t].panels.forEachIndexed { i, p ->
                        val j = ps.optJSONObject(i) ?: return@forEachIndexed
                        val (b, c, v) = screenPlace(j.optInt("b", p.book), j.optInt("c", p.chapter), j.optString("v", p.version))
                        val verse = j.optInt("t", 1)
                        if (p.version != v) p.version = v
                        if (p.book != b || p.chapter != c || p.topVerse != verse) jump(p, b, c, verse)
                    }
                }
            } else {
                val list = TabState.listFromJson(s.tabs, ::screenPlace)?.takeIf { it.isNotEmpty() } ?: return
                tabs.clear(); tabs.addAll(list)
            }
            activeTab = s.active.coerceIn(0, tabs.lastIndex)
        } finally {
            restoring = false
        }
    }

    // ---------- navigation ----------

    /**
     * Jumps a panel to a passage. Jumps from the picker, search, cross-references and notes
     * are remembered for Back; the chapter arrows pass [remember] = false.
     */
    fun goTo(index: Int, book: Int, chapter: Int, verse: Int? = null, remember: Boolean = true) {
        val p = panels.getOrNull(index) ?: return
        if (remember) {
            val here = p.here()
            if (p.back.lastOrNull() != here) p.back.add(here)
            while (p.back.size > MAX_HISTORY) p.back.removeAt(0)
            p.forward.clear()
            step { jump(p, book, chapter, verse) }
        } else jump(p, book, chapter, verse)
    }

    private fun jump(p: PanelState, book: Int, chapter: Int, verse: Int?) {
        if (Sketch.isSketch(book)) {
            // A sketch page (SKT-1): one page, no chapters around it.
            if (sketchOf(book) == null) return
            p.book = book
            p.chapter = 1
            p.pendingVerse = null
            p.navGen++
            return
        }
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
        if (!Sketch.isSketch(p.book)) lastBibleVersion = p.version
    }

    /** The version last chosen for a Bible panel: what a sketch page on its own uses for verse cards. */
    var lastBibleVersion by mutableStateOf(prefs.getString("lastBibleVersion", null) ?: "KJV")
        private set

    /** Section headings for a chapter. Only the BSB has them; being public domain, they are shown in every version. */
    fun headings(book: Int, chapter: Int): List<Heading> = text(BibleRepository.BSB.code).headings(book, chapter)

    /** The version shown in the active panel (used by search and the verse popup). */
    val activeVersion: String get() {
        val p = panels.getOrNull(activePanel) ?: return bible.code
        if (!Sketch.isSketch(p.book)) return p.version
        // A sketch page has no version of its own: the Bible beside it, else the one read last.
        return panels.firstOrNull { !Sketch.isSketch(it.book) }?.version ?: validVersion(lastBibleVersion)
    }

    /** The chapter before (dir = -1) or after (dir = 1), or null at either end of the Bible. */
    fun neighbor(book: Int, chapter: Int, dir: Int): Pair<Int, Int>? = when {
        Sketch.isSketch(book) -> null
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

    fun toggleSplit() {
        if (tab.shown == 1) addPanel() else while (panels.size > 1) closePanel(panels.lastIndex)
    }

    /** A Bible passage and version as saved, made valid (sketch pages reopen on their passage). */
    private fun validPlace(book: Int, chapter: Int, version: String?): Triple<Int, Int, String> {
        val b = book.coerceIn(1, 66)
        return Triple(b, chapter.coerceIn(1, bible.book(b).chapters), validVersion(version))
    }

    /** Where a panel is, for saving: a sketch page is saved as its passage (or where you were before it). */
    private fun savedPlace(p: PanelState): Pair<Int, Int> {
        val sk = sketchOf(p.book)
        return when {
            sk == null -> p.book to p.chapter
            sk.linked -> sk.linkBook to sk.linkChapter
            else -> biblePlaceBefore(p).let { it.first to it.second }
        }
    }

    /** The open tabs as they are saved when the app closes (TAB-4). */
    fun savedTabsJson(): String = tabsJson(tabs)

    private fun tabsJson(list: List<TabState>): String {
        val json = org.json.JSONArray(TabState.listToJson(list))
        // Sketch pages are saved as their passages.
        list.forEachIndexed { t, tab ->
            val ps = json.getJSONObject(t).getJSONArray("panels")
            tab.panels.forEachIndexed { i, p ->
                val (b, c) = savedPlace(p)
                ps.getJSONObject(i).put("b", b).put("c", c)
            }
        }
        return json.toString()
    }

    /**
     * Before 1.2 the screen had up to three Bible panels and a study pane. They become tabs of at
     * most two panels: a third Bible panel, or the study pane beside two Bible panels, moves to a
     * second tab.
     */
    private fun tabsFromOldPrefs(): List<TabState> {
        val old = (0 until prefs.getInt("panels", 1).coerceIn(1, 3)).map { i ->
            val (b, c, v) = validPlace(prefs.getInt("p${i}b", 43), prefs.getInt("p${i}c", 3), prefs.getString("p${i}v", null))
            PanelState(b, c).apply {
                version = v
                for (o in listOf("land", "port")) zoomRel[o] = prefs.getFloat("p${i}z_$o", 1f)
                lastZoomRel = prefs.getFloat("p${i}zl", 2f)
            }
        }
        val pane = prefs.getString("sidePane", null)?.let { n -> PaneKind.entries.firstOrNull { it.name == n } }
        val first = TabState().apply {
            panels.addAll(old.take(2))
            linked = prefs.getBoolean("linkPanels", false) && panels.size == 2
            split = prefs.getFloat("split", 0.5f).coerceIn(TabState.SPLIT_MIN, 1f - TabState.SPLIT_MIN)
            if (pane != null && old.size == 1) {
                addStudy(pane)
                split = (1f - prefs.getFloat("paneFraction", 0.32f)).coerceIn(TabState.SPLIT_MIN, 1f - TabState.SPLIT_MIN)
            }
        }
        val out = mutableListOf(first)
        val rest = old.drop(2)
        if (rest.isNotEmpty() || (pane != null && old.size > 1)) {
            out.add(TabState().apply {
                panels.add(rest.firstOrNull() ?: PanelState(old[0].book, old[0].chapter).apply { version = old[0].version })
                if (pane != null && old.size > 1) addStudy(pane)
            })
        }
        return out
    }

    /** Opens another Bible panel in this tab, showing the active one's passage. */
    fun addPanel() {
        step {
            if (tab.shown >= maxPanels) {
                message = "A tab holds two panels. Open a new tab for more."
                return
            }
            val p = panels[activePanel.coerceIn(0, panels.lastIndex)]
            if (tab.bibleHidden) {
                // Both panels showed study views and one was closed: bring the Bible panel back.
                tab.bibleHidden = false
                tab.studyFirst = true // the study view stays where it was; the Bible comes in beside it
                activePanel = 0
                return
            }
            panels.add(PanelState(p.book, p.chapter).apply { version = validVersion(newPanelVersion ?: p.version) })
            activePanel = panels.lastIndex
        }
    }

    /**
     * Closes Bible panel [index]. The tab's last Bible panel can't go, but beside a study view it
     * is kept out of sight; a tab left with nothing is closed.
     */
    fun closePanel(index: Int) {
        step {
            val t = tab
            when {
                t.panels.size > 1 && index in t.panels.indices -> {
                    t.panels.removeAt(index)
                    t.linked = false
                }
                t.studies.isNotEmpty() -> t.bibleHidden = true
                tabs.size > 1 -> { closeTab(activeTab); return }
            }
            t.activePanel = 0
        }
    }

    /** The weights of the panels on screen. */
    fun weights(): List<Float> = if (tab.shown == 2) listOf(tab.split, 1f - tab.split) else listOf(1f)

    /** Moves the divider by [delta] (a fraction of the space both panels share). */
    fun dragDivider(@Suppress("UNUSED_PARAMETER") i: Int, delta: Float) {
        tab.split = (tab.split + delta).coerceIn(TabState.SPLIT_MIN, 1f - TabState.SPLIT_MIN)
    }

    /** Top and bottom (true) or side by side (false), as chosen for the tab in front. */
    fun isStacked(t: TabState = tab): Boolean = t.stacked ?: !landscape

    fun setStacked(stacked: Boolean) { tab.stacked = stacked }

    // ---------- study views (SPLIT-7) ----------

    /**
     * Shows a study view in the tab in front: it replaces the study view already shown, or takes
     * the second panel. With two Bible panels, the one not in use makes way for it.
     */
    fun showStudy(kind: PaneKind) {
        step {
            val t = tab
            if (kind in t.studies) return
            when {
                t.studies.isNotEmpty() -> t.setStudyAt(0, kind)
                t.panels.size >= 2 -> {
                    // The second panel makes way, unless the first is a sketch page beside Bible text.
                    val other = if (Sketch.isSketch(t.panels[0].book) && !Sketch.isSketch(t.panels[1].book)) 0 else 1
                    t.panels.removeAt(other)
                    t.linked = false
                    t.activePanel = 0
                    t.addStudy(kind)
                    t.studyFirst = other == 0
                }
                else -> { t.addStudy(kind); t.studyFirst = false }
            }
            t.normalize()
        }
    }

    /** Closes study view [kind] in the tab in front. */
    fun closeStudy(kind: PaneKind) {
        step {
            val t = tab
            t.studies.indexOf(kind).takeIf { it >= 0 }?.let { t.removeStudyAt(it) }
            if (t.studies.isEmpty()) {
                t.bibleHidden = false
                t.pinned = null
            }
            t.normalize()
        }
    }

    /** Closes the study views in the tab in front, leaving its Bible panel. */
    fun closeStudies() {
        for (k in tab.studies.toList()) closeStudy(k)
    }

    /** Opens (or switches) the study view; the same kind again closes it. */
    fun togglePane(kind: PaneKind) {
        if (kind in tab.studies) closeStudy(kind) else showStudy(kind)
    }

    /** Turns the panel at [slot] into [view]: a study view, or (null) a Bible panel. */
    fun setSlotView(slot: Slot, view: PaneKind?) {
        step {
            val t = tab
            when (slot) {
                is Slot.Bible -> {
                    if (view == null) return
                    if (view in t.studies && view != PaneKind.COMMENTARY) { message = "${view.label} is already open in this tab."; return }
                    if (t.panels.size >= 2) {
                        t.panels.removeAt(slot.index)
                        t.linked = false
                        t.activePanel = 0
                        t.addStudy(view)
                        t.studyFirst = slot.index == 0
                    } else {
                        // The tab's only Bible panel: it stays, out of sight, keeping the passage.
                        if (t.studies.isEmpty()) t.addStudy(view)
                        else if (t.studyFirst) t.addStudy(view) else t.addStudy(view, at = 0)
                        t.bibleHidden = true
                    }
                }
                is Slot.Study -> {
                    val i = slot.pos.takeIf { it in t.studies.indices && t.studies[it] == slot.kind } ?: t.studies.indexOf(slot.kind)
                    if (i < 0) return
                    if (view != null) {
                        if (view in t.studies && view != PaneKind.COMMENTARY) { message = "${view.label} is already open in this tab."; return }
                        t.setStudyAt(i, view)
                    } else {
                        // Back to a Bible panel at the passage the study view was working with.
                        t.removeStudyAt(i)
                        if (t.bibleHidden) {
                            t.bibleHidden = false
                            t.studyFirst = i == 1
                        } else {
                            val p = t.panels[t.activePanel.coerceIn(0, t.panels.lastIndex)]
                            t.panels.add(if (i == 0 && t.studyFirst) 0 else t.panels.size, PanelState(p.book, p.chapter).apply { version = p.version })
                            t.activePanel = if (t.studyFirst) 0 else t.panels.lastIndex
                        }
                    }
                }
            }
            t.normalize()
        }
    }

    /** Closes the panel at [slot]; the tab's last panel closes the tab (if there's another). */
    fun closeSlot(slot: Slot) {
        step {
            when (slot) {
                is Slot.Bible -> closePanel(slot.index)
                is Slot.Study -> {
                    if (tab.shown == 1 && tabs.size > 1) closeTab(activeTab)
                    else if (tab.shown > 1) {
                        val t = tab
                        val i = slot.pos.takeIf { it in t.studies.indices && t.studies[it] == slot.kind } ?: t.studies.indexOf(slot.kind)
                        if (i >= 0) t.removeStudyAt(i)
                        if (t.studies.isEmpty()) { t.bibleHidden = false; t.pinned = null }
                        t.normalize()
                    }
                }
            }
        }
    }

    /**
     * The Bible panel study views work with: the tab's active one, or a copy fixed in place
     * while the study views are pinned.
     */
    fun studyPanel(): PanelState = tab.pinned ?: panels[activePanel.coerceIn(0, panels.lastIndex)]

    /** Pins the tab's study views to the passage they show now, or lets them follow again. */
    fun togglePin() {
        val t = tab
        t.pinned = if (t.pinned != null) null else {
            val p = panels[activePanel.coerceIn(0, panels.lastIndex)]
            PanelState(p.book, p.chapter).apply { version = p.version; topVerse = p.topVerse }
        }
    }

    // ---------- tabs (TAB-1 to TAB-4) ----------

    /** A tab's name: the one given, or what its first panel shows. */
    fun tabLabel(t: TabState): String {
        t.name?.let { return it }
        return when (val s = t.slots().firstOrNull()) {
            is Slot.Study -> s.kind.label
            is Slot.Bible -> t.panels.getOrNull(s.index)?.let { p ->
                sketchOf(p.book)?.name ?: "${bible.book(p.book.coerceIn(1, 66)).name} ${p.chapter}"
            } ?: "Tab"
            null -> "Tab"
        }
    }

    /** Opens a new tab after the one in front, on [book]:[chapter] (default: where you are). */
    fun newTab(book: Int? = null, chapter: Int = 1, verse: Int? = null, version: String? = null) {
        step {
            val from = panels.getOrNull(activePanel)
            val t = TabState()
            val p = PanelState(from?.book ?: 43, from?.chapter ?: 3).apply { this.version = version ?: from?.version ?: bible.code }
            t.panels.add(p)
            tabs.add(activeTab + 1, t)
            selectTab(activeTab + 1)
            if (book != null) jump(p, book, chapter, verse) else p.pendingVerse = from?.topVerse
        }
    }

    /** Opens the panel at [slot] in a new tab of its own (TAB-3). */
    fun openSlotInNewTab(slot: Slot) {
        step {
            when (slot) {
                is Slot.Bible -> {
                    val p = panels.getOrNull(slot.index) ?: return
                    newTab(p.book, p.chapter, p.topVerse, p.version)
                }
                is Slot.Study -> {
                    val from = tab
                    val p = studyPanel()
                    newTab(p.book, p.chapter, p.topVerse, p.version)
                    tab.addStudy(slot.kind, commentary = from.commentaries.getOrElse(slot.pos) { "" })
                    tab.bibleHidden = true
                    tab.normalize()
                }
            }
        }
    }

    fun selectTab(i: Int) {
        commentaryPos = null // a commentary scrolled in the old tab doesn't move the new one
        if (i !in tabs.indices || i == activeTab) { activeTab = i.coerceIn(0, tabs.lastIndex); return }
        step {
            selection = null
            passagePop = null
            linkPos = null
            activeTab = i
        }
    }

    fun closeTab(i: Int) {
        step {
            if (tabs.size <= 1 || i !in tabs.indices) return
            selection = null
            tabs.removeAt(i)
            activeTab = when {
                activeTab > i -> activeTab - 1
                activeTab == i -> i.coerceAtMost(tabs.lastIndex)
                else -> activeTab
            }
        }
    }

    fun renameTab(i: Int, name: String) {
        tabs.getOrNull(i)?.name = name.trim().ifEmpty { null }
    }

    /** Moves tab [i] one place left (-1) or right (1). */
    fun moveTab(i: Int, dir: Int) {
        val j = i + dir
        if (i !in tabs.indices || j !in tabs.indices) return
        val front = tab
        val t = tabs.removeAt(i)
        tabs.add(j, t)
        activeTab = tabs.indexOf(front)
    }

    // ---------- saved layouts (SPLIT-6) ----------

    val workspaces = mutableStateListOf<Workspace>().apply {
        addAll(user.workspaces().mapNotNull { (n, j) -> Workspace.fromJson(n, j) })
    }

    /** Saves every tab, with its panels, under [name] (replacing one of that name). */
    fun saveWorkspace(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val w = Workspace(n, tabsJson(tabs), activeTab)
        workspaces.removeAll { it.name == n }
        workspaces.add(w)
        io { user.saveWorkspace(n, w.toJson()) }
        message = "Layout “$n” saved."
    }

    /** Opens a saved layout: its tabs replace the open ones. */
    fun openWorkspace(w: Workspace) {
        step {
            val list = w.tabs(::validPlace).ifEmpty { return }
            selection = null
            passagePop = null
            linkPos = null
            tabs.clear()
            tabs.addAll(list)
            activeTab = w.active.coerceIn(0, tabs.lastIndex)
        }
    }

    fun deleteWorkspace(w: Workspace) {
        workspaces.removeAll { it.name == w.name }
        io { user.deleteWorkspace(w.name) }
    }

    /**
     * Shows a verse's details (SPLIT-9): in the pop-up verse window; or, with *Verse details in a
     * panel* on in Settings, in the tab's Verse details panel, opened beside the text when the tab
     * has room (the window still opens when both panels are in use).
     */
    fun openVerse(book: Int, chapter: Int, verse: Int, word: Int = -1) {
        paneVerse = VerseTarget(book, chapter, verse, word)
        paneVerseTop = panels.getOrNull(activePanel.coerceIn(0, panels.lastIndex))?.topVerse ?: -1
        verseWordStudy = null
        val t = tab
        if (verseInPanel && (PaneKind.VERSE in t.studies || (t.studies.isEmpty() && t.shown < 2))) {
            verseSheet = null
            showStudy(PaneKind.VERSE)
        } else {
            verseSheet = VerseTarget(book, chapter, verse, word)
        }
        // A tapped word goes to the Word study panel too (SPLIT-7).
        if (word >= 0) viewModelScope.launch {
            val version = activeVersion
            val id = VerseId.of(book, chapter, verse)
            val w = withContext(Dispatchers.IO) {
                val strong = study.strongs(version, id).getOrNull(word)
                val text = text(version).verseText(id) ?: ""
                val range = com.biblestudy.app.data.StudyRepository.words(text).getOrNull(word)
                if (strong != null && range != null) WordStudy(strong, version, text.substring(range), id) else null
            }
            if (w != null) studyWord = w
        }
    }

    // ---------- Bibles: version manager and import (BIB-4, BIB-5) ----------

    /** Imports a Bible file (or several); runs off the main thread. */
    fun importBible(names: List<String>, open: (Int) -> java.io.InputStream, code: String, name: String, copyright: String) {
        val c = code.trim().uppercase()
        if (c.isEmpty() || name.isBlank() || copyright.isBlank()) { message = "Give the version a short code, a name and its copyright line."; return }
        if (BibleRepository.BUNDLED.any { it.code == c }) { message = "$c is already built in. Choose another code."; return }
        importing = true
        importStatus = "Importing\u2026"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // A version being replaced is read from its new file afterwards.
                    synchronized(this@StudyViewModel) { texts.remove(c) }
                    val addStudy = { db: android.database.sqlite.SQLiteDatabase, parsed: com.biblestudy.app.data.BibleImport.Parsed? -> buildStudy(db, c, parsed) }
                    if (names.size == 1 && names[0].lowercase().endsWith(".db")) {
                        open(0).use { com.biblestudy.app.data.BibleImport.saveAppDb(getApplication(), it, c, name.trim(), copyright.trim(), addStudy) }
                    } else {
                        val parsed = com.biblestudy.app.data.BibleImport.parse(names, open)
                        com.biblestudy.app.data.BibleImport.save(getApplication(), parsed, c, name.trim(), copyright.trim(), bible.books, addStudy)
                    }
                }
            }
            importing = false
            importStatus = null
            synchronized(this@StudyViewModel) { texts.remove(c) }
            message = result.fold({ "${it.code} added, with word studies. Pick it from the version menu." }, { "Couldn't import: ${it.message}" })
        }
    }

    var importing by mutableStateOf(false)
        private set

    /** What an import (or preparing word studies) is doing, shown on the import button. */
    var importStatus by mutableStateOf<String?>(null)
        private set

    /** Word tags, words of Jesus and paragraphs for imported version [code] (see [ImportStudy]). */
    private fun buildStudy(db: android.database.sqlite.SQLiteDatabase, code: String, parsed: com.biblestudy.app.data.BibleImport.Parsed?) {
        com.biblestudy.app.data.ImportStudy.build(db, code, parsed, study, BibleRepository.BUNDLED.map { text(it.code) }) { f ->
            val status = "Preparing word studies\u2026 ${(f * 100).toInt()}%"
            viewModelScope.launch(Dispatchers.Main) { if (importing) importStatus = status }
        }
    }

    /**
     * Versions imported before word studies worked for them (or restored from such a backup) get
     * their word tags, words of Jesus and paragraphs now, in the background.
     */
    fun prepareImported() {
        val todo = BibleRepository.ALL.filter { it.imported && it.online == 0 }
        if (todo.isEmpty() || importing) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for (v in todo) runCatching {
                    android.database.sqlite.SQLiteDatabase.openDatabase(v.asset, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                        if (!com.biblestudy.app.data.ImportStudy.isBuilt(db)) {
                            withContext(Dispatchers.Main) { importing = true }
                            buildStudy(db, v.code, null)
                        }
                    }
                }
            }
            if (importing) { importing = false; importStatus = null; dataGeneration++ }
        }
    }

    /** Removes an imported version; panels reading it go back to the KJV. */
    fun removeBible(code: String) {
        stopSavingForOffline(code)
        panels.forEachIndexed { i, p -> if (p.version == code) setVersion(i, "KJV") }
        if (newPanelVersion == code) newPanelVersion = null
        synchronized(this) { texts.remove(code) }
        BibleRepository.removeImported(getApplication(), code)
        message = "$code removed."
    }

    // ---------- sketch pages (SKT-1 to SKT-4) ----------

    val sketches = mutableStateListOf<Sketch>().apply { addAll(user.sketches()) }

    fun sketchOf(book: Int): Sketch? = if (Sketch.isSketch(book)) sketches.firstOrNull { it.book == book } else null

    /** Sketch pages linked to a chapter, shown as markers in its margin (SKT-2). */
    fun sketchesIn(book: Int, chapter: Int): List<Sketch> = sketches.filter { it.linkBook == book && it.linkChapter == chapter }

    /** A book's name, or a sketch page's. */
    fun bookLabel(book: Int): String = sketchOf(book)?.name ?: bible.book(book).name

    /** "John 3:16", or a sketch page's name. */
    fun hitLabel(book: Int, chapter: Int, verse: Int): String =
        sketchOf(book)?.let { "Sketch page: ${it.name}" } ?: refLabel(VerseId.of(book, chapter, verse))

    /** A heading for what a panel shows: "John 3", or a sketch page's name. */
    fun placeName(book: Int, chapter: Int): String = sketchOf(book)?.name ?: "${bible.book(book).name} $chapter"

    /** Makes a sketch page linked to the passage being read and opens it in the active panel. */
    /** The passage a new sketch page would be linked to: the one in view, or the open sketch page's. */
    fun sketchLinkHere(): Triple<Int, Int, Int>? {
        val p = panels[activePanel.coerceIn(0, panels.lastIndex)]
        val from = sketchOf(p.book) ?: return Triple(p.book, p.chapter, p.topVerse)
        return if (from.linked) Triple(from.linkBook, from.linkChapter, from.linkVerse) else null
    }

    /**
     * Makes a sketch page and opens it in the active panel. With [link] it's tied to that verse and
     * opens from a badge beside it (SKT-2); without, it stands alone and opens from My notes.
     */
    fun createSketch(name: String, paper: Paper, link: Triple<Int, Int, Int>? = sketchLinkHere(), open: Boolean = true, created: Long = System.currentTimeMillis()): Sketch {
        // Small ids, never reused: a sketch's book number is SKETCH_BOOK + id.
        val id = maxOf(prefs.getLong("nextSketch", 1L), (sketches.maxOfOrNull { it.id } ?: 0L) + 1)
        prefs.edit { putLong("nextSketch", id + 1) }
        val s = Sketch(
            id, name.trim().ifEmpty { "Sketch" }, paper,
            link?.first ?: 0, link?.second ?: 0, link?.third ?: 0,
            Sketch.START_HEIGHT, created,
        )
        sketches.add(s)
        io { user.saveSketch(s) }
        if (open) openSketch(s)
        return s
    }

    /**
     * Writes a verse's margin note full screen (MRG-15): its note page, made the first time, opens in
     * the active panel. Back beside the verse it shows shrunk to fit; tap it to open it again.
     */
    fun openNotePage(book: Int, chapter: Int, verse: Int) {
        val existing = sketches.firstOrNull { it.note && it.linkBook == book && it.linkChapter == chapter && it.linkVerse == verse }
        if (existing != null) { openSketch(existing); return }
        val s = createSketch("Note on ${refLabel(VerseId.of(book, chapter, verse))}", Paper.LINED, link = Triple(book, chapter, verse), open = false)
            .copy(note = true)
        updateSketch(s)
        openSketch(s)
    }

    private val sketchSizes = HashMap<Int, Pair<Int, Pair<Float, Float>>>()

    /**
     * A sketch page's size (SKT-1): it grows with what's on it, always keeping at least a page's
     * width and height of empty room to the right and below, so there's no edge to run into.
     */
    fun sketchSize(s: Sketch): Pair<Float, Float> {
        val strokes = marginStrokesFor(s.book, 1); val texts = textsFor(s.book, 1); val images = imagesFor(s.book, 1)
        val key = editCount * 31 + strokes.size * 7 + texts.size * 3 + images.size + s.height.toInt()
        sketchSizes[s.book]?.let { (k, size) -> if (k == key) return size }
        var right = 0f; var bottom = 0f
        for (st in strokes) for (i in st.points.indices step 3) { right = maxOf(right, st.points[i]); bottom = maxOf(bottom, st.points[i + 1]) }
        for (t in texts) { right = maxOf(right, t.x + t.w); bottom = maxOf(bottom, t.y + (textHeights[t.id] ?: estimateTextHeight(t))) }
        for (im in images) { right = maxOf(right, im.x + im.w); bottom = maxOf(bottom, im.y + im.h) }
        val w = maxOf(Sketch.WIDTH, right + Sketch.WIDTH)
        val h = maxOf(s.height, Page.TEXT_TOP + bottom + Sketch.START_HEIGHT)
        val size = w to h
        sketchSizes[s.book] = key to size
        return size
    }

    /** How far down a note page is written on, in page units from its top (MRG-15). */
    fun noteContentBottom(s: Sketch): Float {
        val strokes = marginStrokesFor(s.book, 1).maxOfOrNull { st -> (1 until st.points.size step 3).maxOfOrNull { st.points[it] } ?: 0f } ?: 0f
        val texts = textsFor(s.book, 1).maxOfOrNull { it.y + (textHeights[it.id] ?: estimateTextHeight(it)) } ?: 0f
        val images = imagesFor(s.book, 1).maxOfOrNull { it.y + it.h } ?: 0f
        return maxOf(strokes, texts, images, 120f)
    }

    /** Reads the writing on study articles of one kind and number (INK-16) from the notes database. */
    fun ensureStudyInkLoaded(book: Int, chapter: Int) {
        val m = mk(book, chapter)
        if (!loaded.add("m$m")) return
        loadStarted()
        viewModelScope.launch {
            try {
                val (st, _) = withContext(dbDispatcher) { user.loadMargin(book, chapter) }
                merge(marginStrokesFor(book, chapter), st)
            } finally {
                loadFinished()
            }
        }
    }

    /** Reads a sketch page's drawing from the notes database (for the shrunk note beside its verse). */
    fun ensureSketchLoaded(s: Sketch) {
        val m = mk(s.book, 1)
        if (!loaded.add("m$m")) return
        loadStarted()
        viewModelScope.launch {
            try {
                val (st, i) = withContext(dbDispatcher) { user.loadMargin(s.book, 1) }
                val t = withContext(dbDispatcher) { user.loadTexts(s.book, 1) }
                merge(marginStrokesFor(s.book, 1), st)
                merge(imagesFor(s.book, 1), i)
                merge(textsFor(s.book, 1), t)
            } finally {
                loadFinished()
            }
        }
    }

    /** The last Bible passage a panel showed before its sketch page, for leaving a free-standing page. */
    private fun biblePlaceBefore(p: PanelState): Pair<Int, Int> =
        p.back.lastOrNull { !Sketch.isSketch(it.book) }?.let { it.book to it.chapter } ?: (43 to 1)

    /**
     * The ready-made pages (SKT-5): made once, on first start, as ordinary free-standing sketch pages
     * on the first layer. Later it puts back any that were deleted ([announce] says how many).
     */
    fun addReadyMadePages(announce: Boolean = false, onlyNew: Boolean = false) {
        val layer = layers.firstOrNull() ?: return
        var added = 0
        // Pages made before (by name), so a new version adds its new pages but not ones you deleted.
        val made = prefs.getStringSet("readyMadeNames", null)?.toMutableSet()
            ?: (if (prefs.getBoolean("readyMadeAdded", false)) SketchTemplates.all.take(4).map { it.name }.toMutableSet() else mutableSetOf())
        val env = TemplateEnv(runCatching { LandsMap.load(getApplication()) }.getOrNull())
        SketchTemplates.all.forEachIndexed { i, t ->
            if (sketches.any { it.readyMade && it.name == t.name }) { made += t.name; return@forEachIndexed }
            if (onlyNew && t.name in made) return@forEachIndexed
            val s = createSketch(t.name, t.paper, link = null, open = false, created = (i + 1).toLong())
            placeOnSketch(s, t.items(env), layerId = layer.id, undoable = false)
            made += t.name
            added++
        }
        prefs.edit { putBoolean("readyMadeAdded", true); putStringSet("readyMadeNames", made) }
        if (announce) message = when (added) {
            0 -> "All the ready-made pages are already here."
            1 -> "1 ready-made page put back."
            else -> "$added ready-made pages put back."
        }
    }

    /**
     * Shows a sketch page in a panel beside the Bible text (SPLIT-2, SKT-2): a panel already showing a
     * sketch page is reused, otherwise another panel (one is added if there's only one). The study
     * pane closes to give the page room.
     */
    fun openSketchBeside(s: Sketch) {
        val reading = panels.indexOfFirst { !Sketch.isSketch(it.book) }.takeIf { it >= 0 } ?: 0
        val target = panels.indices.firstOrNull { it != reading && Sketch.isSketch(panels[it].book) && !tab.bibleHidden }
            ?: run {
                // The page takes the tab's other panel (a study view beside the text makes way).
                closeStudies()
                if (panels.size == 1) addPanel()
                if (linkPanels) linkPanels = false
                panels.indices.first { it != reading }
            }
        activePanel = target
        openSketch(s, target)
    }

    fun openSketch(s: Sketch, index: Int = activePanel.coerceIn(0, panels.lastIndex)) {
        goTo(index, s.book, 1)
    }

    fun updateSketch(s: Sketch) {
        val i = sketches.indexOfFirst { it.id == s.id }
        if (i >= 0) sketches[i] = s
        io { user.saveSketch(s) }
    }

    /** Deletes a sketch page and its drawing; panels showing it go back to its passage. */
    fun deleteSketch(s: Sketch) {
        for ((i, p) in panels.withIndex()) {
            if (p.book == s.book) {
                if (s.linked) goTo(i, s.linkBook, s.linkChapter, s.linkVerse, remember = false)
                else biblePlaceBefore(p).let { (b, c) -> goTo(i, b, c, remember = false) }
            }
            p.back.removeAll { it.book == s.book }
            p.forward.removeAll { it.book == s.book }
        }
        sketches.removeAll { it.id == s.id }
        marginStrokesFor(s.book, 1).clear(); imagesFor(s.book, 1).clear(); textsFor(s.book, 1).clear()
        io { user.deleteSketch(s).forEach { File(imagesDir, it).delete() } }
    }

    /**
     * Where to put something new on a sketch page, in its item coordinates (relative to the top of
     * the page's text area): below the title and below anything already there in view.
     */
    fun sketchSpot(p: PanelState, w: Float): Pair<Float, Float> {
        val viewTop = (-p.panY / p.zoom).coerceAtLeast(0f)
        val x = (-p.panX / p.zoom).coerceAtLeast(0f) + 60f
        var y = maxOf(viewTop + 40f, Page.TEXT_TOP + 20f) - Page.TEXT_TOP
        // Boxes already on the page, as (top, bottom) where they overlap this column.
        val taken = textsFor(p.book, 1).filter { it.x < x + w && it.x + it.w > x }
            .map { it.y to it.y + (textHeights[it.id] ?: estimateTextHeight(it)) } +
            imagesFor(p.book, 1).filter { it.x < x + w && it.x + it.w > x }.map { it.y to it.y + it.h }
        var moved = true
        while (moved) {
            moved = false
            for ((top, bottom) in taken) {
                if (y < bottom + 16f && y + 60f > top) { y = bottom + 24f; moved = true }
            }
        }
        return x to y
    }

    /**
     * Puts ready-made drawing on a sketch page as ordinary text boxes and ink, one undoable step
     * (STD-16, SKT-5). [items] are in page units from the top-left of the page's drawing area; they
     * go below anything already there, and the page grows to fit. Returns false if the layer is locked.
     */
    fun placeOnSketch(s: Sketch, items: List<Drawn>, layerId: Long? = null, undoable: Boolean = true): Boolean {
        val layer = (if (layerId != null) layers.firstOrNull { it.id == layerId } else activeLayer()) ?: return false
        if (layerId == null && layer.locked) { message = "Layer \u201c${layer.name}\u201d is locked."; return false }
        if (layerId == null && !layer.visible) setLayerVisible(layer.id, true)
        val book = s.book
        val existing = textsFor(book, 1).map { it.y + (textHeights[it.id] ?: estimateTextHeight(it)) } +
            imagesFor(book, 1).map { it.y + it.h } +
            marginStrokesFor(book, 1).map { st -> (1 until st.points.size step 3).maxOfOrNull { st.points[it] } ?: 0f }
        val top = (existing.maxOrNull()?.let { it + 60f } ?: 40f)
        val added = ArrayList<Annotation>()
        var bottom = top
        for (d in items) when (d) {
            is DrawnBox -> {
                val t = MarginText(newId(), layer.id, book, 1, Region.RIGHT, 1, d.x, top + d.y, d.w, d.text, d.size, d.color, d.background)
                added += t
                bottom = maxOf(bottom, t.y + estimateTextHeight(t))
            }
            is DrawnVerse -> {
                val p = com.biblestudy.app.data.RefLinks.find(d.ref, bible.books).firstOrNull()?.passage ?: continue
                val v = activeVersion
                val verses = passageVerses(p, v)
                if (verses.isEmpty()) continue
                val t = MarginText(newId(), layer.id, book, 1, Region.RIGHT, 1, d.x, top + d.y, d.w, verseCardText(this, p, v, verses), 20f, background = VERSE_CARD_BG)
                added += t
                bottom = maxOf(bottom, t.y + estimateTextHeight(t))
            }
            is DrawnLine -> {
                // Straight runs are filled in every few units so they draw like a pen line.
                val pts = ArrayList<Float>()
                for (i in d.points.indices) {
                    val (x, y) = d.points[i]
                    if (i > 0) {
                        val (px, py) = d.points[i - 1]
                        val n = (kotlin.math.hypot(x - px, y - py) / 12f).toInt()
                        for (k in 1 until n) { pts += px + (x - px) * k / n; pts += top + py + (y - py) * k / n; pts += 0.6f }
                    }
                    pts += x; pts += top + y; pts += 0.6f
                    bottom = maxOf(bottom, top + y)
                }
                added += InkStroke(newId(), layer.id, null, book, 1, Region.RIGHT, 1, false, d.color, d.width, pts.toFloatArray())
            }
        }
        added.forEach { addItem(it) }
        if (undoable) record(Edit(added, emptyList()))
        val needed = Page.TEXT_TOP + bottom + 120f
        sketchOf(book)?.let { if (it.height < needed) updateSketch(it.copy(height = needed)) }
        return true
    }

    /** The family tree being shown (STD-16): a person's TIPNR id. */
    var familyTree by mutableStateOf<String?>(null)

    /** Draws a family tree on the sketch page in view, or on a new one (STD-16). */
    fun copyTreeToSketch(tree: FamilyTree) {
        val p = panels[activePanel.coerceIn(0, panels.lastIndex)]
        val existing = sketchOf(p.book)
        val s = existing ?: createSketch("Family of ${tree.name}", com.biblestudy.app.model.Paper.BLANK)
        if (placeOnSketch(s, tree.drawing(title = existing != null))) message = "Family tree drawn on \u201c${s.name}\u201d."
    }

    /** A text box's height before it has been laid out: wrapped lines at about half an em per letter. */
    private fun estimateTextHeight(t: MarginText): Float {
        val perLine = ((t.w - 16f) / (t.size * 0.5f)).coerceAtLeast(1f)
        val lines = t.text.lines().sumOf { kotlin.math.ceil((it.length.coerceAtLeast(1)) / perLine).toInt() }
        return lines * t.size * 1.35f + 16f
    }

    /**
     * Adds a card to the page: a text box already filled in, e.g. a verse with its reference
     * (which shows as a link) or a person or place (SKT-4).
     */
    fun insertCard(text: String, background: Int) {
        val t = insertTextBox(startEditing = false) ?: return
        val card = t.copy(text = text, background = background)
        replaceItem(card)
        record(Edit(listOf(card), emptyList()))
        cardChanged(text)
    }

    // ---------- reading analytics (ANL-1 to ANL-6) ----------

    /** Whether reading time is counted (ANL-6). */
    var trackReading by mutableStateOf(prefs.getBoolean("trackReading", true))
    /** True while the app is on screen. */
    var foreground = false
    /** Set when something is shown that counts as study rather than reading, e.g. a sketch page. */
    var studyOpen = false
    /** Changes when reading statistics change, so the book picker and stats can refresh. */
    var readingGeneration by mutableIntStateOf(0)
        private set
    private var lastActive = 0L
    private var lastTick = 0L
    private var visitKey = -1
    private var visitSeconds = 0
    private var visitRead = false

    fun startReadingClock(now: Long = android.os.SystemClock.uptimeMillis()) { lastTick = now }

    /** A touch or pen stroke: the reader is here (ANL-1). */
    fun userActive(now: Long = android.os.SystemClock.uptimeMillis()) { lastActive = now }

    /**
     * Counts the time since the last tick for the chapter at the top of the active panel (ANL-1),
     * unless the app is in the background or untouched for two minutes. A chapter counts as read
     * (ANL-2) once a visit to it has lasted a minute and most of it has been in view.
     */
    fun readingTick(now: Long = android.os.SystemClock.uptimeMillis()) {
        val since = (now - lastTick).coerceIn(0L, 30_000L)
        lastTick = now
        if (!foreground || now - lastActive > IDLE_MS || since < 1000) return
        val p = panels.getOrNull(activePanel.coerceIn(0, panels.lastIndex)) ?: return
        if (!Sketch.isSketch(p.book)) noteRecent(p.book, p.chapter, p.topVerse, since / 1000)
        if (!trackReading) return
        val book = p.book; val chapter = p.chapter
        if (Sketch.isSketch(book)) {
            // Time on a sketch page counts as study time for its passage's day, not as reading.
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val seconds = (since / 1000).toInt()
            io { user.addStudy(day, seconds) }
            readingGeneration++
            return
        }
        val key = book * 1000 + chapter
        if (key != visitKey) {
            visitKey = key; visitSeconds = 0; visitRead = false
            io { user.addOpen(book, chapter) }
        }
        val seconds = (since / 1000).toInt()
        visitSeconds += seconds
        val study = sidePane != null || wordStudy != null || studyOpen
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        io { user.addReading(day, book, chapter, seconds, study) }
        if (!visitRead && visitSeconds >= 60) {
            val last = verseCount(book, chapter)
            if (p.seenTo >= (last * 0.8f).toInt()) {
                visitRead = true
                io { user.markRead(book, chapter, System.currentTimeMillis()) }
            }
        }
        readingGeneration++
    }

    private val verseCounts = HashMap<Int, Int>()
    private fun verseCount(book: Int, chapter: Int): Int =
        verseCounts.getOrPut(book * 1000 + chapter) { bible.chapter(book, chapter).size }

    fun clearReadingStats() {
        io { user.clearReading() }
        visitKey = -1
        readingGeneration++
    }

    // ---------- study library (0.8) ----------

    /** Word studies, dictionary, topics and commentary; opened on first use. */
    val study by lazy {
        StudyRepository(getApplication()).also { s ->
            // Imported versions keep their word tags, red letters and paragraphs in their own database.
            s.ownDb = { code -> if (BibleRepository.ALL.any { it.code == code && it.imported }) text(code).database else null }
        }
    }

    /** The word study window, when open (STD-3). */
    var wordStudy by mutableStateOf<WordStudy?>(null)
    /** The word shown in a Word study panel (SPLIT-7): the last one tapped or chosen. */
    var studyWord by mutableStateOf<WordStudy?>(null)

    /** Opens a word study: in the tab's Word study or Verse details panel if it has one, else in its own window. */
    fun openWordStudy(w: WordStudy) {
        when {
            PaneKind.WORDSTUDY in tab.studies -> studyWord = w
            // In the Verse details panel, with a way back to the verse.
            PaneKind.VERSE in tab.studies && verseSheet == null -> verseWordStudy = w
            else -> wordStudy = w
        }
    }
    // ---------- AI chat, online (AI-1 to AI-7) ----------

    val chat by lazy { ChatState(getApplication(), viewModelScope) { com.biblestudy.app.data.RefLinks.find(it, bible.books).isNotEmpty() } }

    /** Opens the AI chat in a panel beside the text. */
    /** The little chat window over the text (AI-10), opened by the chat bubble. */
    var chatWindow by mutableStateOf(false)

    /** Opens the chat: in its little window, unless the AI chat panel is already showing. */
    fun openChat() { if (chat.enabled && PaneKind.CHAT !in tab.studies) chatWindow = true }

    /** Moves the chat from its window into a panel beside the text. */
    fun chatBeside() { chatWindow = false; step { showStudy(PaneKind.CHAT) } }

    /** Adds Bible text to the next question and opens the chat (AI-6). */
    fun sendToChat(label: String, text: String) {
        if (!chat.enabled) return
        chat.attach(ChatPassage(label, text.trim()))
        openChat()
    }

    /** A word study opened from the Verse details panel, shown in it until Back (SPLIT-9). */
    var verseWordStudy by mutableStateOf<WordStudy?>(null)
    /** The dictionary article and topic open in the study pane, if any. */
    var dictionaryOpen by mutableStateOf<Long?>(null)
    var topicOpen by mutableStateOf<Long?>(null)

    fun openDictionary(id: Long) {
        dictionaryOpen = id
        sidePane = PaneKind.DICTIONARY
    }

    /** The person or place open in the study pane (STD-10, STD-11). */
    var nameOpen by mutableStateOf<Long?>(null)

    fun openName(id: Long) {
        nameOpen = id
        sidePane = PaneKind.NAMES
    }

    /** Opens a person or place by its TIPNR id, e.g. a parent or child in a family list. */
    fun openNameUid(uid: String) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { study.nameByUid(uid) }
            if (n != null) openName(n.id) else message = "No entry for ${com.biblestudy.app.data.NameEntry.label(uid)}."
        }
    }

    fun openTopic(id: Long) {
        topicOpen = id
        sidePane = PaneKind.TOPICS
    }

    // ---------- commentaries (STD-17 to STD-19) ----------

    /**
     * The commentary a new commentary panel and the verse pop-up open with: the last one chosen,
     * the AI Commentary to start with (from 1.12 for everyone; the key changed so it's the default again).
     */
    var lastCommentary by mutableStateOf(prefs.getString("commentary2", null) ?: com.biblestudy.app.data.Commentaries.AI)
        private set

    /** The commentary shown by study view [pos] of the tab in front. */
    fun commentaryAt(pos: Int): String = tab.commentaries.getOrNull(pos)?.ifEmpty { null } ?: lastCommentary

    fun setCommentary(pos: Int, id: String) {
        val t = tab
        while (t.commentaries.size <= pos) t.commentaries.add("")
        t.commentaries[pos] = id
        chooseCommentary(id)
    }

    /** Makes [id] the commentary shown in the verse pop-up and in new commentary panels. */
    fun chooseCommentary(id: String) {
        lastCommentary = id
        prefs.edit { putString("commentary2", id) }
    }

    /**
     * Whether the verse details show the commentary rather than the cross-references (STD-20);
     * remembered. The commentary first, to help readers understand the verse (from 1.12).
     */
    var verseCommentary by mutableStateOf(prefs.getBoolean("verseCommentary2", true))
        private set

    fun showVerseCommentary(on: Boolean) {
        verseCommentary = on
        prefs.edit { putBoolean("verseCommentary2", on) }
    }

    /**
     * Commentary [cid]'s note on verse [id] (STD-20): the section covering it, or else the last one
     * before it in the chapter. Null when it has none (or doesn't cover the book).
     */
    suspend fun commentaryOnVerse(cid: String, id: Int): com.biblestudy.app.data.CommentarySection? {
        val sections = loadCommentary(cid, VerseId.book(id), VerseId.chapter(id))
            .filter { VerseId.chapter(it.start) > 0 && VerseId.verse(it.start) > 0 }
        sections.firstOrNull { id in it.start..it.end }?.let { return it }
        // Between notes: the one before runs on to the next (some notes cover more than their heading says).
        val before = sections.lastOrNull { it.start <= id } ?: return null
        val next = sections.firstOrNull { it.start > id }
        val end = if (next != null && VerseId.chapter(next.start) == VerseId.chapter(id)) next.start - 1
            else VerseId.of(VerseId.book(id), VerseId.chapter(id), bible.chapter(VerseId.book(id), VerseId.chapter(id)).lastOrNull()?.verse ?: VerseId.verse(id))
        return before.copy(end = maxOf(before.end, end))
    }

    /** Opens the commentary on the whole chapter beside the text, at the verse tapped. */
    fun openCommentaryBeside() {
        if (PaneKind.COMMENTARY !in tab.studies) showStudy(PaneKind.COMMENTARY)
    }

    /** Whether commentary [pos] scrolls together with the Bible panel (STD-18); on unless switched off. */
    fun commentaryLinked(pos: Int): Boolean = tab.commentaryLinked.getOrNull(pos) ?: true

    fun toggleCommentaryLink(pos: Int) {
        val t = tab
        while (t.commentaryLinked.size <= pos) t.commentaryLinked.add(true)
        t.commentaryLinked[pos] = !t.commentaryLinked[pos]
    }

    /** A commentary's notes on one chapter (unpacking it the first time). */
    suspend fun loadCommentary(id: String, book: Int, chapter: Int): List<com.biblestudy.app.data.CommentarySection> =
        withContext(Dispatchers.IO) {
            if (id == com.biblestudy.app.data.Commentaries.CONCISE) study.commentary(book, chapter)
            else runCatching { com.biblestudy.app.data.Commentaries.chapter(getApplication(), id, book, chapter) }.getOrElse { e ->
                android.util.Log.w("Commentaries", "Couldn't open $id", e)
                withContext(Dispatchers.Main) { message = "Couldn't open ${com.biblestudy.app.data.Commentaries.info(id).short}: ${e.message}" }
                emptyList()
            }
        }

    /** The "book" study writing on commentary [id] is kept under (INK-16): one per commentary. */
    fun commentaryInkBook(id: String): Int = -100 - com.biblestudy.app.data.Commentaries.all.indexOfFirst { it.id == id }.coerceAtLeast(0)

    /** Where a linked commentary was scrolled to by hand: the Bible panel follows it (STD-18). */
    var commentaryPos by mutableStateOf<ScrollPos?>(null)
        private set

    fun followCommentary(book: Int, chapter: Int, verse: Int) {
        commentaryPos = ScrollPos(-1, book, chapter, verse, 0f)
    }

    fun commentaryFollowed(pos: ScrollPos) { if (commentaryPos === pos) commentaryPos = null }

    /** Easton's articles for the names and words of a chapter, in the order they first appear (STD-5). */
    fun chapterArticles(version: String, book: Int, chapter: Int): List<StudyEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<StudyEntry>()
        for (v in text(version).chapter(book, chapter)) {
            for (m in Regex("\\b\\p{Lu}[\\p{L}\u2019']+").findAll(v.text)) {
                val w = m.value.removeSuffix("\u2019s").removeSuffix("'s")
                if (w.length < 3 || !seen.add(w.lowercase()) || w.lowercase() in COMMON_WORDS) continue
                study.dictionaryEntry(w)?.let { out += it }
                if (out.size >= 40) return out
            }
        }
        return out
    }

    /**
     * Parallel accounts of a verse's passage (STD-2): the references listed under its section
     * heading in the BSB (Gospel parallels, Kings and Chronicles, and so on).
     */
    fun parallelAccounts(verseId: Int): List<Passage> {
        val b = VerseId.book(verseId); val c = VerseId.chapter(verseId); val v = VerseId.verse(verseId)
        val bsb = text("BSB")
        // The nearest heading at or before the verse, looking back into the previous chapter if needed.
        val here = bsb.headings(b, c).filter { it.verse <= v }
        val heading = here.lastOrNull { it.refs.isNotBlank() }?.takeIf { h -> here.none { it.verse > h.verse } || here.last().verse == h.verse }
            ?: return emptyList()
        return RefLinks.find(heading.refs, bible.books).map { it.passage }
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
    fun setLayerColor(id: Long, color: Int) = updateLayer(id) { it.copy(color = color) }
    fun setLayerOpacity(id: Long, opacity: Float) = updateLayer(id) { it.copy(opacity = opacity.coerceIn(0.25f, 1f)) }
    fun renameLayer(id: Long, name: String) { if (name.isNotBlank()) updateLayer(id) { it.copy(name = name.trim()) } }
    fun setLayerVisible(id: Long, visible: Boolean) = updateLayer(id) { it.copy(visible = visible) }

    fun setAllLayersVisible(visible: Boolean) {
        layers.map { it.id }.forEach { id -> setLayerVisible(id, visible) }
    }

    /** While exporting one layer (LAY-11): the only layer drawn. */
    var drawOnlyLayer: Long? = null

    /** Saved sets of shown layers, e.g. "Sermon prep" (LAY-10): name → the layers shown. */
    val layerPresets = mutableStateMapOf<String, Set<Long>>().apply {
        runCatching {
            val o = org.json.JSONObject(prefs.getString("layerPresets", "{}")!!)
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                put(k, (0 until a.length()).map { a.getLong(it) }.toSet())
            }
        }
    }

    private fun saveLayerPresets() {
        val o = org.json.JSONObject()
        for ((k, v) in layerPresets) o.put(k, org.json.JSONArray(v.toList()))
        prefs.edit { putString("layerPresets", o.toString()) }
    }

    /** Remembers which layers are shown now under [name]. */
    fun saveLayerPreset(name: String) {
        val n = name.trim().ifEmpty { "View ${layerPresets.size + 1}" }
        layerPresets[n] = layers.filter { it.visible }.mapTo(HashSet()) { it.id }
        saveLayerPresets()
    }

    /** Shows exactly the layers saved under [name] (layers made since then are hidden). */
    fun applyLayerPreset(name: String) {
        val shown = layerPresets[name] ?: return
        layers.map { it.id }.forEach { setLayerVisible(it, it in shown) }
        message = "Showing \u201c$name\u201d."
    }

    fun deleteLayerPreset(name: String) {
        layerPresets.remove(name)
        saveLayerPresets()
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
        marginTexts.values.forEach { l -> l.removeAll { it.layerId == id } }
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

    /** Margin text boxes in a chapter (MRG-12). */
    fun textsFor(book: Int, chapter: Int) = marginTexts.getOrPut(mk(book, chapter)) { mutableStateListOf() }

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
    // Counted outside Compose's snapshots so no update can be lost; the state above mirrors it.
    private val loadsInFlight = java.util.concurrent.atomic.AtomicInteger()
    private fun loadStarted() { pendingLoads = loadsInFlight.incrementAndGet() }
    private fun loadFinished() { pendingLoads = loadsInFlight.decrementAndGet() }

    /**
     * Loads a chapter's annotations. [plainLayout] builds the chapter's layout in a font at normal
     * line spacing without headings; it is only called to convert ink saved before 0.4 to line
     * coordinates, or ink drawn in another font (READ-3).
     */
    fun ensureLoaded(version: String, book: Int, chapter: Int, plainLayout: (TextStyleKey) -> ChapterLayout) {
        val t = tk(version, book, chapter)
        if (loaded.add("t$t")) {
            loadStarted()
            viewModelScope.launch {
                try {
                    val (loadedStrokes, h) = withContext(dbDispatcher) { user.loadText(version, book, chapter) }
                    var s = loadedStrokes
                    val key = styleKey()
                    val now = key.encode()
                    if (s.any { !it.lineAnchored || it.font != now }) {
                        val layouts = HashMap<TextStyleKey, ChapterLayout>()
                        fun plain(k: TextStyleKey) = layouts.getOrPut(k) { plainLayout(k) }
                        s = s.map { st ->
                            var c = st
                            if (!c.lineAnchored) {
                                // Ink from before 0.4: page y at normal spacing in the book font.
                                c = c.copyAs(points = plain(TextStyleKey()).linePoints(c.points), lineAnchored = true, font = TextFont.BOOK.name)
                            }
                            if (c.font != now) {
                                // Drawn in another font or layout: onto the same words here.
                                val from = TextStyleKey.decode(c.font)
                                c = c.copyAs(points = reflowPoints(c.points, plain(from), plain(key)), font = now)
                            }
                            if (c !== st) io { user.insert(c) }
                            c
                        }
                    }
                    merge(textStrokesFor(version, book, chapter), s)
                    merge(highlightsFor(version, book, chapter), h)
                } finally {
                    loadFinished()
                }
            }
        }
        val m = mk(book, chapter)
        if (loaded.add("h$m")) {
            // Highlights made in the other translations, shown here over whole verses (HL-10).
            loadStarted()
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
                    loadFinished()
                }
            }
        }
        if (loaded.add("m$m")) {
            loadStarted()
            viewModelScope.launch {
                try {
                    val (s, i) = withContext(dbDispatcher) { user.loadMargin(book, chapter) }
                    val t = withContext(dbDispatcher) { user.loadTexts(book, chapter) }
                    merge(marginStrokesFor(book, chapter), s)
                    merge(imagesFor(book, chapter), i)
                    merge(textsFor(book, chapter), t)
                } finally {
                    loadFinished()
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

    /** The characters of [verse] in a chapter's text in [version] (its number to just before the next verse). */
    fun verseSpan(version: String, book: Int, chapter: Int, verse: Int): IntRange? {
        val (starts, numbers) = verseStarts(version, book, chapter)
        val i = numbers.indexOf(verse).takeIf { it >= 0 } ?: return null
        val end = if (i + 1 < starts.size) starts[i + 1] else Int.MAX_VALUE
        return starts[i] until end
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
    /** The first and last verse a highlight covers. */
    fun highlightVerses(h: Highlight): Pair<Int, Int> =
        verseOf(h.version, h.book, h.chapter, h.start) to verseOf(h.version, h.book, h.chapter, (h.end - 1).coerceAtLeast(h.start))

    suspend fun highlightEntries(): List<HighlightEntry> = withContext(dbDispatcher) {
        val chapters = HashMap<String, String>()
        user.allHighlights().map { h ->
            val text = chapters.getOrPut(tk(h.version, h.book, h.chapter)) {
                text(h.version).chapter(h.book, h.chapter).joinToString("") { "${it.verse}\u2009${it.text}\n" }
            }
            val words = text.substring(h.start.coerceIn(0, text.length), h.end.coerceIn(0, text.length))
                .replace(Regex("\\n\\d+\u2009"), " ").replace(Regex("^\\d+\u2009"), "").trim()
            // The whole verse (or verses) it's in, with the highlighted words marked.
            val first = verseOf(h.version, h.book, h.chapter, h.start)
            val last = verseOf(h.version, h.book, h.chapter, (h.end - 1).coerceAtLeast(h.start))
            val verses = text(h.version).chapter(h.book, h.chapter).filter { it.verse in first..last }
            val full = if (verses.size == 1) verses.single().text else verses.joinToString(" ") { "${it.verse} ${it.text}" }
            val at = if (verses.size == 1) full.indexOf(words) else -1
            HighlightEntry(h, first, words, last, full.ifEmpty { words }, if (at >= 0 && words.isNotEmpty()) at until at + words.length else null)
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
            is MarginText -> textsFor(after.book, after.chapter).swap(after)
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
            is MarginText -> textsFor(a.book, a.chapter).add(a)
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
            is MarginText -> textsFor(a.book, a.chapter).removeAll { it.id == a.id }
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
        textsFor(sel.book, sel.chapter).filterTo(this) { it.id in sel.ids }
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
                is MarginText -> a.copy(x = a.x + off.x, y = a.y + off.y)
                is Highlight -> null
            }
        }
    }

    /**
     * Resizes the selection by [k] about [pivot] (page units on [g]'s page), INK-11. Ink, images and
     * text boxes grow or shrink; highlights stay on their words.
     */
    fun scaleSelection(k: Float, pivot: Offset, g: PageGeometry) {
        if (k <= 0f || kotlin.math.abs(k - 1f) < 0.01f) return
        val layout = g.layout
        fun sx(ox: Float, x: Float) = (ox + x - pivot.x) * k + pivot.x - ox
        fun sy(oy: Float, y: Float) = (oy + y - pivot.y) * k + pivot.y - oy
        changeSelection { a ->
            when (a) {
                is InkStroke -> {
                    val ox = g.originX(a.region); val oy = g.originY(a.region, a.verse)
                    val shown = if (a.region == Region.TEXT) layout.render(a).points else a.points
                    val pts = FloatArray(shown.size) { i ->
                        when (i % 3) { 0 -> sx(ox, shown[i]); 1 -> sy(oy, shown[i]); else -> shown[i] }
                    }
                    a.copyAs(points = if (a.region == Region.TEXT) layout.linePoints(pts) else pts, width = a.width * k)
                }
                is MarginImage -> {
                    val ox = g.originX(a.region); val oy = g.originY(a.region, a.verse)
                    a.copy(x = sx(ox, a.x), y = sy(oy, a.y), w = a.w * k, h = a.h * k)
                }
                is MarginText -> {
                    val ox = g.originX(a.region); val oy = g.originY(a.region, a.verse)
                    a.copy(x = sx(ox, a.x), y = sy(oy, a.y), w = a.w * k, size = (a.size * k).coerceIn(8f, 96f))
                }
                is Highlight -> null
            }
        }
    }

    /**
     * Turns the selection by [angle] radians (clockwise) about [pivot] (INK-11). Ink turns freely;
     * pictures turn with it when the angle is a whole number of quarter turns, otherwise they and
     * text boxes keep upright and only move round the pivot.
     */
    fun rotateSelection(angle: Float, pivot: Offset, g: PageGeometry) {
        if (kotlin.math.abs(angle) < 0.01f) return
        val layout = g.layout
        val c = kotlin.math.cos(angle); val sn = kotlin.math.sin(angle)
        fun rot(x: Float, y: Float) = Offset(
            (x - pivot.x) * c - (y - pivot.y) * sn + pivot.x,
            (x - pivot.x) * sn + (y - pivot.y) * c + pivot.y,
        )
        val q = kotlin.math.round(angle / (Math.PI.toFloat() / 2f)).toInt()
        val quarter = kotlin.math.abs(angle - q * Math.PI.toFloat() / 2f) < 0.02f
        changeSelection { a ->
            when (a) {
                is InkStroke -> {
                    val ox = g.originX(a.region); val oy = g.originY(a.region, a.verse)
                    val shown = if (a.region == Region.TEXT) layout.render(a).points else a.points
                    val pts = shown.copyOf()
                    for (i in 0 until shown.size / 3) {
                        val r = rot(ox + shown[3 * i], oy + shown[3 * i + 1])
                        pts[3 * i] = r.x - ox; pts[3 * i + 1] = r.y - oy
                    }
                    a.copyAs(points = if (a.region == Region.TEXT) layout.linePoints(pts) else pts)
                }
                is MarginImage -> {
                    val ox = g.originX(a.region); val oy = g.originY(a.region, a.verse)
                    val centre = rot(ox + a.x + a.w / 2f, oy + a.y + a.h / 2f)
                    val turns = if (quarter) ((q % 4) + 4) % 4 else 0
                    val (w, h) = if (turns % 2 == 1) a.h to a.w else a.w to a.h
                    a.copy(x = centre.x - w / 2f - ox, y = centre.y - h / 2f - oy, w = w, h = h, rotation = (a.rotation + turns) % 4)
                }
                is MarginText -> {
                    val ox = g.originX(a.region); val oy = g.originY(a.region, a.verse)
                    val centre = rot(ox + a.x + a.w / 2f, oy + a.y)
                    a.copy(x = centre.x - a.w / 2f - ox, y = centre.y - oy)
                }
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
            is MarginText -> a.copy(color = color)
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
                is MarginText -> a.copy(layerId = layerId)
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
                is MarginText -> a.copy(id = newId(), x = a.x + COPY_SHIFT, y = a.y + COPY_SHIFT)
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

    /** Turns a margin picture a quarter turn clockwise (MRG-8), keeping its top-left corner. */
    fun rotateImage(img: MarginImage) {
        val after = img.copy(rotation = (img.rotation + 1) % 4, w = img.h, h = img.w)
        replaceItem(after)
        record(Edit(listOf(after), listOf(img)))
    }

    /**
     * Keeps part of a margin picture (MRG-8): [l], [t], [r], [b] are fractions of the picture as
     * stored (before turning). The box keeps its width; its height follows the new shape.
     */
    fun cropImage(img: MarginImage, l: Float, t: Float, r: Float, b: Float) {
        val bmp = bitmap(img.file) ?: return
        val cw = (r - l) * bmp.width
        val ch = (b - t) * bmp.height
        if (cw < 4f || ch < 4f) return
        val shownAspect = if (img.rotation % 2 == 0) ch / cw else cw / ch
        val after = img.copy(cropL = l, cropT = t, cropR = r, cropB = b, h = img.w * shownAspect)
        replaceItem(after)
        record(Edit(listOf(after), listOf(img)))
    }

    fun commitImageChange(before: MarginImage, after: MarginImage) {
        if (before == after) return
        io { user.insert(after) }
        record(Edit(listOf(after), listOf(before)))
    }

    // ---------- tags (NOTE-4) and colour meanings (HL-5) ----------

    /** Tags by item key: "n:book:chapter:verse" (typed note), "h:id", "b:id" or "t:id". */
    val tags = mutableStateMapOf<String, Set<String>>().apply { putAll(user.tags()) }
    /** What each highlight colour means, e.g. yellow = "Promises". */
    val meanings = mutableStateMapOf<Int, String>().apply { putAll(user.meanings()) }

    fun noteKey(book: Int, chapter: Int, verse: Int) = "n:$book:$chapter:$verse"

    /** Every tag in use, alphabetically. */
    fun allTags(): List<String> = tags.values.flatten().distinct().sortedBy { it.lowercase() }

    fun setTags(item: String, set: Set<String>) {
        val clean = set.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
        if (clean.isEmpty()) tags.remove(item) else tags[item] = clean
        io { user.setTags(item, clean) }
    }

    fun setMeaning(color: Int, label: String) {
        if (label.isBlank()) meanings.remove(color) else meanings[color] = label.trim()
        io { user.setMeaning(color, label) }
    }

    /** Typed notes and text boxes, for the notes browser (NOTE-5). */
    suspend fun browseNotes(): Pair<List<com.biblestudy.app.data.NoteEntry>, List<MarginText>> =
        withContext(dbDispatcher) { user.allNotes() to user.allTexts() }

    // ---------- margin text boxes (MRG-12) ----------

    /** Heights of text boxes as last drawn, for tapping and dragging them (page units). */
    val textHeights = HashMap<Long, Float>()
    /** Text boxes' laid-out text as last drawn, for finding the reference under a tap. */
    val textLayouts = HashMap<Long, androidx.compose.ui.text.TextLayoutResult>()
    val textLayoutKeys = HashMap<Long, MarginText>()
    val textLayoutStamps = HashMap<Long, Int>()
    /** The verses on each verse card as last laid out (SKT-6), for taps and highlighting. */
    val cardTexts = HashMap<Long, CardText>()
    private val cardSpecs = HashMap<Long, Pair<String, CardSpec?>>()

    /** The card a text box is, if any, worked out once per text. */
    fun cardSpecCached(t: MarginText): CardSpec? {
        val old = cardSpecs[t.id]
        old?.let { (text, spec) -> if (text == t.text) return spec }
        val spec = cardSpec(t)
        if (old != null) cardChanged(t.text) // edited, or its version changed
        cardSpecs[t.id] = t.text to spec
        return spec
    }

    /** Changes when anything a verse card shows could have changed: highlights, settings, layers. */
    fun cardStamp(): Int = editCount * 31 + highlightLoads * 17 + (if (redLetters) 1 else 0) +
        (if (highlightsAllVersions) 2 else 0) + layers.hashCode() * 7 + onlineArrivals * 13

    /** Highlights read from the notes database for verse cards; counts up as they arrive. */
    var highlightLoads by mutableIntStateOf(0)
        private set

    /** Reads every version's highlights for the chapters on a card, once (SKT-6, HL-10). */
    fun ensureCardHighlights(spec: CardSpec) {
        val p = spec.passage
        for (ch in p.chapter..p.endChapter) {
            val m = mk(p.book, ch)
            if (!loaded.add("a$m")) continue
            viewModelScope.launch {
                val all = withContext(dbDispatcher) {
                    user.chapterHighlights(p.book, ch).groupBy { it.version }.onEach { (v, _) -> verseStarts(v, p.book, ch) }
                }
                for ((v, list) in all) {
                    val target = highlightsFor(v, p.book, ch)
                    val have = target.mapTo(HashSet()) { it.id }
                    target.addAll(list.filter { it.id !in have })
                }
                highlightLoads++
            }
        }
    }

    /** The text box being typed in, if any. */
    var editingText by mutableStateOf<Long?>(null)

    /**
     * Adds an empty text box beside the verse at the top of the active panel and starts typing in
     * it. Returns it, or null if the active layer is locked.
     */
    fun insertTextBox(startEditing: Boolean = true): MarginText? {
        val p = panels[activePanel.coerceIn(0, panels.lastIndex)]
        val layer = activeLayer() ?: return null
        if (layer.locked) { message = "Layer \u201c${layer.name}\u201d is locked."; return null }
        if (!layer.visible) setLayerVisible(layer.id, true)
        if (Sketch.isSketch(p.book)) {
            // On a sketch page: in the first free space from the top-left of what's in view.
            val (x, y) = sketchSpot(p, 480f)
            val t = MarginText(newId(), layer.id, p.book, 1, Region.RIGHT, 1, x, y, 480f, "")
            addItem(t)
            if (startEditing) editingText = t.id
            return t
        }
        val region = when {
            marginRight -> Region.RIGHT
            marginLeft -> Region.LEFT
            else -> { marginRight = true; Region.RIGHT }
        }
        val w = marginWidth(region == Region.LEFT) - 48f
        val t = MarginText(newId(), layer.id, p.book, p.chapter, region, p.topVerse, 24f, 8f, w, "")
        addItem(t)
        if (startEditing) editingText = t.id
        return t
    }

    /** Ends typing in a text box: saves its new text (undoable), or removes it if left empty. */
    fun finishTextEdit(before: MarginText, text: String) {
        editingText = null
        val list = textsFor(before.book, before.chapter)
        val current = list.firstOrNull { it.id == before.id } ?: return
        if (text.isBlank()) {
            removeItem(current)
            if (before.text.isNotBlank()) record(Edit(emptyList(), listOf(before)))
            return
        }
        if (text == before.text && current == before) return
        val after = current.copy(text = text)
        replaceItem(after)
        record(Edit(listOf(after), if (before.text.isBlank()) emptyList() else listOf(before)))
    }

    /** Changes a text box's look (size, colour, background), undoable. */
    fun restyleText(before: MarginText, after: MarginText) {
        if (before == after) return
        replaceItem(after)
        record(Edit(listOf(after), listOf(before)))
    }

    fun replaceTextLive(t: MarginText) {
        val list = textsFor(t.book, t.chapter)
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) list[i] = t
    }

    fun commitTextChange(before: MarginText, after: MarginText) {
        if (before == after) return
        io { user.insert(after) }
        record(Edit(listOf(after), listOf(before)))
    }

    fun deleteText(t: MarginText) {
        if (editingText == t.id) editingText = null
        removeItem(t)
        record(Edit(emptyList(), listOf(t)))
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
        val sketch = Sketch.isSketch(p.book)
        val region = when {
            sketch || marginRight -> Region.RIGHT // a sketch page is all "right margin"
            marginLeft -> Region.LEFT
            else -> { marginRight = true; Region.RIGHT }
        }
        val marginW = if (sketch) 620f else marginWidth(region == Region.LEFT)
        val book = p.book; val chapter = p.chapter; val verse = if (sketch) 1 else p.topVerse
        // On a sketch page it goes in free space where you're looking (SKT-3).
        val spot = if (sketch) sketchSpot(p, marginW - 48f) else null
        val x = spot?.first ?: 24f
        val y = spot?.second ?: 8f
        viewModelScope.launch {
            val id = newId()
            val saved = withContext(Dispatchers.IO) { importImage(uri, id) }
            if (saved == null) { message = "Couldn't open that image."; return@launch }
            val (file, aspect) = saved
            val w = marginW - 48f
            val img = MarginImage(id, layer.id, book, chapter, region, verse, x, y, w, w * aspect, file)
            addItem(img)
            record(Edit(listOf(img), emptyList()))
            tool = Tool.SELECT
            message = if (sketch) "Picture added. Use Select to move or resize it." else "Image added beside verse $verse. Use Select to move or resize it."
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
    /** Brings the tab's Bible panel into view if both panels show study views (it takes the second one's place). */
    fun showBible() {
        if (tab.bibleHidden) tab.studies.lastOrNull()?.let { setSlotView(Slot.Study(it), null) }
    }

    fun openPassage(p: Passage, from: Int, beside: Boolean) {
        step {
            passagePop = null
            showBible()
            var target = from.coerceIn(0, panels.lastIndex)
            if (beside) {
                if (panels.size == 1) {
                    // The passage takes the tab's other panel (a study view makes way for it).
                    tab.studies.lastOrNull()?.let { setSlotView(Slot.Study(it), null) } ?: addPanel()
                    target = target.coerceIn(0, panels.lastIndex)
                }
                if (linkPanels) {
                    linkPanels = false
                    message = "Panels unlinked to show the passage beside."
                }
                target = panels.indices.firstOrNull { it != target } ?: target
            }
            activePanel = target
            goTo(target, p.book, p.chapter, p.verse)
        }
    }

    // ---------- book picker markers ----------

    /** Where the user has notes, for the book picker (read from the database, after pending writes). */
    suspend fun loadMarkers(version: String): MarkerIndex =
        withContext(dbDispatcher) { MarkerIndex(user.markerRows(version), user.notedVerses()) }

    /** Visible layers, in drawing order: their items are the ones marked in the book picker. */
    fun visibleLayerIds(): List<Long> = layers.filter { it.visible }.map { it.id }

    // ---------- notes ----------

    /** Saves a typed note on verses [t]..[endVerse] of one chapter (NOTE-1); blank text deletes it. */
    fun setNote(t: VerseTarget, text: String, endVerse: Int = t.verse) {
        val map = notesFor(t.book, t.chapter)
        if (text.isBlank()) map.remove(t.verse) else map[t.verse] = TypedNote(t.verse, maxOf(t.verse, endVerse), text)
        io { user.setNote(t.book, t.chapter, t.verse, text, endVerse) }
    }

    // ---------- bookmarks (NOTE-3) and recently read (READ-8) ----------

    /** Bookmarked verses, newest first (NOTE-3). Back in 1.8, after highlights stood in for them from 0.9. */
    val bookmarks = mutableStateListOf<com.biblestudy.app.model.Bookmark>().apply { addAll(user.bookmarks()) }

    fun bookmarkAt(book: Int, chapter: Int, verse: Int) = bookmarks.firstOrNull { it.book == book && it.chapter == chapter && it.verse == verse }

    /** The bookmarked verses of a chapter, for the ribbons beside them. */
    fun bookmarkedVerses(book: Int, chapter: Int): List<Int> = bookmarks.filter { it.book == book && it.chapter == chapter }.map { it.verse }

    /** Bookmarks a verse, or takes its bookmark off. */
    fun toggleBookmark(book: Int, chapter: Int, verse: Int) {
        val old = bookmarkAt(book, chapter, verse)
        if (old != null) removeBookmark(old)
        else {
            val b = com.biblestudy.app.model.Bookmark(newId(), book, chapter, verse, System.currentTimeMillis(), "")
            bookmarks.add(0, b)
            io { user.addBookmark(b) }
        }
        dataGeneration++
    }

    fun removeBookmark(b: com.biblestudy.app.model.Bookmark) {
        bookmarks.remove(b)
        io { user.deleteBookmark(b.id) }
        dataGeneration++
    }

    /** Chapters read lately, newest first, each with the verse you were at (READ-8). Kept on this tablet. */
    val recent = mutableStateListOf<RecentRead>().apply {
        runCatching { org.json.JSONArray(prefs.getString("recent", "[]")) }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> add(RecentRead(o.getInt("b"), o.getInt("c"), o.getInt("v"), o.getLong("t"))) }
        }
    }
    private var recentKey = -1
    private var recentSeconds = 0L

    /**
     * Adds the chapter in front to the recently read list once it has been read for a few seconds,
     * and keeps its verse up to date while you stay.
     */
    fun noteRecent(book: Int, chapter: Int, verse: Int, seconds: Long) {
        val key = book * 1000 + chapter
        if (key != recentKey) { recentKey = key; recentSeconds = 0 }
        recentSeconds += seconds
        if (recentSeconds < RECENT_AFTER_S) return
        val top = recent.firstOrNull()
        val now = System.currentTimeMillis()
        if (top != null && top.book == book && top.chapter == chapter) {
            if (top.verse == verse && now - top.at < 60_000) return
            recent[0] = top.copy(verse = verse, at = now)
        } else {
            recent.removeAll { it.book == book && it.chapter == chapter }
            recent.add(0, RecentRead(book, chapter, verse, now))
            while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
        }
        saveRecent()
    }

    fun clearRecent() { recent.clear(); recentKey = -1; saveRecent() }

    private fun saveRecent() {
        val a = org.json.JSONArray(recent.map { org.json.JSONObject().put("b", it.book).put("c", it.chapter).put("v", it.verse).put("t", it.at) })
        prefs.edit { putString("recent", a.toString()) }
    }

    // ---------- backup & restore ----------

    /** Writes everything (the notes database and pictures) as one zip. Call on [dbDispatcher]. */
    private fun writeBackup(os: java.io.OutputStream) {
        user.checkpoint()
        val dbFile = getApplication<Application>().getDatabasePath(UserDb.NAME)
        ZipOutputStream(os).use { zip ->
            zip.putNextEntry(ZipEntry("userdata.db"))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            imagesDir.listFiles()?.forEach { f ->
                zip.putNextEntry(ZipEntry("images/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            // Imported Bibles (BIB-4) go too, so a new tablet gets them back.
            val imported = BibleRepository.ALL.filter { it.imported }
            if (imported.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("bibles/imported.json"))
                zip.write(BibleRepository.importedJson().toByteArray())
                zip.closeEntry()
                for (v in imported) {
                    if (v.online > 0) continue // online Bibles are downloaded again, not backed up (BIB-12)
                    val f = File(v.asset)
                    if (!f.exists()) continue
                    zip.putNextEntry(ZipEntry("bibles/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun backup(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(dbDispatcher) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { writeBackup(it) }
                        ?: error("Couldn't write file")
                }.isSuccess
            }
            message = if (ok) "Backup saved." else "Backup failed."
        }
    }

    // ---------- automatic backups (DATA-6) ----------

    var autoBackup by mutableStateOf(runCatching { AutoBackup.valueOf(prefs.getString("autoBackup", "OFF")!!) }.getOrDefault(AutoBackup.OFF))
    /** A folder the user chose for automatic backups (a document-tree URI), or null for app storage. */
    var backupFolder by mutableStateOf(prefs.getString("backupFolder", null))
    var lastAutoBackup by mutableLongStateOf(prefs.getLong("lastAutoBackup", 0L))
        private set

    /** Where backups go when no folder is chosen: Android/data/<app>/files/Backups. */
    private val appBackupDir: File get() = File(getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir, "Backups")

    /** A readable name for where automatic backups go. */
    fun backupFolderName(): String = backupFolder?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':')?.ifEmpty { null } ?: "Chosen folder" }
        ?: "App storage (Android/data)"

    /**
     * Makes an automatic backup if one is due (DATA-6): run when the app goes to the background.
     * Keeps the [KEEP_BACKUPS] newest automatic backups. Returns the job, or null if none was due.
     */
    fun autoBackupIfDue(now: Long = System.currentTimeMillis(), force: Boolean = false): kotlinx.coroutines.Job? {
        val every = autoBackup.days
        if (every == 0 && !force) return null
        if (!force && now - lastAutoBackup < every * 24L * 3600_000L - 3600_000L) return null
        lastAutoBackup = now
        prefs.edit { putLong("lastAutoBackup", now) }
        val name = "bible-study-auto-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss", java.util.Locale.US).format(java.util.Date(now)) + ".zip"
        val folder = backupFolder
        val app = getApplication<Application>()
        return viewModelScope.launch(dbDispatcher) {
            runCatching {
                if (folder != null) {
                    val tree = Uri.parse(folder)
                    val dir = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                    val doc = android.provider.DocumentsContract.createDocument(app.contentResolver, dir, "application/zip", name)
                        ?: error("Couldn't create the backup file")
                    app.contentResolver.openOutputStream(doc)?.use { writeBackup(it) }
                    pruneTree(tree)
                } else {
                    val dir = appBackupDir.apply { mkdirs() }
                    File(dir, name).outputStream().use { writeBackup(it) }
                    dir.listFiles { f -> f.name.startsWith("bible-study-auto-") }?.sortedByDescending { it.name }
                        ?.drop(KEEP_BACKUPS)?.forEach { it.delete() }
                }
            }.onFailure { withContext(Dispatchers.Main) { message = "Automatic backup failed: ${it.message}" } }
        }
    }

    /** The automatic backups kept in app storage, newest first (for Settings and tests). */
    fun appBackups(): List<File> =
        appBackupDir.listFiles { f -> f.name.startsWith("bible-study-auto-") }?.sortedByDescending { it.name } ?: emptyList()

    private fun pruneTree(tree: Uri) {
        val app = getApplication<Application>()
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree),
        )
        val found = ArrayList<Pair<String, String>>()
        app.contentResolver.query(
            children,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) found += c.getString(0) to c.getString(1) }
        found.filter { it.second.startsWith("bible-study-auto-") }.sortedByDescending { it.second }.drop(KEEP_BACKUPS).forEach { (id, _) ->
            runCatching {
                android.provider.DocumentsContract.deleteDocument(app.contentResolver, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
    }

    // ---------- export (DATA-5) ----------

    /** A chapter export waiting for the active panel to draw it. */
    var exportRequest by mutableStateOf<ExportRequest?>(null)

    /** A file name for exporting the active panel's chapter, e.g. "John 3 (BSB)". */
    fun exportName(): String {
        val p = panels[activePanel.coerceIn(0, panels.lastIndex)]
        return "${text(p.version).book(p.book).name} ${p.chapter} (${p.version})"
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
                                    ((name.startsWith("images/") || name.startsWith("bibles/")) && !name.contains("..") && name.count { it == '/' } == 1)
                                if (!e.isDirectory && safe) {
                                    val out = File(tmp, name)
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { zip.copyTo(it) }
                                }
                            }
                        }
                    } ?: error("Couldn't read file")
                    val newDb = File(tmp, "userdata.db")
                    require(newDb.exists()) { "Not an Ink & Word backup" }
                    user.close()
                    val dbFile = app.getDatabasePath(UserDb.NAME)
                    File(dbFile.path + "-wal").delete()
                    File(dbFile.path + "-shm").delete()
                    newDb.copyTo(dbFile, overwrite = true)
                    imagesDir.listFiles()?.forEach { it.delete() }
                    File(tmp, "images").listFiles()?.forEach { it.copyTo(File(imagesDir, it.name), overwrite = true) }
                    File(tmp, "bibles/imported.json").takeIf { it.exists() }?.let {
                        BibleRepository.restoreImported(app, it.readText(), File(tmp, "bibles"), bible.books)
                    }
                    tmp.deleteRecursively()
                    user.layers() to Unit
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
            marginTexts.values.forEach { it.clear() }
            notes.values.forEach { it.clear() }
            loaded.clear(); renders.clear(); bitmaps.clear(); requestedBitmaps.clear()
            selection = null
            undoStack.clear(); redoStack.clear(); editVersion++
            layers.clear(); layers.addAll(result.first)
            if (layers.none { it.id == activeLayerId }) activeLayerId = layers.firstOrNull()?.id ?: 1L
            bookmarks.clear(); bookmarks.addAll(user.bookmarks())
            tags.clear(); tags.putAll(user.tags())
            meanings.clear(); meanings.putAll(user.meanings())
            workspaces.clear(); workspaces.addAll(user.workspaces().mapNotNull { (n, j) -> Workspace.fromJson(n, j) })
            sketches.clear(); sketches.addAll(user.sketches()) // sketch pages (SKT)
            readingGeneration++ // reading stats came with the backup
            dataGeneration++
            message = "Notes restored."
            prepareImported() // versions in an older backup have no word tags yet
        }
    }


    // The ready-made sketch pages are there from the first start (SKT-5). Last in the class, so
    // everything they use is set up.
    init {
        // The first start makes them all; after an update, only pages new in that version.
        runCatching { addReadyMadePages(onlyNew = true) }
        prepareImported()
    }

    companion object {
        /** Reading time pauses after this long without a touch (ANL-1). */
        const val IDLE_MS = 120_000L

        /** Capitalised words that start sentences, not names worth a dictionary article. */
        private val COMMON_WORDS = setOf(
            "and", "the", "then", "but", "for", "now", "when", "who", "what", "this", "that", "these", "they", "there",
            "thou", "thy", "thee", "you", "your", "his", "her", "him", "she", "with", "from", "after", "behold",
            "verily", "therefore", "how", "why", "which", "not", "all", "let", "are", "was", "were", "has", "have",
        )
        private const val COPY_SHIFT = 30f
        private const val MAX_HISTORY = 100
        /** Seconds on a chapter before it counts as read lately (READ-8), and how many are kept. */
        const val RECENT_AFTER_S = 5L
        const val MAX_RECENT = 50
        const val PASSAGE_LIMIT = 80
        /** How many automatic backups are kept (DATA-6). */
        const val KEEP_BACKUPS = 5
    }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(dbDispatcher) { block() }
    }

    /** Waits until every save queued so far has reached the database (for tests). */
    fun awaitSaves() = kotlinx.coroutines.runBlocking(dbDispatcher) {}

    override fun onCleared() {
        sound.release()
        super.onCleared()
        dbDispatcher.close()
        cardKeeper.close()
    }
}
