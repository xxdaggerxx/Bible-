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
 *
 * A limited Bible (ESV, NLT) keeps at most 500 verses: the chapters read last, plus the verses of
 * verse cards ([keep]), which stay while chapters come and go so cards always work.
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

    init {
        // Verse cards' verses (added in 1.18.2; older databases get the table now).
        db.execSQL("CREATE TABLE IF NOT EXISTS kept(id INTEGER PRIMARY KEY)")
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
            store(book, chapter, parsed, whole = true)
        }
        return true
    }

    /**
     * Saves downloaded verses, in one transaction: a [whole] chapter (listed in `fetched`), or a
     * verse card's verses. Verses already there (a card's) are replaced.
     */
    private fun store(book: Int, chapter: Int, parsed: BibleImport.Parsed?, whole: Boolean) {
        db.beginTransaction()
        try {
            val lo = BibleImport.vid(book, chapter, 0)
            // A whole chapter sets its own paragraph starts (a card's verses may have marked one wrongly).
            if (whole) db.execSQL("DELETE FROM paragraphs WHERE id BETWEEN ? AND ?", arrayOf<Any>(lo, lo + 999))
            for ((vid, text) in parsed?.verses?.toSortedMap().orEmpty()) {
                val had = db.rawQuery("SELECT 1 FROM verses WHERE id = ?", arrayOf(vid.toString())).use { it.moveToFirst() }
                if (had) db.execSQL("DELETE FROM verses_fts WHERE docid = ?", arrayOf<Any>(vid)) // reads the old words, so first
                db.execSQL("INSERT OR REPLACE INTO verses VALUES(?, ?, ?, ?, ?)", arrayOf<Any>(vid, book, chapter, vid % 1000, text))
                db.execSQL("INSERT INTO verses_fts(docid, text) VALUES(?, ?)", arrayOf<Any>(vid, text))
            }
            if (parsed != null && parsed.verses.isNotEmpty()) study?.invoke(db, parsed)
            if (parsed?.marksRed == true) db.execSQL("INSERT OR REPLACE INTO meta VALUES('marksRed', '1')")
            if (whole) {
                // "at" orders chapters by when they were last read: a counter that goes up.
                db.execSQL(
                    "INSERT OR REPLACE INTO fetched(book, chapter, at, read_at) VALUES(?, ?, (SELECT COALESCE(MAX(at), 0) + 1 FROM fetched), ?)",
                    arrayOf<Any>(book, chapter, System.currentTimeMillis()),
                )
                readNow += book * 1000 + chapter
            }
            if (limited) trim(if (whole) book else -1, chapter)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Verse cards' verses to keep on the tablet (SKT-6), most wanted first. A limited Bible keeps
     * them while the chapters around them come and go, within the limits: at most [MAX_KEPT]
     * verses and less than half of any book, so there's always room to read. Others are dropped
     * from the list. Returns the verses kept.
     */
    fun keep(ids: List<Int>): Set<Int> {
        if (!limited) return emptySet()
        val sizes = bookVerses
        val perBook = HashMap<Int, Int>()
        val out = LinkedHashSet<Int>()
        for (id in ids) {
            if (out.size >= MAX_KEPT || id in out) continue
            val b = id / 1_000_000
            val n = perBook[b] ?: 0
            if (sizes != null && b < sizes.size && (n + 1) * 2 > sizes[b]) continue
            out += id
            perBook[b] = n + 1
        }
        synchronized(LOCK) {
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM kept")
                for (id in out) db.execSQL("INSERT INTO kept VALUES(?)", arrayOf<Any>(id))
                trim(-1, -1)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return out
    }

    /** Verses to keep that aren't on the tablet. */
    fun missingKept(): List<Int> =
        db.rawQuery("SELECT id FROM kept WHERE id NOT IN (SELECT id FROM verses) ORDER BY id", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }

    /**
     * Downloads just these verses (a limited Bible's verse cards), a run of verses per request.
     * Throws if they couldn't be fetched.
     */
    fun fetchVerses(ids: List<Int>) {
        if (!limited) return
        val runs = ArrayList<IntArray>() // book, chapter, from, to
        for (id in ids.sorted()) {
            val b = id / 1_000_000; val c = (id / 1000) % 1000; val v = id % 1000
            val last = runs.lastOrNull()
            if (last != null && last[0] == b && last[1] == c && last[3] == v - 1) last[3] = v else runs += intArrayOf(b, c, v, v)
        }
        for ((b, c, from, to) in runs) {
            if (b !in books) continue
            val usfm = when (id) {
                Esv.ID -> Esv.versesUsfm(b, c, from, to)
                Nlt.ID -> Nlt.versesUsfm(b, c, from, to)
                else -> null
            } ?: continue
            val parsed = BibleImport.parseUsfm(listOf(usfm))
            val lo = BibleImport.vid(b, c, from); val hi = BibleImport.vid(b, c, to)
            val r = lo..hi
            val wanted = BibleImport.Parsed(
                parsed.verses.filterKeys { it in r }, parsed.bookNames, parsed.title, parsed.red.filterKeys { it in r },
                parsed.marksRed, parsed.paragraphs.filterTo(HashSet()) { it in r }, parsed.bridges.filterKeys { it in r },
            )
            synchronized(LOCK) { store(b, c, wanted, whole = false) }
        }
    }

    /**
     * Marks a chapter as just read: its clock for [expire] starts again (once a session), and a
     * limited Bible keeps it longest.
     */
    fun touch(book: Int, chapter: Int) {
        val first = readNow.add(book * 1000 + chapter)
        if (!first && !limited) return
        synchronized(LOCK) {
            if (first) db.execSQL("UPDATE fetched SET read_at = ? WHERE book = ? AND chapter = ?", arrayOf<Any>(System.currentTimeMillis(), book, chapter))
            if (limited) db.execSQL(
                "UPDATE fetched SET at = (SELECT MAX(at) + 1 FROM fetched) WHERE book = ? AND chapter = ? AND at < (SELECT MAX(at) FROM fetched)",
                arrayOf<Any>(book, chapter),
            )
        }
    }

    /**
     * Lets go of the chapters not read for [days] days (they download again when read), and
     * searches older than that. Not a Bible saved for offline, nor verse cards' verses. 0 = keep
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

    /** Removes everything downloaded: every chapter, verse cards' verses and searches. */
    fun clear() {
        synchronized(LOCK) {
            db.beginTransaction()
            try {
                for (t in listOf("verses", "tags", "red", "paragraphs", "fetched", "kept", "searches")) db.execSQL("DELETE FROM $t")
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

    /** How many verses are kept on the tablet. */
    fun savedVerses(): Int = db.rawQuery("SELECT COUNT(*) FROM verses", null).use { c -> c.moveToFirst(); c.getInt(0) }

    /**
     * Drops the chapters read least lately until at most [Esv.MAX_VERSES] verses are kept and no
     * book has more than half of its verses kept. Verse cards' verses ([keep]) stay, and count.
     * The chapter just fetched is always kept (a one-chapter book can't be read otherwise).
     */
    private fun trim(keepBook: Int, keepChapter: Int) {
        // Verses no longer kept for a card, outside the chapters read: gone first.
        db.rawQuery(
            "SELECT DISTINCT v.book, v.chapter FROM verses v WHERE v.id NOT IN (SELECT id FROM kept) " +
                "AND NOT EXISTS (SELECT 1 FROM fetched f WHERE f.book = v.book AND f.chapter = v.chapter)",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0) to c.getInt(1)) } }.forEach { (b, ch) -> drop(b, ch) }
        val counts = HashMap<Int, Int>() // book → verses kept
        db.rawQuery("SELECT book, COUNT(*) FROM verses GROUP BY book", null).use { c -> while (c.moveToNext()) counts[c.getInt(0)] = c.getInt(1) }
        val chapters = ArrayList<Triple<Int, Int, Int>>() // book, chapter, verses that would go; oldest first
        db.rawQuery(
            "SELECT f.book, f.chapter, (SELECT COUNT(*) FROM verses v WHERE v.book = f.book AND v.chapter = f.chapter " +
                "AND v.id NOT IN (SELECT id FROM kept)) FROM fetched f ORDER BY f.at",
            null,
        ).use { c -> while (c.moveToNext()) chapters += Triple(c.getInt(0), c.getInt(1), c.getInt(2)) }
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

    /** Removes a chapter's text, search entries, word tags, red letters and paragraphs, except verse cards' verses. */
    private fun drop(book: Int, chapter: Int) {
        val lo = BibleImport.vid(book, chapter, 0)
        val args = arrayOf<Any>(lo, lo + 999)
        val notKept = "NOT IN (SELECT id FROM kept)"
        // The search index first: it reads the words to remove from the verses still there.
        db.execSQL("DELETE FROM verses_fts WHERE docid BETWEEN ? AND ? AND docid $notKept", args)
        for (t in listOf("verses", "tags", "red", "paragraphs")) db.execSQL("DELETE FROM $t WHERE id BETWEEN ? AND ? AND id $notKept", args)
        db.execSQL("DELETE FROM fetched WHERE book = ? AND chapter = ?", arrayOf<Any>(book, chapter))
    }

    /**
     * Verses matching [query] from the online service, with their text where it gives it (the ESV
     * does; for YouVersion it's read from the chapter): (verse id, text or null).
     */
    fun searchRemote(query: String, books: List<BookInfo>): List<Pair<Int, String?>> =
        when (id) {
            // Not kept: their results carry the verses' text, which would count toward the 500.
            Esv.ID -> Esv.search(query, books)
            Nlt.ID -> Nlt.search(query)
            else -> {
                // YouVersion gives verse ids only: kept, so the same search doesn't ask again.
                val q = query.trim().lowercase()
                val cached = db.rawQuery("SELECT ids FROM searches WHERE q = ?", arrayOf(q)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val ids = cached?.split(',')?.mapNotNull { it.toIntOrNull() } ?: YouVersion.searchVerses(id, query).also { found ->
                    synchronized(LOCK) {
                        db.execSQL("INSERT OR REPLACE INTO searches VALUES(?, ?, ?)", arrayOf<Any>(q, System.currentTimeMillis(), found.joinToString(",")))
                    }
                }
                ids.map { it to null }
            }
        }

    /** Whether any chapter so far marked the words of Jesus (if none did, they're found as for imported Bibles). */
    fun marksRed(): Boolean = meta("marksRed") == "1"

    private fun meta(key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    companion object {
        private val LOCK = Any()

        /** At most this many verse cards' verses are kept by a limited Bible, leaving room to read. */
        const val MAX_KEPT = 300

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
