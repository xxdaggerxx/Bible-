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
        val html = if (book in books) YouVersion.chapterHtml(id, book, chapter) else null
        val parsed = html?.let { BibleImport.parseUsfm(listOf(YouVersion.toUsfm(it, book, chapter))) }
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
                db.execSQL("INSERT OR REPLACE INTO fetched VALUES(?, ?, ?)", arrayOf<Any>(book, chapter, System.currentTimeMillis()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return true
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
