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
    /** Imported by the user (BIB-4): [asset] is then the database file's full path. */
    val imported: Boolean = false,
    /** An online Bible (BIB-12): its YouVersion id; [asset] is the database it's kept in on the tablet. */
    val online: Int = 0,
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
        val file = if (version.imported) File(version.asset) else context.getDatabasePath("$base$DB_VERSION.db")
        if (!version.imported && !file.exists()) {
            // Remove copies of older bundled databases.
            file.parentFile?.listFiles()?.filter { it.name.startsWith(base) && it.name != file.name }?.forEach { it.delete() }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            context.assets.open("bibles/${version.asset}").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp.renameTo(file)
        }
        db = SQLiteDatabase.openDatabase(file.path, null, if (version.online > 0) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY)
        books = db.rawQuery("SELECT id, name, osis, chapters FROM books ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext()) add(BookInfo(c.getInt(0), c.getString(1), c.getString(2), c.getInt(3)))
            }
        }
    }

    /** An online Bible's downloads (BIB-12); null for the others. */
    val online: OnlineBible? = if (version.online > 0) OnlineBible(version.code, version.online, db) else null

    /**
     * For an online Bible: makes sure a chapter is on the tablet before it's read. Off the main
     * thread it's downloaded now (the caller waits); on the main thread it's fetched in the
     * background and [onlineEvents] says when it has come, so the screen shows it then.
     */
    fun ensure(book: Int, chapter: Int) {
        val o = online ?: return
        if (book !in 1..66 || chapter < 1) return
        if (o.isSaved(book, chapter)) { o.touch(book, chapter); return }
        if (android.os.Looper.getMainLooper().isCurrentThread) { fetchLater(book, chapter); return }
        fetchNow(book, chapter)
    }

    private fun fetchNow(book: Int, chapter: Int): Boolean {
        val o = online ?: return false
        return try {
            o.fetch(book, chapter)
            onlineEvents?.arrived(code, book, chapter)
            // Read on without waiting: the next two chapters come in the background.
            for (n in 1..2) if (chapter + n <= book(book).chapters) fetchLater(book, chapter + n, quiet = true)
            true
        } catch (e: Exception) {
            onlineEvents?.failed(code, book, chapter, e)
            false
        }
    }

    private fun fetchLater(book: Int, chapter: Int, quiet: Boolean = false) {
        val o = online ?: return
        val key = "$code $book $chapter"
        if (o.isSaved(book, chapter) || !pending.add(key)) return
        OnlineBible.background.execute {
            try {
                if (quiet) runCatching { o.fetch(book, chapter) }.onSuccess { onlineEvents?.arrived(code, book, chapter) }
                else fetchNow(book, chapter)
            } finally {
                pending.remove(key)
            }
        }
    }

    fun book(id: Int): BookInfo = books[(id - 1).coerceIn(0, books.lastIndex)]

    fun chapter(book: Int, chapter: Int): List<Verse> = ensure(book, chapter).let {
        db.rawQuery(
            "SELECT verse, text FROM verses WHERE book = ? AND chapter = ? ORDER BY verse",
            arrayOf(book.toString(), chapter.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Verse(c.getInt(0), c.getString(1))) }
        }
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
    fun versesBetween(fromId: Int, toId: Int, limit: Int): List<Pair<Int, String>> {
        // An online Bible fetches the chapters of a short passage (a pop-over, a verse card).
        if (online != null && fromId / 1_000_000 == toId / 1_000_000 && (toId / 1000) % 1000 - (fromId / 1000) % 1000 <= 3) {
            for (c in (fromId / 1000) % 1000..(toId / 1000) % 1000) ensure(fromId / 1_000_000, c)
        }
        return savedVersesBetween(fromId, toId, limit)
    }

    /** Verses from [fromId] to [toId] that are on the tablet, without downloading (see [versesBetween]). */
    fun savedVersesBetween(fromId: Int, toId: Int, limit: Int): List<Pair<Int, String>> {
        return db.rawQuery(
            "SELECT id, text FROM verses WHERE id BETWEEN ? AND ? ORDER BY id LIMIT $limit",
            arrayOf(fromId.toString(), toId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1)) } }
    }

    /** The open database, for an imported version's word tags, red letters and paragraphs (see [StudyRepository.ownDb]). */
    internal val database: SQLiteDatabase get() = db

    /** Every verse, in Bible order. */
    fun allVerses(): List<Pair<Int, String>> =
        db.rawQuery("SELECT id, text FROM verses ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1)) }
        }

    /**
     * A verse's text if it's on the tablet, without downloading (an online Bible's previews, such as
     * the cross-reference list, shouldn't fetch a chapter for every line).
     */
    fun savedVerseText(id: Int): String? =
        db.rawQuery("SELECT text FROM verses WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun verseText(id: Int): String? = ensure(id / 1_000_000, (id / 1000) % 1000).let {
        db.rawQuery("SELECT text FROM verses WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
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
        val (wanted, excluded) = splitExcluded(raw)
        val query = ftsQuery(wanted) ?: return emptyList()
        val (lo, hi) = when (scope) {
            SearchScope.ALL -> 1 to 66
            SearchScope.OT -> 1 to 39
            SearchScope.NT -> 40 to 66
            SearchScope.BOOK -> currentBook to currentBook
        }
        val found = searchSaved(query, lo, hi, excluded)
        val o = online ?: return found
        // An online Bible: also ask YouVersion, for chapters not on the tablet yet (not on the main thread).
        if (android.os.Looper.getMainLooper().isCurrentThread) return found
        val ids = runCatching { o.searchRemote(wanted, books) }.getOrDefault(emptyList())
            .filter { it.first / 1_000_000 in lo..hi }
        if (ids.isEmpty()) return found
        val have = found.mapTo(HashSet()) { VerseId.of(it.book, it.chapter, it.verse) }
        val extra = ids.filter { it.first !in have }.mapNotNull { (id, given) ->
            val text = given ?: verseText(id) ?: return@mapNotNull null
            if (containsAny(text, excluded)) null else SearchHit(id / 1_000_000, (id / 1000) % 1000, id % 1000, text)
        }
        return (found + extra).sortedBy { VerseId.of(it.book, it.chapter, it.verse) }
    }

    private fun searchSaved(query: String, lo: Int, hi: Int, excluded: List<String>): List<SearchHit> {
        return try {
            db.rawQuery(
                "SELECT v.book, v.chapter, v.verse, v.text FROM verses_fts " +
                    "JOIN verses v ON v.id = verses_fts.rowid " +
                    "WHERE verses_fts MATCH ? AND v.book BETWEEN ? AND ? ORDER BY v.id LIMIT $MAX_RESULTS",
                arrayOf(query, lo.toString(), hi.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val hit = SearchHit(c.getInt(0), c.getInt(1), c.getInt(2), c.getString(3))
                        if (!containsAny(hit.text, excluded)) add(hit)
                    }
                }
            }
        } catch (e: SQLiteException) {
            emptyList()
        }
    }

    /** What happens to an online Bible's downloads (BIB-12): told to the screen. */
    interface OnlineEvents {
        fun arrived(code: String, book: Int, chapter: Int)
        fun failed(code: String, book: Int, chapter: Int, error: Exception)
    }

    companion object {
        @Volatile var onlineEvents: OnlineEvents? = null

        /** Chapters being fetched in the background ("NIV 43 3"). */
        private val pending = java.util.Collections.synchronizedSet(HashSet<String>())

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
        val BUNDLED = listOf(KJV, BSB, WEB)

        /** Versions the user imported (BIB-4), remembered in files/bibles/imported.json. */
        private val imported = androidx.compose.runtime.mutableStateListOf<BibleVersion>()

        /** Every version, in the order shown in the version picker: the bundled ones, then imported. */
        val ALL: List<BibleVersion> get() = BUNDLED + imported

        private fun registry(context: Context) = File(File(context.filesDir, "bibles").apply { mkdirs() }, "imported.json")

        fun loadImported(context: Context) {
            val f = registry(context)
            val list = if (f.exists()) runCatching { BibleImport.fromJson(org.json.JSONArray(f.readText())) }.getOrDefault(emptyList()) else emptyList()
            imported.clear()
            imported.addAll(list.filter { File(it.asset).exists() })
        }

        private fun saveRegistry(context: Context) {
            val a = org.json.JSONArray()
            imported.forEach { a.put(BibleImport.toJson(it)) }
            registry(context).writeText(a.toString())
        }

        fun addImported(context: Context, v: BibleVersion) {
            imported.removeAll { it.code == v.code }
            imported.add(v)
            saveRegistry(context)
        }

        /** The imported versions' list as saved in backups (DATA-1). */
        fun importedJson(): String = org.json.JSONArray().also { a -> imported.forEach { a.put(BibleImport.toJson(it)) } }.toString()

        /**
         * Brings back imported versions from a backup: [files] holds their database files by name.
         * Each is added (replacing one with the same code) with its file in this tablet's folder.
         */
        fun restoreImported(context: Context, json: String, files: File, kjvBooks: List<BookInfo>) {
            val dir = File(context.filesDir, "bibles").apply { mkdirs() }
            val list = runCatching { BibleImport.fromJson(org.json.JSONArray(json)) }.getOrDefault(emptyList())
            for (v in list) {
                if (v.online > 0) {
                    // Online Bibles aren't in backups: they're downloaded again as they're read (BIB-12).
                    val info = YouVersion.Info(v.online, v.code, v.name, (1..66).toSet(), v.copyright, v.description)
                    addImported(context, OnlineBible.create(context, info, kjvBooks).copy(code = v.code))
                    continue
                }
                val src = File(files, File(v.asset).name)
                if (!src.exists()) continue
                val dest = File(dir, src.name)
                src.copyTo(dest, overwrite = true)
                addImported(context, v.copy(asset = dest.path))
            }
        }

        fun removeImported(context: Context, code: String) {
            imported.firstOrNull { it.code == code }?.let { File(it.asset).delete() }
            imported.removeAll { it.code == code }
            saveRegistry(context)
        }

        /** The database file a version reads, for its size in the version manager (BIB-5). */
        fun fileOf(context: Context, v: BibleVersion): File =
            if (v.imported) File(v.asset) else context.getDatabasePath("bible_${v.asset.removeSuffix(".db")}_v$DB_VERSION.db")

        /**
         * Separates words to leave out (SRCH-3), written with a minus sign ("love -world"), from the
         * rest of the query. Returns the query without them and the excluded words (lower case,
         * a trailing * kept for word beginnings).
         */
        fun splitExcluded(raw: String): Pair<String, List<String>> {
            val excluded = ArrayList<String>()
            val kept = StringBuilder()
            Regex("\"[^\"]*\"|\\S+").findAll(raw).forEach { m ->
                val tok = m.value
                if (tok.length > 1 && tok.startsWith("-") && !tok.startsWith("\"")) {
                    val w = tok.drop(1).lowercase().replace('\'', '\u2019').filter { it.isLetterOrDigit() || it == '\u2019' || it == '*' }
                    if (w.trimEnd('*').isNotEmpty()) excluded += w
                } else {
                    kept.append(tok).append(' ')
                }
            }
            return kept.toString().trim() to excluded
        }

        /** Whether [text] has any of [words] as a whole word (or word beginning, for "lov*"). */
        fun containsAny(text: String, words: List<String>): Boolean {
            if (words.isEmpty()) return false
            val t = text.lowercase().replace('\'', '\u2019')
            return words.any { w ->
                val stem = Regex.escape(w.trimEnd('*'))
                val end = if (w.endsWith("*")) "" else "(?![\\p{L}\\p{N}])"
                Regex("(?<![\\p{L}\\p{N}])$stem$end").containsMatchIn(t)
            }
        }

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
            Regex("[\\p{L}\u2019']+").findAll(splitExcluded(raw).first).map { it.value.lowercase().replace('\'', '\u2019') }
                .filter { it != "or" && it.length > 1 }.toList()
    }
}
