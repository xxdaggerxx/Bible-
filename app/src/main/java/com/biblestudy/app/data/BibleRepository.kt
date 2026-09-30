package com.biblestudy.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.biblestudy.app.model.BookInfo
import com.biblestudy.app.model.CrossRef
import com.biblestudy.app.model.Heading
import com.biblestudy.app.model.SearchHit
import com.biblestudy.app.model.SearchScope
import com.biblestudy.app.model.Verse
import com.biblestudy.app.model.VerseId
import java.io.File

/** A Bible version bundled with the app, with a one-line [summary] and a short [description] for readers. */
data class BibleVersion(
    val code: String,
    val name: String,
    val asset: String,
    val copyright: String,
    val summary: String,
    val description: String,
)

/**
 * Read-only access to one bundled Bible version (SQLite with an FTS4 search index; the KJV
 * database also holds the OpenBible.info cross-references). The database ships in
 * assets/bibles and is copied to app storage on first launch so it works fully offline.
 */
class BibleRepository(context: Context, val version: BibleVersion) {
    val code = version.code
    private val db: SQLiteDatabase
    val books: List<BookInfo>

    init {
        val base = "bible_${version.asset.removeSuffix(".db")}_v"
        val file = context.getDatabasePath("$base$DB_VERSION.db")
        if (!file.exists()) {
            // Remove copies of older bundled databases.
            file.parentFile?.listFiles()?.filter { it.name.startsWith(base) && it.name != file.name }?.forEach { it.delete() }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            context.assets.open("bibles/${version.asset}").use { input ->
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

    /** Section headings in a chapter (only databases built with headings have any). */
    fun headings(book: Int, chapter: Int): List<Heading> = try {
        val lo = VerseId.of(book, chapter, 0)
        db.rawQuery(
            "SELECT verse_id, level, text, refs FROM headings WHERE verse_id BETWEEN ? AND ? ORDER BY verse_id, level",
            arrayOf(lo.toString(), (lo + 999).toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Heading(VerseId.verse(c.getInt(0)), c.getInt(1), c.getString(2), c.getString(3))) }
        }
    } catch (e: SQLiteException) {
        emptyList() // no headings table
    }

    /** Verses from [fromId] to [toId] (verse ids), in order, at most [limit]. */
    fun versesBetween(fromId: Int, toId: Int, limit: Int): List<Pair<Int, String>> =
        db.rawQuery(
            "SELECT id, text FROM verses WHERE id BETWEEN ? AND ? ORDER BY id LIMIT $limit",
            arrayOf(fromId.toString(), toId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1)) } }

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
        /** Bump when a bundled database changes, so the new copy replaces the old one. */
        private const val DB_VERSION = 2
        const val MAX_RESULTS = 2000

        val KJV = BibleVersion(
            "KJV", "King James Version (1769)", "kjv.db", "Public domain.",
            summary = "Classic 1611 English, word for word",
            description = "Translated by a team of English scholars and first published in 1611; this is the " +
                "1769 revision found in most KJV Bibles today. It follows the Hebrew and Greek closely, word for " +
                "word, in the Early Modern English of its time (\u201cthee\u201d, \u201cthou\u201d, " +
                "\u201cbelieveth\u201d). Its New Testament is based on the Greek Textus Receptus. Loved for its " +
                "beauty and memorable phrasing, though some words have changed meaning since.",
        )
        val BSB = BibleVersion(
            "BSB", "Berean Standard Bible", "bsb.db",
            "The Holy Bible, Berean Standard Bible (BSB). Dedicated to the public domain, 2023.",
            summary = "Modern, accurate and readable \u2014 closest to the NIV",
            description = "A modern translation first published in 2016 and given to the public domain in " +
                "2023. It balances word-for-word accuracy with natural, current English, much like the NIV or " +
                "ESV. The New Testament mainly follows the modern critical Greek text, so a few verses found in " +
                "the KJV (such as Matthew 17:21) appear only as footnotes. Pronouns for God are capitalised " +
                "(\u201cHe\u201d, \u201cHis\u201d). The section headings shown in this app come from the BSB.",
        )
        val WEB = BibleVersion(
            "WEB", "World English Bible", "web.db",
            "World English Bible (WEB). Public domain. \u201cWorld English Bible\u201d is a trademark of eBible.org.",
            summary = "Modern-English update of the 1901 ASV, fairly literal",
            description = "A revision of the American Standard Version (1901) into modern English, made by " +
                "volunteers and completed in the early 2000s. It stays close to the original wording \u2014 more " +
                "literal than the NIV or NLT \u2014 while replacing words like \u201cthee\u201d and " +
                "\u201cthou\u201d. Its New Testament follows the Majority Text (the reading of most Greek " +
                "manuscripts), so it keeps almost all the verses the KJV has.",
        )
        /** In the order shown in the version picker. */
        val ALL = listOf(KJV, BSB, WEB)

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
