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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biblestudy.app.data.BibleRepository
import com.biblestudy.app.data.UserDb
import com.biblestudy.app.model.Annotation
import com.biblestudy.app.model.Bookmark
import com.biblestudy.app.model.Edit
import com.biblestudy.app.model.Highlight
import com.biblestudy.app.model.InkStroke
import com.biblestudy.app.model.Layer
import com.biblestudy.app.model.MarginImage
import com.biblestudy.app.model.Region
import com.biblestudy.app.model.Tool
import com.biblestudy.app.model.VerseId
import com.biblestudy.app.model.VerseTarget
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
    var topVerse = 1
}

class StudyViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("study", Context.MODE_PRIVATE)
    val bible = BibleRepository(app)
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
    var marginLeft by mutableStateOf(prefs.getBoolean("marginLeft", false))
    var marginRight by mutableStateOf(prefs.getBoolean("marginRight", true))
    var theme by mutableStateOf(runCatching { PageTheme.valueOf(prefs.getString("theme", "LIGHT")!!) }.getOrDefault(PageTheme.LIGHT))
    var splitFraction by mutableFloatStateOf(prefs.getFloat("split", 0.5f))

    // ---------- panels ----------
    val panels = mutableStateListOf<PanelState>()
    var activePanel by mutableIntStateOf(0)

    // ---------- layers ----------
    val layers = mutableStateListOf<Layer>()
    var activeLayerId by mutableLongStateOf(prefs.getLong("activeLayer", 1L))

    // ---------- other UI state ----------
    var message by mutableStateOf<String?>(null)
    var verseSheet by mutableStateOf<VerseTarget?>(null)
    var lastSearch by mutableStateOf("")
    /** Bumped after a restore so panels reload their data. */
    var dataGeneration by mutableIntStateOf(0)
    val bookmarks = mutableStateListOf<Bookmark>()

    // ---------- undo ----------
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var editVersion by mutableIntStateOf(0)
    val canUndo: Boolean get() = editVersion >= 0 && undoStack.isNotEmpty()
    val canRedo: Boolean get() = editVersion >= 0 && redoStack.isNotEmpty()

    // ---------- caches ----------
    private val textStrokes = HashMap<String, SnapshotStateList<InkStroke>>()
    private val highlights = HashMap<String, SnapshotStateList<Highlight>>()
    private val marginStrokes = HashMap<String, SnapshotStateList<InkStroke>>()
    private val images = HashMap<String, SnapshotStateList<MarginImage>>()
    private val notes = HashMap<String, SnapshotStateMap<Int, String>>()
    private val loaded = HashSet<String>()
    private val renders = HashMap<Long, StrokeRender>()
    val bitmaps = mutableStateMapOf<String, ImageBitmap>()
    private val requestedBitmaps = HashSet<String>()
    private var lastId = 0L

    init {
        val count = prefs.getInt("panels", 1).coerceIn(1, 2)
        for (i in 0 until count) {
            val b = prefs.getInt("p${i}b", 43).coerceIn(1, 66)
            val c = prefs.getInt("p${i}c", if (b == 43) 3 else 1).coerceIn(1, bible.book(b).chapters)
            panels.add(PanelState(b, c))
        }
        layers.addAll(user.layers())
        if (layers.isEmpty()) {
            val l = Layer(1L, "My Notes", LAYER_COLORS[0], visible = true, locked = false, sort = 0)
            layers.add(l); user.saveLayer(l)
        }
        if (layers.none { it.id == activeLayerId }) activeLayerId = layers.first().id
        bookmarks.addAll(user.bookmarks())
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
            putBoolean("marginLeft", marginLeft); putBoolean("marginRight", marginRight)
            putString("theme", theme.name); putLong("activeLayer", activeLayerId)
            putFloat("split", splitFraction)
            putInt("panels", panels.size)
            panels.forEachIndexed { i, p -> putInt("p${i}b", p.book); putInt("p${i}c", p.chapter) }
        }
    }

    fun currentWidth(highlighter: Boolean) =
        if (highlighter) HIGHLIGHT_SIZES[highlightSize.coerceIn(0, 2)] else PEN_SIZES[penSize.coerceIn(0, 2)]

    // ---------- navigation ----------

    fun goTo(index: Int, book: Int, chapter: Int, verse: Int? = null) {
        val p = panels.getOrNull(index) ?: return
        val b = book.coerceIn(1, 66)
        p.book = b
        p.chapter = chapter.coerceIn(1, bible.book(b).chapters)
        p.pendingVerse = verse
    }

    fun nextChapter(index: Int) {
        val p = panels.getOrNull(index) ?: return
        if (p.chapter < bible.book(p.book).chapters) goTo(index, p.book, p.chapter + 1)
        else if (p.book < 66) goTo(index, p.book + 1, 1)
    }

    fun prevChapter(index: Int) {
        val p = panels.getOrNull(index) ?: return
        if (p.chapter > 1) goTo(index, p.book, p.chapter - 1)
        else if (p.book > 1) goTo(index, p.book - 1, bible.book(p.book - 1).chapters)
    }

    fun toggleSplit() {
        if (panels.size == 1) {
            val p = panels[0]
            panels.add(PanelState(p.book, p.chapter))
            activePanel = 1
        } else {
            closePanel(1)
        }
    }

    fun closePanel(index: Int) {
        if (panels.size > 1 && index in panels.indices) panels.removeAt(index)
        activePanel = 0
    }

    fun openVerse(book: Int, chapter: Int, verse: Int) {
        verseSheet = VerseTarget(book, chapter, verse)
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

    fun notesFor(book: Int, chapter: Int): SnapshotStateMap<Int, String> {
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

    fun ensureLoaded(version: String, book: Int, chapter: Int) {
        val t = tk(version, book, chapter)
        if (loaded.add("t$t")) {
            viewModelScope.launch {
                val (s, h) = withContext(dbDispatcher) { user.loadText(version, book, chapter) }
                merge(textStrokesFor(version, book, chapter), s)
                merge(highlightsFor(version, book, chapter), h)
            }
        }
        val m = mk(book, chapter)
        if (loaded.add("m$m")) {
            viewModelScope.launch {
                val (s, i) = withContext(dbDispatcher) { user.loadMargin(book, chapter) }
                merge(marginStrokesFor(book, chapter), s)
                merge(imagesFor(book, chapter), i)
            }
        }
    }

    private fun <T : Annotation> merge(list: SnapshotStateList<T>, fromDb: List<T>) {
        val ids = fromDb.mapTo(HashSet()) { it.id }
        val extra = list.filter { it.id !in ids }
        list.clear(); list.addAll(fromDb); list.addAll(extra)
    }

    fun render(s: InkStroke): StrokeRender = renders.getOrPut(s.id) { buildRender(s.points, s.width, s.highlighter) }

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
        e.added.forEach { removeItem(it) }
        e.removed.forEach { addItem(it) }
        redoStack.addLast(e)
        editVersion++
    }

    fun redo() {
        val e = redoStack.removeLastOrNull() ?: return
        e.removed.forEach { removeItem(it) }
        e.added.forEach { addItem(it) }
        undoStack.addLast(e)
        editVersion++
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
        val book = p.book; val chapter = p.chapter; val verse = p.topVerse
        viewModelScope.launch {
            val id = newId()
            val saved = withContext(Dispatchers.IO) { importImage(uri, id) }
            if (saved == null) { message = "Couldn't open that image."; return@launch }
            val (file, aspect) = saved
            val w = Page.MARGIN_W - 48f
            val img = MarginImage(id, layer.id, book, chapter, region, verse, 24f, 8f, w, w * aspect, file)
            addItem(img)
            record(Edit(listOf(img), emptyList()))
            tool = Tool.SELECT
            message = "Image added beside verse $verse. Use Select to move or resize it."
        }
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

    // ---------- notes & bookmarks ----------

    fun setNote(t: VerseTarget, text: String) {
        val map = notesFor(t.book, t.chapter)
        if (text.isBlank()) map.remove(t.verse) else map[t.verse] = text
        io { user.setNote(t.book, t.chapter, t.verse, text) }
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
            undoStack.clear(); redoStack.clear(); editVersion++
            layers.clear(); layers.addAll(result.first)
            if (layers.none { it.id == activeLayerId }) activeLayerId = layers.firstOrNull()?.id ?: 1L
            bookmarks.clear(); bookmarks.addAll(result.second)
            dataGeneration++
            message = "Notes restored."
        }
    }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(dbDispatcher) { block() }
    }

    override fun onCleared() {
        super.onCleared()
        dbDispatcher.close()
    }
}
