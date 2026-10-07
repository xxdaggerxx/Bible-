package com.biblestudy.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.biblestudy.app.model.BookInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * An online Bible (BIB-12) kept on the tablet as it's read: YouVersion's, the ESV (Crossway) and
 * the NLT (Tyndale), all kept the same way. Its database has the same tables as an imported
 * Bible's, so every feature reads it the same way, plus `fetched`: the chapters already
 * downloaded and when each was last read (a chapter the Bible doesn't have is listed with no
 * verses), and `searches`: searches already asked.
 *
 * A chapter is downloaded the first time anything asks for it, with the two after it fetched
 * in the background so reading on never waits. Once saved it's read from the tablet, also
 * offline, for as long as the app's *Keep downloaded chapters* says ([expire]). *Save for
 * offline* downloads every chapter, paced to the service's limits ([pace]).
 */
class OnlineBible(val code: String, val id: Int, private val db: SQLiteDatabase) {
    /** Book ids this Bible has. */
    val books: Set<Int> = meta("books")?.split(',')?.mapNotNullTo(HashSet()) { it.toIntOrNull() } ?: (1..66).toSet()

    /** The least time between downloads when saving the whole Bible, in ms: the service's rate limits. */
    val pace: Long get() = when (id) {
        Esv.ID -> 3_700L // Crossway: 60 requests a minute and 1,000 an hour
        Nlt.ID -> 500L
        else -> 0L
    }

