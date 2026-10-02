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
import com.biblestudy.app.model.MarginText
import com.biblestudy.app.model.Region
import com.biblestudy.app.model.SearchHit
import com.biblestudy.app.model.TypedNote
import com.biblestudy.app.model.VerseId
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A typed note for the notes browser (NOTE-5). */
data class NoteEntry(val book: Int, val chapter: Int, val verse: Int, val endVerse: Int, val text: String, val updated: Long)

/** Reading recorded for one chapter (ANL-2, ANL-4). */
data class ChapterReading(val book: Int, val chapter: Int, val seconds: Int, val opens: Int, val timesRead: Int, val lastRead: Long)

/** One annotation's place and layer, for the book picker's markers. */
data class MarkRow(val book: Int, val chapter: Int, val verse: Int, val layerId: Long, val start: Int = -1)

/** All of the user's own data: layers, ink, highlights, images, notes and bookmarks. */
class UserDb(context: Context) : SQLiteOpenHelper(context, NAME, null, 9) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE layers(id INTEGER PRIMARY KEY, name TEXT NOT NULL, color INTEGER NOT NULL, " +
                "visible INTEGER NOT NULL, locked INTEGER NOT NULL, sort INTEGER NOT NULL, opacity REAL NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "CREATE TABLE strokes(id INTEGER PRIMARY KEY, layer_id INTEGER NOT NULL, version TEXT, " +
                "book INTEGER NOT NULL, chapter INTEGER NOT NULL, region INTEGER NOT NULL, verse INTEGER NOT NULL, " +
                "highlighter INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL, points BLOB NOT NULL, " +
                "coords INTEGER NOT NULL DEFAULT 1, font TEXT NOT NULL DEFAULT 'BOOK')"
        )
        db.execSQL("CREATE INDEX strokes_bc ON strokes(book, chapter)")
        db.execSQL(
            "CREATE TABLE highlights(id INTEGER PRIMARY KEY, layer_id INTEGER NOT NULL, version TEXT NOT NULL, " +
                "book INTEGER NOT NULL, chapter INTEGER NOT NULL, start_off INTEGER NOT NULL, " +
                "end_off INTEGER NOT NULL, color INTEGER NOT NULL, style INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX highlights_bc ON highlights(book, chapter)")
        db.execSQL(
            "CREATE TABLE images(id INTEGER PRIMARY KEY, layer_id INTEGER NOT NULL, book INTEGER NOT NULL, " +
                "chapter INTEGER NOT NULL, region INTEGER NOT NULL, verse INTEGER NOT NULL, x REAL NOT NULL, " +
                "y REAL NOT NULL, w REAL NOT NULL, h REAL NOT NULL, file TEXT NOT NULL, rot INTEGER NOT NULL DEFAULT 0, " +
                "crop_l REAL NOT NULL DEFAULT 0, crop_t REAL NOT NULL DEFAULT 0, crop_r REAL NOT NULL DEFAULT 1, " +
                "crop_b REAL NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX images_bc ON images(book, chapter)")
        db.execSQL(
            "CREATE TABLE notes(book INTEGER NOT NULL, chapter INTEGER NOT NULL, verse INTEGER NOT NULL, " +
                "text TEXT NOT NULL, updated INTEGER NOT NULL, end_verse INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(book, chapter, verse))"
        )
        db.execSQL(
            "CREATE TABLE bookmarks(id INTEGER PRIMARY KEY, book INTEGER NOT NULL, chapter INTEGER NOT NULL, " +
                "verse INTEGER NOT NULL, created INTEGER NOT NULL, folder TEXT NOT NULL DEFAULT '')"
        )
        createTexts(db)
        createReading(db)
        createSketches(db)
        db.execSQL("INSERT INTO layers(id, name, color, visible, locked, sort) VALUES(1, 'My Notes', ${DEFAULT_LAYER_COLOR}, 1, 0, 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // 0.4: ink on the words is stored in line coordinates (coords = 1). Older strokes keep
            // page coordinates (coords = 0) until the app converts them on first load.
            db.execSQL("ALTER TABLE strokes ADD COLUMN coords INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // 0.6: notes can cover a range of verses (NOTE-1; 0 = just the one verse) and
            // bookmarks can be put in folders (NOTE-3).
            db.execSQL("ALTER TABLE notes ADD COLUMN end_verse INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE bookmarks ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            // READ-3: the font ink on the words was drawn in (all earlier ink used Gentium Book).
            db.execSQL("ALTER TABLE strokes ADD COLUMN font TEXT NOT NULL DEFAULT 'BOOK'")
        }
        if (oldVersion < 4) {
            createTexts(db) // 0.7: margin text boxes (MRG-12)
            db.execSQL("ALTER TABLE highlights ADD COLUMN style INTEGER NOT NULL DEFAULT 0") // 0 = fill, 1 = underline (HL-4)
            // Rotated and cropped margin pictures (MRG-8).
            db.execSQL("ALTER TABLE images ADD COLUMN rot INTEGER NOT NULL DEFAULT 0")
            for (c in listOf("crop_l" to 0, "crop_t" to 0, "crop_r" to 1, "crop_b" to 1)) {
                db.execSQL("ALTER TABLE images ADD COLUMN ${c.first} REAL NOT NULL DEFAULT ${c.second}")
            }
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE layers ADD COLUMN opacity REAL NOT NULL DEFAULT 1") // 0.8: faded layers (LAY-8)
        }
        if (oldVersion < 6) createReading(db) // 0.9: reading analytics (ANL-1 to ANL-6)
        if (oldVersion < 7) createSketches(db) // 0.9: sketch pages (SKT-1 to SKT-4)
        if (oldVersion in 4..7) db.execSQL("ALTER TABLE texts ADD COLUMN marks TEXT NOT NULL DEFAULT ''") // 1.1: highlights in text boxes (HL-11)
        if (oldVersion == 7) db.execSQL("ALTER TABLE sketches ADD COLUMN note INTEGER NOT NULL DEFAULT 0") // 1.1: full-screen margin notes (MRG-15)
        db.execSQL("DROP TABLE IF EXISTS ink_text") // 1.1.2: handwriting reading removed (1.1.0 and 1.1.1 kept read text here)
    }

    private fun createSketches(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sketches(id INTEGER PRIMARY KEY, name TEXT NOT NULL, paper TEXT NOT NULL, " +
                "book INTEGER NOT NULL, chapter INTEGER NOT NULL, verse INTEGER NOT NULL, height REAL NOT NULL, created INTEGER NOT NULL, " +
                "note INTEGER NOT NULL DEFAULT 0)"
        )
    }

    private fun createReading(db: SQLiteDatabase) {
        // Seconds spent each day, reading and in the study tools (ANL-1, ANL-3).
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_days(day TEXT PRIMARY KEY, read_s INTEGER NOT NULL, study_s INTEGER NOT NULL)")
        // Per chapter: time spent, times opened and times read through (ANL-2, ANL-4).
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reading_chapters(book INTEGER NOT NULL, chapter INTEGER NOT NULL, " +
                "seconds INTEGER NOT NULL, opens INTEGER NOT NULL, times_read INTEGER NOT NULL, last_read INTEGER NOT NULL, " +
                "PRIMARY KEY(book, chapter))"
        )
    }

    private fun createTexts(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE texts(id INTEGER PRIMARY KEY, layer_id INTEGER NOT NULL, book INTEGER NOT NULL, " +
                "chapter INTEGER NOT NULL, region INTEGER NOT NULL, verse INTEGER NOT NULL, x REAL NOT NULL, " +
                "y REAL NOT NULL, w REAL NOT NULL, body TEXT NOT NULL, size REAL NOT NULL, color INTEGER NOT NULL, " +
                "bg INTEGER NOT NULL, marks TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX texts_bc ON texts(book, chapter)")
        // Tags on notes, highlights, bookmarks and text boxes (NOTE-4); [item] is a key such as
        // "n:43:3:16" (typed note), "h:<id>", "b:<id>" or "t:<id>".
        db.execSQL("CREATE TABLE tags(item TEXT NOT NULL, tag TEXT NOT NULL, PRIMARY KEY(item, tag))")
        // What each highlight colour means (HL-5).
        db.execSQL("CREATE TABLE meanings(color INTEGER PRIMARY KEY, label TEXT NOT NULL)")
        // Saved panel layouts (SPLIT-6), as JSON.
        db.execSQL("CREATE TABLE workspaces(name TEXT PRIMARY KEY, json TEXT NOT NULL, created INTEGER NOT NULL)")
    }

    // ---------- sketch pages (SKT) ----------

    fun sketches(): List<com.biblestudy.app.model.Sketch> =
        readableDatabase.rawQuery("SELECT id, name, paper, book, chapter, verse, height, created, note FROM sketches ORDER BY created", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    com.biblestudy.app.model.Sketch(
                        c.getLong(0), c.getString(1),
                        runCatching { com.biblestudy.app.model.Paper.valueOf(c.getString(2)) }.getOrDefault(com.biblestudy.app.model.Paper.BLANK),
                        c.getInt(3), c.getInt(4), c.getInt(5), c.getFloat(6), c.getLong(7), c.getInt(8) == 1,
                    )
                )
            }
        }

    fun saveSketch(s: com.biblestudy.app.model.Sketch) {
        val v = ContentValues().apply {
            put("id", s.id); put("name", s.name); put("paper", s.paper.name); put("book", s.linkBook)
            put("chapter", s.linkChapter); put("verse", s.linkVerse); put("height", s.height); put("created", s.created); put("note", if (s.note) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("sketches", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Deletes a sketch page and everything on it; returns its pictures' files to remove. */
    fun deleteSketch(s: com.biblestudy.app.model.Sketch): List<String> {
        val db = writableDatabase
        val files = db.rawQuery("SELECT file FROM images WHERE book = ?", arrayOf(s.book.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        for (t in listOf("strokes", "images", "texts")) db.delete(t, "book = ?", arrayOf(s.book.toString()))
        db.delete("sketches", "id = ?", arrayOf(s.id.toString()))
        return files
    }

    // ---------- reading analytics (ANL) ----------

    // Insert-then-update rather than an upsert: Android 10's SQLite (3.22) has no ON CONFLICT DO UPDATE.

    fun addReading(day: String, book: Int, chapter: Int, seconds: Int, study: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO reading_days VALUES(?, 0, 0)", arrayOf(day))
            db.execSQL(
                "UPDATE reading_days SET read_s = read_s + ?, study_s = study_s + ? WHERE day = ?",
                arrayOf(seconds, if (study) seconds else 0, day),
            )
            ensureChapter(db, book, chapter)
            db.execSQL("UPDATE reading_chapters SET seconds = seconds + ? WHERE book = ? AND chapter = ?", arrayOf(seconds, book, chapter))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Time on a sketch page: study time for the day, not tied to a chapter. */
    fun addStudy(day: String, seconds: Int) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO reading_days VALUES(?, 0, 0)", arrayOf(day))
        db.execSQL("UPDATE reading_days SET study_s = study_s + ? WHERE day = ?", arrayOf(seconds, day))
    }

    private fun ensureChapter(db: SQLiteDatabase, book: Int, chapter: Int) =
        db.execSQL("INSERT OR IGNORE INTO reading_chapters VALUES(?, ?, 0, 0, 0, 0)", arrayOf(book, chapter))

    fun addOpen(book: Int, chapter: Int) {
        val db = writableDatabase
        ensureChapter(db, book, chapter)
        db.execSQL("UPDATE reading_chapters SET opens = opens + 1 WHERE book = ? AND chapter = ?", arrayOf(book, chapter))
    }

    fun markRead(book: Int, chapter: Int, at: Long) {
        val db = writableDatabase
        ensureChapter(db, book, chapter)
        db.execSQL(
            "UPDATE reading_chapters SET times_read = times_read + 1, last_read = ? WHERE book = ? AND chapter = ?",
            arrayOf(at, book, chapter),
        )
    }

    /** (day, reading seconds, study seconds), oldest first. */
    fun readingDays(): List<Triple<String, Int, Int>> =
        readableDatabase.rawQuery("SELECT day, read_s, study_s FROM reading_days ORDER BY day", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getInt(1), c.getInt(2))) }
        }

    fun readingChapters(): List<ChapterReading> =
        readableDatabase.rawQuery("SELECT book, chapter, seconds, opens, times_read, last_read FROM reading_chapters", null).use { c ->
            buildList { while (c.moveToNext()) add(ChapterReading(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getLong(5))) }
        }

    fun clearReading() {
        writableDatabase.execSQL("DELETE FROM reading_days")
        writableDatabase.execSQL("DELETE FROM reading_chapters")
    }

    // ---------- layers ----------

    fun layers(): List<Layer> =
        readableDatabase.rawQuery("SELECT id, name, color, visible, locked, sort, opacity FROM layers ORDER BY sort, id", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        Layer(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3) != 0, c.getInt(4) != 0, c.getInt(5), c.getFloat(6))
                    )
                }
            }

    fun saveLayer(l: Layer) {
        val v = ContentValues().apply {
            put("id", l.id); put("name", l.name); put("color", l.color)
            put("visible", if (l.visible) 1 else 0); put("locked", if (l.locked) 1 else 0); put("sort", l.sort)
            put("opacity", l.opacity)
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
            db.delete("texts", "layer_id = ?", args)
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
                put("font", a.font)
            }, SQLiteDatabase.CONFLICT_REPLACE)

            is Highlight -> db.insertWithOnConflict("highlights", null, ContentValues().apply {
                put("id", a.id); put("layer_id", a.layerId); put("version", a.version)
                put("book", a.book); put("chapter", a.chapter)
                put("start_off", a.start); put("end_off", a.end); put("color", a.color)
                put("style", if (a.underline) 1 else 0)
            }, SQLiteDatabase.CONFLICT_REPLACE)

            is MarginImage -> db.insertWithOnConflict("images", null, ContentValues().apply {
                put("id", a.id); put("layer_id", a.layerId); put("book", a.book); put("chapter", a.chapter)
                put("region", a.region.code); put("verse", a.verse)
                put("x", a.x); put("y", a.y); put("w", a.w); put("h", a.h); put("file", a.file)
                put("rot", a.rotation); put("crop_l", a.cropL); put("crop_t", a.cropT); put("crop_r", a.cropR); put("crop_b", a.cropB)
            }, SQLiteDatabase.CONFLICT_REPLACE)

            is MarginText -> db.insertWithOnConflict("texts", null, ContentValues().apply {
                put("id", a.id); put("layer_id", a.layerId); put("book", a.book); put("chapter", a.chapter)
                put("region", a.region.code); put("verse", a.verse)
                put("x", a.x); put("y", a.y); put("w", a.w); put("body", a.text)
                put("size", a.size); put("color", a.color); put("bg", a.background); put("marks", a.marks)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun delete(a: Annotation) {
        val table = when (a) {
            is InkStroke -> "strokes"
            is Highlight -> "highlights"
            is MarginImage -> "images"
            is MarginText -> "texts"
        }
        writableDatabase.delete(table, "id = ?", arrayOf(a.id.toString()))
    }

    // ---------- tags (NOTE-4) and colour meanings (HL-5) ----------

    fun tags(): Map<String, Set<String>> =
        readableDatabase.rawQuery("SELECT item, tag FROM tags", null).use { c ->
            val out = HashMap<String, MutableSet<String>>()
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { sortedSetOf() }.add(c.getString(1))
            out
        }

    fun setTags(item: String, tags: Set<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("tags", "item = ?", arrayOf(item))
            for (t in tags) db.insert("tags", null, ContentValues().apply { put("item", item); put("tag", t) })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun meanings(): Map<Int, String> =
        readableDatabase.rawQuery("SELECT color, label FROM meanings", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getInt(0), c.getString(1)) }
        }

    fun setMeaning(color: Int, label: String) {
        if (label.isBlank()) writableDatabase.delete("meanings", "color = ?", arrayOf(color.toString()))
        else writableDatabase.insertWithOnConflict("meanings", null, ContentValues().apply {
            put("color", color); put("label", label.trim())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun workspaces(): List<Pair<String, String>> =
        readableDatabase.rawQuery("SELECT name, json FROM workspaces ORDER BY created", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    fun saveWorkspace(name: String, json: String) {
        writableDatabase.insertWithOnConflict("workspaces", null, ContentValues().apply {
            put("name", name); put("json", json); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteWorkspace(name: String) {
        writableDatabase.delete("workspaces", "name = ?", arrayOf(name))
    }

    /** Every typed note, for the notes browser (NOTE-5): with when it was last changed. */
    fun allNotes(): List<NoteEntry> =
        readableDatabase.rawQuery("SELECT book, chapter, verse, end_verse, text, updated FROM notes", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val v = c.getInt(2)
                    add(NoteEntry(c.getInt(0), c.getInt(1), v, maxOf(v, c.getInt(3)), c.getString(4), c.getLong(5)))
                }
            }
        }

    /** Every margin text box, for the notes browser. */
    fun allTexts(): List<MarginText> =
        readableDatabase.rawQuery(
            "SELECT id, layer_id, book, chapter, region, verse, x, y, w, body, size, color, bg, marks FROM texts WHERE book < 1000", null, // not sketch pages
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    MarginText(
                        c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), Region.of(c.getInt(4)), c.getInt(5),
                        c.getFloat(6), c.getFloat(7), c.getFloat(8), c.getString(9), c.getFloat(10), c.getInt(11), c.getInt(12),
                        c.getString(13) ?: "",
                    )
                )
            }
        }

    /** Margin text boxes in a chapter (MRG-12). */
    fun loadTexts(book: Int, chapter: Int): List<MarginText> =
        readableDatabase.rawQuery(
            "SELECT id, layer_id, book, chapter, region, verse, x, y, w, body, size, color, bg, marks FROM texts " +
                "WHERE book = ? AND chapter = ? ORDER BY id",
            arrayOf(book.toString(), chapter.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    MarginText(
                        c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), Region.of(c.getInt(4)), c.getInt(5),
                        c.getFloat(6), c.getFloat(7), c.getFloat(8), c.getString(9), c.getFloat(10), c.getInt(11), c.getInt(12),
                        c.getString(13) ?: "",
                    )
                )
            }
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
            "SELECT id, layer_id, version, book, chapter, start_off, end_off, color, style FROM highlights " +
                "WHERE version = ? AND book = ? AND chapter = ? ORDER BY id",
            args,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toHighlight()) } }
        return strokes to highlights
    }

    /** Highlights in every version of a chapter (HL-10 shows them across translations). */
    fun chapterHighlights(book: Int, chapter: Int): List<Highlight> =
        readableDatabase.rawQuery(
            "SELECT id, layer_id, version, book, chapter, start_off, end_off, color, style FROM highlights " +
                "WHERE book = ? AND chapter = ? ORDER BY id",
            arrayOf(book.toString(), chapter.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toHighlight()) } }

    /** Every highlight, in Bible order (HL-8). */
    fun allHighlights(): List<Highlight> =
        readableDatabase.rawQuery(
            "SELECT id, layer_id, version, book, chapter, start_off, end_off, color, style FROM highlights " +
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
            "SELECT id, layer_id, book, chapter, region, verse, x, y, w, h, file, rot, crop_l, crop_t, crop_r, crop_b FROM images " +
                "WHERE book = ? AND chapter = ? ORDER BY id",
            args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    MarginImage(
                        c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), Region.of(c.getInt(4)), c.getInt(5),
                        c.getFloat(6), c.getFloat(7), c.getFloat(8), c.getFloat(9), c.getString(10),
                        c.getInt(11), c.getFloat(12), c.getFloat(13), c.getFloat(14), c.getFloat(15),
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
        // Sketch pages (books from 1000) aren't Bible chapters.
        return rows("SELECT DISTINCT book, chapter, verse, layer_id FROM strokes WHERE (region != 0 OR version = ?) AND book BETWEEN 1 AND 999" /* not sketch pages or study articles */, arrayOf(version), false) +
            rows("SELECT DISTINCT book, chapter, verse, layer_id FROM images WHERE book < 1000", emptyArray(), false) +
            rows("SELECT DISTINCT book, chapter, verse, layer_id FROM texts WHERE book < 1000", emptyArray(), false) +
            rows("SELECT book, chapter, start_off, layer_id FROM highlights WHERE version = ?", arrayOf(version), true)
    }

    /** Verse ids that have a typed note. */
    fun notedVerses(): Set<Int> =
        readableDatabase.rawQuery("SELECT book, chapter, verse, end_verse FROM notes", null).use { c ->
            buildSet {
                while (c.moveToNext()) {
                    val v = c.getInt(2)
                    for (x in v..maxOf(v, c.getInt(3))) add(VerseId.of(c.getInt(0), c.getInt(1), x))
                }
            }
        }

    // ---------- notes & bookmarks ----------

    fun notes(book: Int, chapter: Int): Map<Int, TypedNote> =
        readableDatabase.rawQuery(
            "SELECT verse, end_verse, text FROM notes WHERE book = ? AND chapter = ?",
            arrayOf(book.toString(), chapter.toString()),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getInt(0), c.toNote()) } }

    /** The note on [verse], or on a range of verses that includes it (NOTE-1). */
    fun noteCovering(book: Int, chapter: Int, verse: Int): TypedNote? =
        readableDatabase.rawQuery(
            "SELECT verse, end_verse, text FROM notes WHERE book = ? AND chapter = ? AND verse <= ? " +
                "AND MAX(verse, end_verse) >= CAST(? AS INTEGER) ORDER BY verse DESC LIMIT 1",
            arrayOf(book.toString(), chapter.toString(), verse.toString(), verse.toString()),
        ).use { c -> if (c.moveToFirst()) c.toNote() else null }

    /** Typed notes containing every word of [query] (SRCH-6), in Bible order. */
    fun searchNotes(query: String, lo: Int, hi: Int): List<SearchHit> {
        val words = query.replace("\"", " ").split(Regex("\\s+")).filter { it.isNotBlank() && it != "OR" }.take(8)
        if (words.isEmpty()) return emptyList()
        val where = words.joinToString(" AND ") { "text LIKE ? ESCAPE '\\'" }
        val args = words.map { "%" + it.trimEnd('*').replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" } +
            listOf(lo.toString(), hi.toString())
        // Typed notes on verses and text boxes in the margins (MRG-12).
        val textWhere = where.replace("text LIKE", "body LIKE")
        return readableDatabase.rawQuery(
            "SELECT book, chapter, verse, text FROM notes WHERE $where AND book BETWEEN ? AND ? " +
                "UNION ALL SELECT book, chapter, verse, body FROM texts WHERE $textWhere AND book BETWEEN ? AND ? " +
                "ORDER BY 1, 2, 3 LIMIT 500",
            (args + args).toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(SearchHit(c.getInt(0), c.getInt(1), c.getInt(2), c.getString(3))) } }
    }

    fun note(book: Int, chapter: Int, verse: Int): String? =
        readableDatabase.rawQuery(
            "SELECT text FROM notes WHERE book = ? AND chapter = ? AND verse = ?",
            arrayOf(book.toString(), chapter.toString(), verse.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setNote(book: Int, chapter: Int, verse: Int, text: String, endVerse: Int = verse) {
        val db = writableDatabase
        if (text.isBlank()) {
            db.delete("notes", "book = ? AND chapter = ? AND verse = ?", arrayOf(book.toString(), chapter.toString(), verse.toString()))
        } else {
            db.insertWithOnConflict("notes", null, ContentValues().apply {
                put("book", book); put("chapter", chapter); put("verse", verse)
                put("text", text); put("updated", System.currentTimeMillis())
                put("end_verse", if (endVerse > verse) endVerse else 0)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun bookmarks(): List<Bookmark> =
        readableDatabase.rawQuery("SELECT id, book, chapter, verse, created, folder FROM bookmarks ORDER BY created DESC", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(Bookmark(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getString(5)))
                }
            }

    fun addBookmark(b: Bookmark) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("id", b.id); put("book", b.book); put("chapter", b.chapter); put("verse", b.verse); put("created", b.created); put("folder", b.folder)
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
            "id, layer_id, version, book, chapter, region, verse, highlighter, color, width, points, coords, font"

        private fun Cursor.toNote(): TypedNote {
            val v = getInt(0)
            return TypedNote(v, maxOf(v, getInt(1)), getString(2))
        }

        private fun Cursor.toHighlight() =
            Highlight(getLong(0), getLong(1), getString(2), getInt(3), getInt(4), getInt(5), getInt(6), getInt(7), getInt(8) == 1)

        private fun Cursor.toStroke() = InkStroke(
            id = getLong(0), layerId = getLong(1), version = if (isNull(2)) null else getString(2),
            book = getInt(3), chapter = getInt(4), region = Region.of(getInt(5)), verse = getInt(6),
            highlighter = getInt(7) != 0, color = getInt(8), width = getFloat(9), points = getBlob(10).toFloats(),
            lineAnchored = getInt(11) != 0, font = getString(12),
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
