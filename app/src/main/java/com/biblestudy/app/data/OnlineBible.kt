package com.biblestudy.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.biblestudy.app.model.BookInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * An online Bible (BIB-12) kept on the tablet as it's read. Its database has the same tables as
 * an imported Bible's, so every feature reads it the same way, plus `fetched`: the chapters
 * already downloaded (a chapter the Bible doesn't have is listed with no verses).
 *
 * A chapter is downloaded the first time anything asks for it, with the two after it fetched
 * in the background so reading on never waits. Once saved it's read from the tablet, also
 * offline. *Save for offline* downloads every chapter.
 */
class OnlineBible(val code: String, val id: Int, private val db: SQLiteDatabase) {
    /** Book ids this Bible has. */
    val books: Set<Int> = meta("books")?.split(',')?.mapNotNullTo(HashSet()) { it.toIntOrNull() } ?: (1..66).toSet()

    /**
     * Whether only a few chapters may be kept (the ESV: Crossway allows 500 verses, or half a book,
     * on the tablet). The chapters read least lately are dropped to stay within it; no Save for offline.
     */
    val limited: Boolean get() = id == Esv.ID || id == Nlt.ID

    /** Verses in each book (index = book id), for the half-a-book limit; set by the app. */
    @Volatile var bookVerses: IntArray? = null

    /** Adds what [ImportStudy] gives an imported Bible (word tags, words of Jesus, paragraphs) for new verses. */
    @Volatile var study: ((SQLiteDatabase, BibleImport.Parsed) -> Unit)? = null

    fun isSaved(book: Int, chapter: Int): Boolean =
        db.rawQuery("SELECT 1 FROM fetched WHERE book = ? AND chapter = ?", arrayOf(book.toString(), chapter.toString())).use { it.moveToFirst() }

    /** How many chapters are on the tablet. */
    fun savedChapters(): Int = db.rawQuery("SELECT COUNT(*) FROM fetched", null).use { c -> c.moveToFirst(); c.getInt(0) }

    /**
     * Downloads a chapter unless it's saved already. Returns true when it is on the tablet now;
     * throws if it couldn't be fetched (no internet, no key).
     */
    fun fetch(book: Int, chapter: Int): Boolean {
        if (isSaved(book, chapter)) return true
        val usfm = when {
            book !in books -> null
            id == Esv.ID -> Esv.chapterUsfm(book, chapter)
            id == Nlt.ID -> Nlt.chapterUsfm(book, chapter)
            else -> YouVersion.chapterHtml(id, book, chapter)?.let { YouVersion.toUsfm(it, book, chapter) }
        }
        val parsed = usfm?.let { BibleImport.parseUsfm(listOf(it)) }
        synchronized(LOCK) {
            if (isSaved(book, chapter)) return true
            db.beginTransaction()
            try {
                for ((vid, text) in parsed?.verses?.toSortedMap().orEmpty()) {
                    db.execSQL("INSERT OR REPLACE INTO verses VALUES(?, ?, ?, ?, ?)", arrayOf<Any>(vid, book, chapter, vid % 1000, text))
                    db.execSQL("INSERT INTO verses_fts(docid, text) VALUES(?, ?)", arrayOf<Any>(vid, text))
                }
                if (parsed != null && parsed.verses.isNotEmpty()) study?.invoke(db, parsed)
                if (parsed?.marksRed == true) db.execSQL("INSERT OR REPLACE INTO meta VALUES('marksRed', '1')")
                // "at" orders chapters by when they were last read: a counter that goes up.
                db.execSQL("INSERT OR REPLACE INTO fetched VALUES(?, ?, (SELECT COALESCE(MAX(at), 0) + 1 FROM fetched))", arrayOf<Any>(book, chapter))
                if (limited) trim(book, chapter)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return true
    }

    /** A limited Bible: marks a chapter as just read, so it's kept longest. */
    fun touch(book: Int, chapter: Int) {
        if (!limited) return
        synchronized(LOCK) {
            db.execSQL(
                "UPDATE fetched SET at = (SELECT MAX(at) + 1 FROM fetched) WHERE book = ? AND chapter = ? AND at < (SELECT MAX(at) FROM fetched)",
                arrayOf<Any>(book, chapter),
            )
        }
    }

    /** How many verses are kept on the tablet. */
    fun savedVerses(): Int = db.rawQuery("SELECT COUNT(*) FROM verses", null).use { c -> c.moveToFirst(); c.getInt(0) }

    /**
     * Drops the chapters read least lately until at most [Esv.MAX_VERSES] verses are kept and no
     * book has more than half of its verses kept. The chapter just fetched is always kept (a
     * one-chapter book can't be read otherwise).
     */
    private fun trim(keepBook: Int, keepChapter: Int) {
        val counts = HashMap<Int, Int>() // book → verses kept
        val chapters = ArrayList<Triple<Int, Int, Int>>() // book, chapter, verses; oldest first
        db.rawQuery(
            "SELECT f.book, f.chapter, (SELECT COUNT(*) FROM verses v WHERE v.book = f.book AND v.chapter = f.chapter) FROM fetched f ORDER BY f.at",
            null,
        ).use { c -> while (c.moveToNext()) chapters += Triple(c.getInt(0), c.getInt(1), c.getInt(2)) }
        for ((b, _, n) in chapters) counts.merge(b, n, Int::plus)
        var total = counts.values.sum()
        val sizes = bookVerses
        for ((b, ch, n) in chapters) {
            if (b == keepBook && ch == keepChapter) continue
            val bookTooBig = sizes != null && b < sizes.size && (counts[b] ?: 0) * 2 > sizes[b]
            if (total <= Esv.MAX_VERSES && !bookTooBig) continue
            drop(b, ch)
            total -= n
            counts[b] = (counts[b] ?: 0) - n
        }
    }

    /** Removes a chapter's text, search entries, word tags, red letters and paragraphs. */
    private fun drop(book: Int, chapter: Int) {
        val lo = BibleImport.vid(book, chapter, 0)
        val args = arrayOf<Any>(lo, lo + 999)
        // The search index first: it reads the words to remove from the verses still there.
        db.execSQL("DELETE FROM verses_fts WHERE docid BETWEEN ? AND ?", args)
        for (t in listOf("verses", "tags", "red", "paragraphs")) db.execSQL("DELETE FROM $t WHERE id BETWEEN ? AND ?", args)
        db.execSQL("DELETE FROM fetched WHERE book = ? AND chapter = ?", arrayOf<Any>(book, chapter))
    }

    /**
     * Verses matching [query] from the online service, with their text where it gives it (the ESV
     * does; for YouVersion it's read from the chapter): (verse id, text or null).
     */
    fun searchRemote(query: String, books: List<BookInfo>): List<Pair<Int, String?>> =
        when (id) {
            Esv.ID -> Esv.search(query, books)
            Nlt.ID -> Nlt.search(query)
            else -> YouVersion.searchVerses(id, query).map { it to null }
        }

    /** Whether any chapter so far marked the words of Jesus (if none did, they're found as for imported Bibles). */
    fun marksRed(): Boolean = meta("marksRed") == "1"

    private fun meta(key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    companion object {
        private val LOCK = Any()

        /** Background downloads: a chapter asked for on the main thread, and the chapters after it. */
        val background = Executors.newFixedThreadPool(2) { r -> Thread(r, "online-bible").apply { isDaemon = true } }

        fun fileOf(context: Context, id: Int) = File(File(context.filesDir, "bibles").apply { mkdirs() }, "yv-$id.db")

        /**
         * Makes the (empty) database for online Bible [info], with every book listed with the KJV's
         * chapter count so navigation works the same, and the tables for word tags, words of Jesus
         * and paragraphs ready. Returns its version entry.
         */
        fun create(context: Context, info: YouVersion.Info, kjvBooks: List<BookInfo>): BibleVersion {
            val file = fileOf(context, info.id)
            if (!file.exists()) {
                val tmp = File(file.path + ".tmp").apply { delete() }
                SQLiteDatabase.openOrCreateDatabase(tmp, null).use { db ->
                    db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
                    db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY, name TEXT NOT NULL, osis TEXT NOT NULL, chapters INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE verses(id INTEGER PRIMARY KEY, book INTEGER NOT NULL, chapter INTEGER NOT NULL, verse INTEGER NOT NULL, text TEXT NOT NULL)")
                    db.execSQL("CREATE INDEX verses_bc ON verses(book, chapter)")
                    db.execSQL("CREATE VIRTUAL TABLE verses_fts USING fts4(text, content=\"verses\")")
                    db.execSQL("CREATE TABLE xrefs(from_id INTEGER NOT NULL, to_start INTEGER NOT NULL, to_end INTEGER NOT NULL, votes INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE fetched(book INTEGER NOT NULL, chapter INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(book, chapter))")
                    ImportStudy.createTables(db)
                    for ((k, v) in listOf(
                        "code" to info.code, "name" to info.title, "copyright" to info.copyright, "schema" to "1",
                        "youversion" to info.id.toString(), "books" to info.books.sorted().joinToString(","), "study" to ImportStudy.VERSION,
                    )) db.execSQL("INSERT INTO meta VALUES(?, ?)", arrayOf(k, v))
                    for (b in kjvBooks) db.execSQL("INSERT INTO books VALUES(?, ?, ?, ?)", arrayOf<Any>(b.id, b.name, b.osis, b.chapters))
                }
                if (!tmp.renameTo(file)) throw IOException("Couldn't save ${info.code}")
            }
            return BibleVersion(
                info.code, info.title, file.absolutePath, info.copyright,
                summary = "Online, from YouVersion",
                description = info.about.ifEmpty { "${info.title}, read online from YouVersion and kept on this tablet as you read." },
                imported = true, online = info.id,
            )
        }
    }
}