    init {
        // Searches already asked (1.19).
        db.execSQL("CREATE TABLE IF NOT EXISTS searches(q TEXT PRIMARY KEY, at INTEGER NOT NULL, ids TEXT NOT NULL)")
        // When each chapter was last read, for how long chapters are kept (1.19). Chapters saved
        // earlier start their clock now.
        val hasReadAt = db.rawQuery("PRAGMA table_info(fetched)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == "read_at") found = true
            found
        }
        if (!hasReadAt) {
            db.execSQL("ALTER TABLE fetched ADD COLUMN read_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE fetched SET read_at = ?", arrayOf<Any>(System.currentTimeMillis()))
        }
        // Until 1.20 the ESV and NLT kept only 500 verses, with verse cards' verses kept apart
        // (`kept`): those become ordinary text, and verses outside the chapters read go, so every
        // chapter is whole or not there.
        val hadKept = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'kept'", null).use { it.moveToFirst() }
        if (hadKept) synchronized(LOCK) {
            db.execSQL("DROP TABLE kept")
            db.rawQuery(
                "SELECT DISTINCT v.book, v.chapter FROM verses v WHERE NOT EXISTS " +
                    "(SELECT 1 FROM fetched f WHERE f.book = v.book AND f.chapter = v.chapter)",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0) to c.getInt(1)) } }.forEach { (b, ch) -> drop(b, ch) }
        }
    }

    /** Chapters marked as read this session, so reading doesn't write to the database every time. */
    private val readNow = java.util.Collections.synchronizedSet(HashSet<Int>())

    /**
     * Whether the whole Bible was saved for offline on purpose: then nothing of it is let go when
     * chapters haven't been read for a while ([expire]).
     */
    var savedForOffline: Boolean
        get() = meta("offline") == "1"
        set(v) { synchronized(LOCK) { db.execSQL("INSERT OR REPLACE INTO meta VALUES('offline', ?)", arrayOf<Any>(if (v) "1" else "0")) } }

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
                val now = System.currentTimeMillis()
                db.execSQL("INSERT OR REPLACE INTO fetched(book, chapter, at, read_at) VALUES(?, ?, ?, ?)", arrayOf<Any>(book, chapter, now, now))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            readNow += book * 1000 + chapter
        }
        return true
    }

    /** Marks a chapter as just read: its clock for [expire] starts again (once a session). */
    fun touch(book: Int, chapter: Int) {
        if (!readNow.add(book * 1000 + chapter)) return
        synchronized(LOCK) {
            db.execSQL("UPDATE fetched SET read_at = ? WHERE book = ? AND chapter = ?", arrayOf<Any>(System.currentTimeMillis(), book, chapter))
        }
    }

    /**
     * Lets go of the chapters not read for [days] days (they download again when read), and
     * searches older than that. Not a Bible saved for offline. 0 = keep
     * them always. Returns how many chapters were let go.
     */
    fun expire(days: Int, now: Long = System.currentTimeMillis()): Int {
        if (days <= 0) return 0
        val before = now - days * 86_400_000L
        synchronized(LOCK) {
            db.execSQL("DELETE FROM searches WHERE at < ?", arrayOf<Any>(before))
            if (savedForOffline || complete()) return 0
            val old = db.rawQuery("SELECT book, chapter FROM fetched WHERE read_at < ?", arrayOf(before.toString()))
                .use { c -> buildList { while (c.moveToNext()) add(c.getInt(0) to c.getInt(1)) } }
            if (old.isEmpty()) return 0
            db.beginTransaction()
            try {
                for ((b, c) in old) drop(b, c)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            for ((b, c) in old) readNow -= b * 1000 + c
            return old.size
        }
    }

    /** Whether every chapter is on the tablet (a Bible saved for offline before 1.19 isn't marked so). */
    private fun complete(): Boolean {
        val all = db.rawQuery("SELECT id, chapters FROM books", null).use { c ->
            var n = 0
            while (c.moveToNext()) if (c.getInt(0) in books) n += c.getInt(1)
            n
        }
        return all > 0 && savedChapters() >= all
    }

    /** Removes everything downloaded: every chapter and search. */
    fun clear() {
        synchronized(LOCK) {
            db.beginTransaction()
            try {
                for (t in listOf("verses", "tags", "red", "paragraphs", "fetched", "searches")) db.execSQL("DELETE FROM $t")
                db.execSQL("INSERT INTO verses_fts(verses_fts) VALUES('rebuild')") // the search index, now empty
                db.execSQL("INSERT OR REPLACE INTO meta VALUES('offline', '0')")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            readNow.clear()
            runCatching { db.execSQL("VACUUM") } // give the space back
        }
    }

    /** How many verses are on the tablet. */
    fun savedVerses(): Int = db.rawQuery("SELECT COUNT(*) FROM verses", null).use { c -> c.moveToFirst(); c.getInt(0) }

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
     * and NLT do; for YouVersion it's read from the chapter): (verse id, text or null).
     */
    fun searchRemote(query: String, books: List<BookInfo>): List<Pair<Int, String?>> {
        fun ask(): List<Pair<Int, String?>> = when (id) {
            Esv.ID -> Esv.search(query, books)
            Nlt.ID -> Nlt.search(query)
            else -> YouVersion.searchVerses(id, query).map { it to null }
        }
        // Kept, so the same search doesn't ask again: "id" or "id<tab>text" a line.
        val q = query.trim().lowercase()
        db.rawQuery("SELECT ids FROM searches WHERE q = ?", arrayOf(q)).use { c -> if (c.moveToFirst()) c.getString(0) else null }?.let { saved ->
            return saved.lines().filter { it.isNotEmpty() }.flatMap { l ->
                if ('\t' in l) listOfNotNull(l.substringBefore('\t').toIntOrNull()?.let { it to l.substringAfter('\t') })
                else l.split(',').mapNotNull { it.trim().toIntOrNull()?.let { v -> v to null } } // 1.19 kept ids with commas
            }
        }
        val found = ask()
        val text = found.joinToString("\n") { (v, t) -> if (t == null) "$v" else "$v\t" + t.replace('\t', ' ').replace('\n', ' ') }
        synchronized(LOCK) { db.execSQL("INSERT OR REPLACE INTO searches VALUES(?, ?, ?)", arrayOf<Any>(q, System.currentTimeMillis(), text)) }
        return found
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
                    db.execSQL("CREATE TABLE fetched(book INTEGER NOT NULL, chapter INTEGER NOT NULL, at INTEGER NOT NULL, read_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(book, chapter))")
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
