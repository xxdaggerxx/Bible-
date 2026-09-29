package com.biblestudy.app.model

/** Where on the page an annotation lives. */
enum class Region(val code: Int) {
    TEXT(0), LEFT(1), RIGHT(2);

    companion object {
        fun of(code: Int): Region = entries.firstOrNull { it.code == code } ?: TEXT
    }
}

enum class Tool(val label: String) {
    PEN("Pen"), HIGHLIGHTER("Highlighter"), ERASER("Eraser"), SELECT("Select")
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
 * Points are packed as (x, y, pressure) triples in *local* page units:
 *  - Region.TEXT: relative to the top-left of the text column (Study Layout coordinates).
 *    These strokes belong to one Bible version ([version] is set).
 *  - Region.LEFT / RIGHT: relative to (margin left edge, top of [verse]).
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
) : Annotation

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

data class ChapterData(val version: String, val book: Int, val chapter: Int, val verses: List<Verse>)

data class SearchHit(val book: Int, val chapter: Int, val verse: Int, val text: String)

data class CrossRef(val toStart: Int, val toEnd: Int, val votes: Int, val preview: String)

data class Bookmark(val id: Long, val book: Int, val chapter: Int, val verse: Int, val created: Long)

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
