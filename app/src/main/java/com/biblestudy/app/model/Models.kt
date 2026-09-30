package com.biblestudy.app.model

/** Where on the page an annotation lives. */
enum class Region(val code: Int) {
    TEXT(0), LEFT(1), RIGHT(2);

    companion object {
        fun of(code: Int): Region = entries.firstOrNull { it.code == code } ?: TEXT
    }
}

enum class Tool(val label: String) {
    PEN("Pen"), HIGHLIGHTER("Highlighter"), ERASER("Eraser"), LASSO("Lasso"), SELECT("Select")
}

/** What holding the stylus side button does while the pen touches the screen. */
enum class SideButton(val label: String, val tool: Tool?) {
    ERASER("Eraser", Tool.ERASER), LASSO("Lasso", Tool.LASSO), OFF("Nothing", null)
}

/** Anything the user adds to a chapter. Everything belongs to exactly one (global) layer. */
sealed interface Annotation {
    val id: Long
    val layerId: Long
    val book: Int
    val chapter: Int
}

/**
 * A pen or freehand-highlighter stroke.
 *
 * Points are packed as (x, y, pressure) triples:
 *  - Region.TEXT: x in page units from the text column's left edge; y in *line coordinates*
 *    (line index + fraction of the line pitch), so ink stays on its words when headings or line
 *    spacing change. These strokes belong to one Bible version ([version] is set).
 *    Strokes saved before version 0.4 ([lineAnchored] = false) hold y in page units from the
 *    text's top at normal spacing; they are converted when first loaded.
 *    Lines depend on the text font, so each stroke on the words records the [font] it was drawn
 *    in; after a font change it is moved to the same character in the new layout (READ-3).
 *  - Region.LEFT / RIGHT: page units relative to (margin left edge, top of [verse]).
 *    Margin strokes are shared across versions ([version] is null).
 */
class InkStroke(
    override val id: Long,
    override val layerId: Long,
    val version: String?,
    override val book: Int,
    override val chapter: Int,
    val region: Region,
    val verse: Int,
    val highlighter: Boolean,
    val color: Int,
    val width: Float,
    val points: FloatArray,
    val lineAnchored: Boolean = true,
    val font: String = TextFont.BOOK.name,
) : Annotation {
    fun copyAs(
        id: Long = this.id,
        points: FloatArray = this.points,
        color: Int = this.color,
        layerId: Long = this.layerId,
        lineAnchored: Boolean = this.lineAnchored,
        font: String = this.font,
    ) = InkStroke(id, layerId, version, book, chapter, region, verse, highlighter, color, width, points, lineAnchored, font)

    fun withPoints(p: FloatArray) = copyAs(points = p)
    fun withColor(c: Int) = copyAs(color = c)
    fun withLayer(l: Long) = copyAs(layerId = l)
}

/** The typeface for the Bible text (READ-3). */
enum class TextFont(val label: String) { BOOK("Gentium Book"), SERIF("Serif"), SANS("Sans-serif") }

/** A copy of (x, y, pressure) triples shifted by (dx, dy). */
fun FloatArray.translated(dx: Float, dy: Float): FloatArray = FloatArray(size) { i ->
    when (i % 3) {
        0 -> this[i] + dx
        1 -> this[i] + dy
        else -> this[i]
    }
}

/** A clean, snapped highlight covering a character range of one version's chapter text. */
data class Highlight(
    override val id: Long,
    override val layerId: Long,
    val version: String,
    override val book: Int,
    override val chapter: Int,
    val start: Int,
    val end: Int,
    val color: Int,
) : Annotation

/**
 * A highlight made in another translation, shown here over whole verses (HL-10):
 * [source] covers verses [fromVerse]..[toVerse] of its own version.
 */
data class CrossHighlight(val source: Highlight, val fromVerse: Int, val toVerse: Int)

/** A highlight for the Highlights list (HL-8), with the words it covers. */
data class HighlightEntry(val highlight: Highlight, val verse: Int, val words: String)

/** A picture placed in a margin, anchored to a verse and shared across versions. */
data class MarginImage(
    override val id: Long,
    override val layerId: Long,
    override val book: Int,
    override val chapter: Int,
    val region: Region,
    val verse: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val file: String,
) : Annotation

data class Layer(
    val id: Long,
    val name: String,
    val color: Int,
    val visible: Boolean,
    val locked: Boolean,
    val sort: Int,
)

/** One undoable step: things that were added and things that were removed. */
data class Edit(val added: List<Annotation>, val removed: List<Annotation>)

data class BookInfo(val id: Int, val name: String, val osis: String, val chapters: Int)

data class Verse(val verse: Int, val text: String)

/** A section heading shown above [verse] (level 0 = major division, 1 = heading, 2 = subheading). */
data class Heading(val verse: Int, val level: Int, val text: String, val refs: String)

data class ChapterData(
    val version: String,
    val book: Int,
    val chapter: Int,
    val verses: List<Verse>,
    val headings: List<Heading> = emptyList(),
)

data class SearchHit(val book: Int, val chapter: Int, val verse: Int, val text: String)

data class CrossRef(val toStart: Int, val toEnd: Int, val votes: Int, val preview: String)

/** A bookmarked verse; [folder] is "" for bookmarks not in a folder (NOTE-3). */
data class Bookmark(val id: Long, val book: Int, val chapter: Int, val verse: Int, val created: Long, val folder: String = "")

/** A typed note on verses [verse]..[endVerse] of a chapter (NOTE-1); a one-verse note has endVerse == verse. */
data class TypedNote(val verse: Int, val endVerse: Int, val text: String)

data class VerseTarget(val book: Int, val chapter: Int, val verse: Int)

enum class SearchScope(val label: String) {
    ALL("Whole Bible"), OT("Old Testament"), NT("New Testament"), BOOK("This book")
}

/** Verse ids are book * 1_000_000 + chapter * 1_000 + verse (same as the bundled database). */
object VerseId {
    fun of(book: Int, chapter: Int, verse: Int) = book * 1_000_000 + chapter * 1_000 + verse
    fun book(id: Int) = id / 1_000_000
    fun chapter(id: Int) = (id / 1_000) % 1_000
    fun verse(id: Int) = id % 1_000
}
