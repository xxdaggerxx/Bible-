package com.biblestudy.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.biblestudy.app.model.Annotation
import com.biblestudy.app.model.Bookmark
import com.biblestudy.app.model.Highlight
import com.biblestudy.app.model.InkStroke
import com.biblestudy.app.model.Layer
import com.biblestudy.app.model.MarginImage
import com.biblestudy.app.model.Region
import com.biblestudy.app.model.SearchHit
import com.biblestudy.app.model.VerseId
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One annotation's place and layer, for the book picker's markers. */
data class MarkRow(val book: Int, val chapter: Int, val verse: Int, val layerId: Long, val start: Int = -1)

/** All of the user's own data: layers, ink, highlights, images, notes and bookmarks. */
class UserDb(context: Context) : SQLiteOpenHelper(context, NAME, null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE layers(id INTEGER PRIMARY KEY, name TEXT NOT NULL, color INTEGER NOT NULL, " +
                "visible INTEGER NOT NULL, locked INTEGER NOT NULL, sort INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE strokes(id INTEGER PRIMARY KEY, layer_id INTEGER NOT NULL, version TEXT, " +
                "book INTEGER NOT NULL, chapter INTEGER NOT NULL, region INTEGER NOT NULL, verse INTEGER NOT NULL, " +
                "highlighter INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL, points BLOB NOT NULL, " +
                "coords INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX strokes_bc ON strokes(book, chapter)")
        db.execSQL(
            "CREATE TABLE highlights(id INTEGER PRIMARY KEY, layer_id INTEGER NOT NULL, version TEXT NOT NULL, " +
                "book INTEGER NOT NULL, chapter INTEGER NOT NULL, start_off INTEGER NOT NULL, " +
                "end_off INTEGER NOT NULL, color INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX highlights_bc ON highlights(book, chapter)")
        db.execSQL(
            "CREATE TABLE images(id INTEGER PRIMARY KEY, layer_id INTEGER NOT NULL, book INTEGER NOT NULL, " +
                "chapter INTEGER NOT NULL, region INTEGER NOT NULL, verse INTEGER NOT NULL, x REAL NOT NULL, " +
                "y REAL NOT NULL, w REAL NOT NULL, h REAL NOT NULL, file TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX images_bc ON images(book, chapter)")
        db.execSQL(
            "CREATE TABLE notes(book INTEGER NOT NULL, chapter INTEGER NOT NULL, verse INTEGER NOT NULL, " +
                "text TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(book, chapter, verse))"
        )
        db.execSQL(
            "CREATE TABLE bookmarks(id INTEGER PRIMARY KEY, book INTEGER NOT NULL, chapter INTEGER NOT NULL, " +
                "verse INTEGER NOT NULL, created INTEGER NOT NULL)"
        )
        db.execSQL("INSERT INTO layers VALUES(1, 'My Notes', ${DEFAULT_LAYER_COLOR}, 1, 0, 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // 0.4: ink on the words is stored in line coordinates (coords = 1). Older strokes keep
            // page coordinates (coords = 0) until the app converts them on first load.
            db.execSQL("ALTER TABLE strokes ADD COLUMN coords INTEGER NOT NULL DEFAULT 0")
        }
    }

    // ---------- layers ----------

    fun layers(): List<Layer> =
        readableDatabase.rawQuery("SELECT id, name, color, visible, locked, sort FROM layers ORDER BY sort, id", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        Layer(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3) != 0, c.getInt(4) != 0, c.getInt(5))
                    )
                }
            }

    fun saveLayer(l: Layer) {
        val v = ContentValues().apply {
            put("id", l.id); put("name", l.name); put("color", l.color)
            put("visible", if (l.visible) 1 else 0); put("locked", if (l.locked) 1 else 0); put("sort", l.sort)
        }
        writableDatabase.insertWithOnConflict("layers", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Deletes a layer and everything on it. Returns the image files that should be removed. */
    fun deleteLayer(id: Long): List<String> {
        val db = writableDatabase
        val args = arrayOf(id.toString())
        val files = db.rawQuery("SELECT file FROM images WHERE layer_id = ?", args).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        db.beginTransaction()
        try {
            db.delete("strokes", "layer_id = ?", args)
            db.delete("highlights", "layer_id = ?", args)
            db.delete("images", "layer_id = ?", args)
            db.delete("layers", "id = ?", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return files
    }

    // ---------- annotations ----------

    fun insert(a: Annotation) {
        val db = writableDatabase
        when (a) {
            is InkStroke -> db.insertWithOnConflict("strokes", null, ContentValues().apply {
                put("id", a.id); put("layer_id", a.layerId); put("version", a.version)
                put("book", a.book); put("chapter", a.chapter); put("region", a.region.code); put("verse", a.verse)
                put("highlighter", if (a.highlighter) 1 else 0); put("color", a.color); put("width", a.width)
                put("points", a.points.toBlob()); put("coords", if (a.lineAnchored) 1 else 0)
            }, SQLiteDatabase.CONFLICT_REPLACE)

            is Highlight -> db.insertWithOnConflict("highlights", null, ContentValues().apply {
                put("id", a.id); put("layer_id", a.layerId); put("version", a.version)
                put("book", a.book); put("chapter", a.chapter)
                put("start_off", a.start); put("end_off", a.end); put("color", a.color)
            }, SQLiteDatabase.CONFLICT_REPLACE)

            is MarginImage -> db.insertWithOnConflict("images", null, ContentValues().apply {
                put("id", a.id); put("layer_id", a.layerId); put("book", a.book); put("chapter", a.chapter)
                put("region", a.region.code); put("verse", a.verse)
                put("x", a.x); put("y", a.y); put("w", a.w); put("h", a.h); put("file", a.file)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun delete(a: Annotation) {
        val table = when (a) {
            is InkStroke -> "strokes"
            is Highlight -> "highlights"
            is MarginImage -> "images"
        }
        writableDatabase.delete(table, "id = ?", arrayOf(a.id.toString()))
    }

    /** Ink drawn on the words of one version's chapter. */
    fun loadText(version: String, book: Int, chapter: Int): Pair<List<InkStroke>, List<Highlight>> {
        val db = readableDatabase
        val args = arrayOf(version, book.toString(), chapter.toString())
        val strokes = db.rawQuery(
            "SELECT $STROKE_COLS FROM strokes WHERE version = ? AND book = ? AND chapter = ? AND region = 0 ORDER BY id",
            args,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toStroke()) } }
        val highlights = db.rawQuery(
            "SELECT id, layer_id, version, book, chapter, start_off, end_off, color FROM highlights " +
                "WHERE version = ? AND book = ? AND chapter = ? ORDER BY id",
            args,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toHighlight()) } }
        return strokes to highlights
    }

    /** Highlights in every version of a chapter (HL-10 shows them across translations). */
    fun chapterHighlights(book: Int, chapter: Int): List<Highlight> =
        readableDatabase.rawQuery(
            "SELECT id, layer_id, version, book, chapter, start_off, end_off, color FROM highlights " +
                "WHERE book = ? AND chapter = ? ORDER BY id",
            arrayOf(book.toString(), chapter.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toHighlight()) } }

    /** Every highlight, in Bible order (HL-8). */
    fun allHighlights(): List<Highlight> =
        readableDatabase.rawQuery(
            "SELECT id, layer_id, version, book, chapter, start_off, end_off, color FROM highlights " +
                "ORDER BY book, chapter, start_off, id",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toHighlight()) } }

    /** Margin ink and images for a chapter (shared across all versions). */
    fun loadMargin(book: Int, chapter: Int): Pair<List<InkStroke>, List<MarginImage>> {
        val db = readableDatabase
        val args = arrayOf(book.toString(), chapter.toString())
        val strokes = db.rawQuery(
            "SELECT $STROKE_COLS FROM strokes WHERE book = ? AND chapter = ? AND region != 0 ORDER BY id",
            args,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toStroke()) } }
        val images = db.rawQuery(
            "SELECT id, layer_id, book, chapter, region, verse, x, y, w, h, file FROM images " +
                "WHERE book = ? AND chapter = ? ORDER BY id",
            args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    MarginImage(
                        c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), Region.of(c.getInt(4)), c.getInt(5),
                        c.getFloat(6), c.getFloat(7), c.getFloat(8), c.getFloat(9), c.getString(10),
                    )
                )
            }
        }
        return strokes to images
    }

    // ---------- markers for the book picker ----------

    /**
     * Where the user has ink, highlights or images, by layer: margin items (every version) and
     * items on the words of [version]. Highlights have no verse column, so [MarkRow.start] carries
     * their character offset instead (verse = 0).
     */
    fun markerRows(version: String): List<MarkRow> {
        val db = readableDatabase
        fun rows(sql: String, args: Array<String>, highlight: Boolean) = db.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    if (highlight) MarkRow(c.getInt(0), c.getInt(1), 0, c.getLong(3), start = c.getInt(2))
                    else MarkRow(c.getInt(0), c.getInt(1), c.getInt(2), c.getLong(3))
                )
            }
        }
        return rows("SELECT DISTINCT book, chapter, verse, layer_id FROM strokes WHERE region != 0 OR version = ?", arrayOf(version), false) +
            rows("SELECT DISTINCT book, chapter, verse, layer_id FROM images", emptyArray(), false) +
            rows("SELECT book, chapter, start_off, layer_id FROM highlights WHERE version = ?", arrayOf(version), true)
    }

    /** Verse ids that have a typed note. */
    fun notedVerses(): Set<Int> =
        readableDatabase.rawQuery("SELECT book, chapter, verse FROM notes", null).use { c ->
            buildSet { while (c.moveToNext()) add(VerseId.of(c.getInt(0), c.getInt(1), c.getInt(2))) }
        }

    // ---------- notes & bookmarks ----------

    fun notes(book: Int, chapter: Int): Map<Int, String> =
        readableDatabase.rawQuery(
            "SELECT verse, text FROM notes WHERE book = ? AND chapter = ?",
            arrayOf(book.toString(), chapter.toString()),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getInt(0), c.getString(1)) } }

    /** Typed notes containing every word of [query] (SRCH-6), in Bible order. */
    fun searchNotes(query: String, lo: Int, hi: Int): List<SearchHit> {
        val words = query.replace("\"", " ").split(Regex("\\s+")).filter { it.isNotBlank() && it != "OR" }.take(8)
        if (words.isEmpty()) return emptyList()
        val where = words.joinToString(" AND ") { "text LIKE ? ESCAPE '\\'" }
        val args = words.map { "%" + it.trimEnd('*').replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" } +
            listOf(lo.toString(), hi.toString())
        return readableDatabase.rawQuery(
            "SELECT book, chapter, verse, text FROM notes WHERE $where AND book BETWEEN ? AND ? ORDER BY book, chapter, verse LIMIT 500",
            args.toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(SearchHit(c.getInt(0), c.getInt(1), c.getInt(2), c.getString(3))) } }
    }

    fun note(book: Int, chapter: Int, verse: Int): String? =
        readableDatabase.rawQuery(
            "SELECT text FROM notes WHERE book = ? AND chapter = ? AND verse = ?",
            arrayOf(book.toString(), chapter.toString(), verse.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setNote(book: Int, chapter: Int, verse: Int, text: String) {
        val db = writableDatabase
        if (text.isBlank()) {
            db.delete("notes", "book = ? AND chapter = ? AND verse = ?", arrayOf(book.toString(), chapter.toString(), verse.toString()))
        } else {
            db.insertWithOnConflict("notes", null, ContentValues().apply {
                put("book", book); put("chapter", chapter); put("verse", verse)
                put("text", text); put("updated", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun bookmarks(): List<Bookmark> =
        readableDatabase.rawQuery("SELECT id, book, chapter, verse, created FROM bookmarks ORDER BY created DESC", null)
            .use { c ->
                buildList { while (c.moveToNext()) add(Bookmark(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4))) }
            }

    fun addBookmark(b: Bookmark) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("id", b.id); put("book", b.book); put("chapter", b.chapter); put("verse", b.verse); put("created", b.created)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
    }

    /** Flush the write-ahead log into the main file (before backing up). */
    fun checkpoint() {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
    }

    companion object {
        const val NAME = "userdata.db"
        const val DEFAULT_LAYER_COLOR = 0xFF7A5C2E.toInt()
        private const val STROKE_COLS =
            "id, layer_id, version, book, chapter, region, verse, highlighter, color, width, points, coords"

        private fun Cursor.toHighlight() =
            Highlight(getLong(0), getLong(1), getString(2), getInt(3), getInt(4), getInt(5), getInt(6), getInt(7))

        private fun Cursor.toStroke() = InkStroke(
            id = getLong(0), layerId = getLong(1), version = if (isNull(2)) null else getString(2),
            book = getInt(3), chapter = getInt(4), region = Region.of(getInt(5)), verse = getInt(6),
            highlighter = getInt(7) != 0, color = getInt(8), width = getFloat(9), points = getBlob(10).toFloats(),
            lineAnchored = getInt(11) != 0,
        )

        fun FloatArray.toBlob(): ByteArray {
            val bb = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(this)
            return bb.array()
        }

        fun ByteArray.toFloats(): FloatArray {
            val fb = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }
}
