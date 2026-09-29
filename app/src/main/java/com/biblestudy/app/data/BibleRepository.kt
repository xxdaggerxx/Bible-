package com.biblestudy.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.biblestudy.app.model.BookInfo
import com.biblestudy.app.model.CrossRef
import com.biblestudy.app.model.SearchHit
import com.biblestudy.app.model.SearchScope
import com.biblestudy.app.model.Verse
import java.io.File

/**
 * Read-only access to one bundled Bible version (SQLite with an FTS4 search index
 * and OpenBible.info cross-references). The database ships in assets/bibles and is
 * copied to app storage on first launch so it works fully offline.
 */
class BibleRepository(context: Context) {
    val code = "KJV"
    private val db: SQLiteDatabase
    val books: List<BookInfo>

    init {
        val file = context.getDatabasePath(DB_FILE)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            context.assets.open("bibles/kjv.db").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp.renameTo(file)
        }
        db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        books = db.rawQuery("SELECT id, name, osis, chapters FROM books ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext()) add(BookInfo(c.getInt(0), c.getString(1), c.getString(2), c.getInt(3)))
            }
        }
    }

    fun book(id: Int): BookInfo = books[(id - 1).coerceIn(0, books.lastIndex)]

    fun chapter(book: Int, chapter: Int): List<Verse> =
        db.rawQuery(
            "SELECT verse, text FROM verses WHERE book = ? AND chapter = ? ORDER BY verse",
            arrayOf(book.toString(), chapter.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Verse(c.getInt(0), c.getString(1))) }
        }

    fun verseText(id: Int): String? =
        db.rawQuery("SELECT text FROM verses WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun crossRefs(fromId: Int): List<CrossRef> =
        db.rawQuery(
            "SELECT x.to_start, x.to_end, x.votes, v.text FROM xrefs x " +
                "JOIN verses v ON v.id = x.to_start WHERE x.from_id = ? ORDER BY x.votes DESC",
            arrayOf(fromId.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(CrossRef(c.getInt(0), c.getInt(1), c.getInt(2), c.getString(3))) }
        }

    fun search(raw: String, scope: SearchScope, currentBook: Int): List<SearchHit> {
        val query = ftsQuery(raw) ?: return emptyList()
        val (lo, hi) = when (scope) {
            SearchScope.ALL -> 1 to 66
            SearchScope.OT -> 1 to 39
            SearchScope.NT -> 40 to 66
            SearchScope.BOOK -> currentBook to currentBook
        }
        return try {
            db.rawQuery(
                "SELECT v.book, v.chapter, v.verse, v.text FROM verses_fts " +
                    "JOIN verses v ON v.id = verses_fts.rowid " +
                    "WHERE verses_fts MATCH ? AND v.book BETWEEN ? AND ? ORDER BY v.id LIMIT $MAX_RESULTS",
                arrayOf(query, lo.toString(), hi.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(SearchHit(c.getInt(0), c.getInt(1), c.getInt(2), c.getString(3)))
                }
            }
        } catch (e: SQLiteException) {
            emptyList()
        }
    }

    companion object {
        const val DB_FILE = "bible_kjv_v1.db"
        const val MAX_RESULTS = 2000

        /**
         * Turns what the user typed into an FTS4 query:
         *  - words are ANDed, "quoted text" is an exact phrase,
         *  - OR between words means either, a trailing * matches word prefixes (lov* → love, loved).
         */
        fun ftsQuery(raw: String): String? {
            val parts = ArrayList<String>()
            val normalized = raw.replace('\'', '\u2019')
            Regex("\"([^\"]*)\"|(\\S+)").findAll(normalized).forEach { m ->
                val phrase = m.groups[1]?.value
                if (phrase != null) {
                    val w = clean(phrase)
                    if (w.isNotEmpty()) parts += "\"$w\""
                } else {
                    val tok = m.groupValues[2]
                    if (tok == "OR" || tok == "or") {
                        parts += "OR"
                    } else {
                        val w = clean(tok)
                        if (w.isNotEmpty()) parts += w + if (tok.endsWith("*")) "*" else ""
                    }
                }
            }
            while (parts.firstOrNull() == "OR") parts.removeAt(0)
            while (parts.lastOrNull() == "OR") parts.removeAt(parts.lastIndex)
            return parts.joinToString(" ").ifBlank { null }
        }

        private fun clean(s: String): String =
            s.replace(Regex("[^\\p{L}\\p{N}\u2019 ]"), " ").trim().replace(Regex("\\s+"), " ")

        /** Lower-case words from a query, used to bold matches in results. */
        fun terms(raw: String): List<String> =
            Regex("[\\p{L}\u2019']+").findAll(raw).map { it.value.lowercase().replace('\'', '\u2019') }
                .filter { it != "or" && it.length > 1 }.toList()
    }
}
