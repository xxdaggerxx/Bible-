package com.biblestudy.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** A Hebrew or Greek word from Strong's dictionaries (STD-3). [id] is e.g. "G26" or "H7225". */
data class LexEntry(
    val id: String,
    val lemma: String,
    val xlit: String,
    val pron: String,
    val derivation: String,
    val def: String,
    val kjv: String,
) {
    val hebrew get() = id.startsWith("H")
    val language get() = if (hebrew) "Hebrew" else "Greek"
}

/** A verse where a Strong's number is used, with the word ranges (in the verse text) it translates. */
data class Occurrence(val id: Int, val text: String, val words: List<IntRange>)

/** A dictionary article or topic, with [body] in the study markup (see [StudyText]). */
data class StudyEntry(val id: Long, val title: String, val body: String)

/**
 * A person or place in the Bible (STD-10, STD-11), from STEPBible's TIPNR. Family fields hold
 * TIPNR ids ("Amram@Exo.6.18-1Ch") separated by commas, or a "+" between two parents.
 */
data class NameEntry(
    val id: Long,
    val uid: String,
    val name: String,
    val place: Boolean,
    val brief: String,
    val article: String,
    val parents: String,
    val siblings: String,
    val partners: String,
    val children: String,
    val area: String,
    val lat: Double?,
    val lon: Double?,
    val refCount: Int,
) {
    companion object {
        /** The TIPNR ids in a family field. */
        fun ids(field: String): List<String> =
            field.split(',', '+').map { it.substringBefore('=').replace("(?)", "").trim() }.filter { '@' in it }

        /** "Jerusalem_wives@2Sa.5.13" → "Jerusalem wives". */
        fun label(uid: String) = uid.substringBefore('@').replace('_', ' ')
    }
}

/**
 * One word of the Hebrew or Greek text (STD-4): the word, how it's said, its English meaning here,
 * its Strong's number, its grammar code ("H:Ncfsa", "A:…" or "G:N-GSF") and which editions have it
 * ("" all, "m" modern editions only, "k" only the text the KJV was translated from).
 */
data class OriginalWord(val word: String, val xlit: String, val gloss: String, val strong: String, val grammar: String, val edition: String) {
    val hebrew: Boolean get() = !grammar.startsWith("G:")
}

/** Matthew Henry on a range of verses (STD-7). */
data class CommentarySection(val start: Int, val end: Int, val body: String)

/**
 * The bundled study library (assets/study/study.db, built by tools/build_study_db.py): Strong's
 * numbers for each word of the KJV, BSB and WEB, Strong's Hebrew and Greek dictionaries, Easton's
 * Bible Dictionary, Nave's Topical Bible and Matthew Henry's Concise Commentary.
 */
class StudyRepository(private val context: Context) {
    private val db: SQLiteDatabase = open("study", DB_VERSION)

    /** The Hebrew and Greek text (assets/study/original.db), copied out the first time it's needed. */
    private val orig: SQLiteDatabase by lazy { open("original", ORIGINAL_VERSION) }

    private fun open(name: String, version: Int): SQLiteDatabase {
        val file = context.getDatabasePath("${name}_v$version.db")
        if (!file.exists()) {
            file.parentFile?.listFiles()?.filter { it.name.startsWith("${name}_v") && it.name != file.name }?.forEach { it.delete() }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            context.assets.open("study/$name.db").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file)
        }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    // ---------- words of Jesus (BIB-8) ----------

    /**
     * The words of Jesus in a chapter: verse number → character ranges in that verse's [texts].
     * Only the bundled KJV, BSB and WEB are marked.
     */
    fun redLetters(version: String, book: Int, chapter: Int, texts: Map<Int, String>): Map<Int, List<IntRange>> {
        if (book < 40) return emptyMap()
        val base = book * 1_000_000 + chapter * 1_000
        val out = HashMap<Int, List<IntRange>>()
        db.rawQuery("SELECT id, words FROM red WHERE version = ? AND id BETWEEN ? AND ?",
            arrayOf(version, base.toString(), (base + 999).toString())).use { c ->
            while (c.moveToNext()) {
                val verse = c.getInt(0) - base
                val words = words(texts[verse] ?: continue)
                val ranges = c.getString(1).split(',').mapNotNull { r ->
                    val (a, b) = r.split('-').map { it.toInt() }
                    if (a > b || b >= words.size) null else words[a].first..words[b].last
                }
                if (ranges.isNotEmpty()) out[verse] = ranges
            }
        }
        return out
    }

    // ---------- the original languages (STD-4, BIB-9) ----------

    /** A verse in Hebrew or Greek, word by word, in the original order (English verse numbering). */
    fun original(verseId: Int): List<OriginalWord> =
        orig.rawQuery("SELECT words FROM original WHERE id = ?", arrayOf(verseId.toString())).use { c ->
            if (!c.moveToFirst()) emptyList()
            else c.getString(0).split('\n').map { line ->
                val f = line.split('\t')
                OriginalWord(f[0], f.getOrElse(1) { "" }, f.getOrElse(2) { "" }, f.getOrElse(3) { "" },
                    f.getOrElse(4) { "" }, f.getOrElse(5) { "" })
            }
        }

    // ---------- word studies ----------

    /** The Strong's number of each word of a verse (as split by [words]), or null where there is none. */
    fun strongs(version: String, verseId: Int): List<String?> {
        val raw = db.rawQuery("SELECT words FROM tags WHERE version = ? AND id = ?", arrayOf(version, verseId.toString()))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return emptyList()
        val prefix = if (verseId < NT_START) "H" else "G"
        return raw.substring(1, raw.length - 1).split(' ').map { n ->
            when {
                n.isEmpty() -> null
                n[0].isLetter() -> n
                else -> prefix + n
            }
        }
    }

    fun lexicon(id: String): LexEntry? =
        db.rawQuery("SELECT id, lemma, xlit, pron, derivation, def, kjv FROM lexicon WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) LexEntry(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "") else null
        }

    /**
     * Every verse in [version] where the word [strong] is used (STD-8, SRCH-7), in Bible order,
     * with the English words that translate it. [text] gives a verse's text in that version.
     */
    fun occurrences(version: String, strong: String, text: (Int) -> String?, limit: Int = 5000): List<Occurrence> {
        val s = normalizeStrong(strong) ?: return emptyList()
        val hebrew = s[0] == 'H'
        val num = s.substring(1)
        val (lo, hi) = if (hebrew) 0 to NT_START - 1 else NT_START to Int.MAX_VALUE
        val tokens = listOf(" $num ", " $s ")
        val rows = db.rawQuery(
            "SELECT id, words FROM tags WHERE version = ? AND id BETWEEN ? AND ? AND (words LIKE ? OR words LIKE ?) ORDER BY id LIMIT $limit",
            arrayOf(version, lo.toString(), hi.toString(), "%${tokens[0]}%", "%${tokens[1]}%"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1)) } }
        return rows.mapNotNull { (id, words) ->
            val t = text(id) ?: return@mapNotNull null
            val nums = words.substring(1, words.length - 1).split(' ')
            val ranges = words(t)
            val hits = nums.indices.filter { nums[it] == num || nums[it] == s }.mapNotNull { ranges.getOrNull(it) }
            Occurrence(id, t, mergeAdjacent(hits, t))
        }
    }

    // ---------- dictionary and topics ----------

    /** Easton's article for [term] (any case), if there is one. */
    fun dictionaryEntry(term: String): StudyEntry? = entry("dictionary", "term", term)

    /** Easton's articles whose title starts with [prefix] (or all, alphabetically, when blank). */
    fun dictionarySearch(prefix: String, limit: Int = 200): List<StudyEntry> = search("dictionary", "term", prefix, limit)

    fun dictionaryById(id: Long): StudyEntry? = byId("dictionary", "term", id)

    fun topic(name: String): StudyEntry? = entry("topics", "name", name)

    fun topicSearch(prefix: String, limit: Int = 200): List<StudyEntry> = search("topics", "name", prefix, limit)

    fun topicById(id: Long): StudyEntry? = byId("topics", "name", id)

    /** Nave's topics that list this verse (STD-6, STD-9), most specific (fewest verses) first. */
    fun topicsFor(verseId: Int, limit: Int = 30): List<StudyEntry> =
        db.rawQuery(
            "SELECT t.id, t.name, (SELECT COUNT(*) FROM topic_refs r2 WHERE r2.topic = t.id) AS n FROM topic_refs r " +
                "JOIN topics t ON t.id = r.topic WHERE r.start BETWEEN ? AND ? AND r.end >= ? GROUP BY t.id ORDER BY n LIMIT $limit",
            arrayOf((verseId - 999).toString(), verseId.toString(), verseId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(StudyEntry(c.getLong(0), c.getString(1), "")) } }

    /**
     * Passages related to a verse through the topics they share (STD-9): verses listed under two
     * or more of the same topics, most shared first. Returns (start, end, shared topic count).
     */
    fun relatedByTopics(verseId: Int, limit: Int = 20): List<Triple<Int, Int, Int>> {
        val topics = topicsFor(verseId, 60).map { it.id }
        if (topics.isEmpty()) return emptyList()
        val chapter = verseId / 1000
        return db.rawQuery(
            "SELECT start, end, COUNT(DISTINCT topic) AS k FROM topic_refs WHERE topic IN (${topics.joinToString(",")}) " +
                "GROUP BY start, end HAVING k >= 2 ORDER BY k DESC, start LIMIT ${limit * 3}",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getInt(0), c.getInt(1), c.getInt(2))) } }
            .filter { it.first / 1000 != chapter } // not the verse's own chapter
            .take(limit)
    }

    /** Verse numbers in a chapter that start a paragraph or a line of poetry (READ-6). */
    fun paragraphStarts(version: String, book: Int, chapter: Int): Set<Int> {
        val lo = book * 1_000_000 + chapter * 1000
        return db.rawQuery(
            "SELECT id FROM paragraphs WHERE version = ? AND id BETWEEN ? AND ?",
            arrayOf(version, lo.toString(), (lo + 999).toString()),
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getInt(0) % 1000) } }
    }

    // ---------- names and places (STD-10, STD-11) ----------

    private val NAME_COLS = "id, uid, name, kind, brief, article, parents, siblings, partners, children, area, lat, lon, refs"
    /** The same columns of the names table joined as "n". */
    private val N_COLS = NAME_COLS.split(", ").joinToString { "n.$it" }

    private fun android.database.Cursor.toName() = NameEntry(
        getLong(0), getString(1), getString(2), getString(3) == "place", getString(4) ?: "", getString(5) ?: "",
        getString(6) ?: "", getString(7) ?: "", getString(8) ?: "", getString(9) ?: "", getString(10) ?: "",
        if (isNull(11)) null else getDouble(11), if (isNull(12)) null else getDouble(12), getInt(13),
    )

    private fun names(sql: String, args: Array<String>?): List<NameEntry> =
        db.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toName()) } }

    fun nameById(id: Long): NameEntry? = names("SELECT $NAME_COLS FROM names WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    fun nameByUid(uid: String): NameEntry? = names("SELECT $NAME_COLS FROM names WHERE uid = ?", arrayOf(uid)).firstOrNull()

    /** People and places whose name starts with (then contains) [prefix]; the best known first. */
    fun nameSearch(prefix: String, limit: Int = 200): List<NameEntry> {
        val p = prefix.trim().lowercase()
        val starts = names("SELECT $NAME_COLS FROM names WHERE key >= ? AND key < ? ORDER BY refs DESC LIMIT $limit", arrayOf(p, p + "\uffff"))
        if (p.length < 3) return starts
        val seen = starts.map { it.id }.toSet()
        return (starts + names("SELECT $NAME_COLS FROM names WHERE key LIKE ? ORDER BY refs DESC LIMIT $limit", arrayOf("%$p%")).filter { it.id !in seen }).take(limit)
    }

    /** The people and places mentioned in a verse. */
    fun namesInVerse(verseId: Int): List<NameEntry> =
        names("SELECT $N_COLS FROM name_refs r JOIN names n ON n.id = r.name WHERE r.verse = ? ORDER BY n.refs DESC", arrayOf(verseId.toString()))

    /** The people and places mentioned in a chapter, most mentioned first. */
    fun namesInChapter(book: Int, chapter: Int, limit: Int = 60): List<NameEntry> {
        val lo = book * 1_000_000 + chapter * 1000
        return names(
            "SELECT $N_COLS FROM name_refs r JOIN names n ON n.id = r.name " +
                "WHERE r.verse BETWEEN ? AND ? GROUP BY n.id ORDER BY COUNT(*) DESC, n.refs DESC LIMIT $limit",
            arrayOf(lo.toString(), (lo + 999).toString()),
        )
    }

    /** The person or place a Strong's number names, preferring the one mentioned in [verseId]. */
    fun nameForStrong(strong: String, verseId: Int): NameEntry? {
        val s = normalizeStrong(strong) ?: return null
        return names(
            "SELECT $N_COLS FROM name_strongs s JOIN names n ON n.id = s.name WHERE s.strong = ? " +
                "ORDER BY EXISTS(SELECT 1 FROM name_refs r WHERE r.name = n.id AND r.verse = ?) DESC, n.refs DESC LIMIT 1",
            arrayOf(s, verseId.toString()),
        ).firstOrNull()
    }

    /** Every verse that mentions a person or place, in Bible order. */
    fun nameVerses(id: Long): List<Int> =
        db.rawQuery("SELECT verse FROM name_refs WHERE name = ? ORDER BY verse", arrayOf(id.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        }

    /** Well-known places with coordinates, to help find your way on the map. */
    fun landmarks(limit: Int = 40): List<NameEntry> =
        names("SELECT $NAME_COLS FROM names WHERE kind = 'place' AND lat IS NOT NULL ORDER BY refs DESC LIMIT $limit", null)

    // ---------- commentary ----------

    /** Matthew Henry's sections on a chapter, in order (STD-7). */
    fun commentary(book: Int, chapter: Int): List<CommentarySection> {
        val lo = book * 1_000_000 + chapter * 1000
        return db.rawQuery(
            "SELECT start, end, body FROM commentary WHERE start <= ? AND end >= ? ORDER BY start",
            arrayOf((lo + 999).toString(), lo.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(CommentarySection(c.getInt(0), c.getInt(1), c.getString(2))) } }
    }

    private fun entry(table: String, col: String, term: String): StudyEntry? =
        db.rawQuery("SELECT id, $col, body FROM $table WHERE key = ? LIMIT 1", arrayOf(term.trim().lowercase())).use { c ->
            if (c.moveToFirst()) StudyEntry(c.getLong(0), c.getString(1), c.getString(2)) else null
        }

    private fun byId(table: String, col: String, id: Long): StudyEntry? =
        db.rawQuery("SELECT id, $col, body FROM $table WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) StudyEntry(c.getLong(0), c.getString(1), c.getString(2)) else null
        }

    private fun search(table: String, col: String, prefix: String, limit: Int): List<StudyEntry> {
        val p = prefix.trim().lowercase()
        // Titles that start with what was typed, then titles that contain it.
        val starts = db.rawQuery(
            "SELECT id, $col FROM $table WHERE key >= ? AND key < ? ORDER BY key LIMIT $limit",
            arrayOf(p, p + "￿"),
        ).use { c -> buildList { while (c.moveToNext()) add(StudyEntry(c.getLong(0), c.getString(1), "")) } }
        if (p.length < 3 || starts.size >= limit) return starts
        val seen = starts.map { it.id }.toSet()
        val contains = db.rawQuery(
            "SELECT id, $col FROM $table WHERE key LIKE ? ORDER BY key LIMIT $limit",
            arrayOf("%$p%"),
        ).use { c -> buildList { while (c.moveToNext()) { val id = c.getLong(0); if (id !in seen) add(StudyEntry(id, c.getString(1), "")) } } }
        return (starts + contains).take(limit)
    }

    companion object {
        /** Bump when study.db changes, so the new copy replaces the old one. */
        private const val DB_VERSION = 5
        private const val ORIGINAL_VERSION = 1
        const val NT_START = 40_000_000

        private val WORD = Regex("[\\p{L}\\p{M}\\p{N}_’']+")

        /** Character ranges of the words of a verse, split the same way as when study.db was built. */
        fun words(text: String): List<IntRange> = WORD.findAll(text).map { it.range }.toList()

        /** "g26", "G0026" or "G26" → "G26"; null if it isn't a Strong's number. */
        fun normalizeStrong(s: String): String? {
            val m = Regex("^([HhGg])0*(\\d{1,5})$").find(s.trim()) ?: return null
            return m.groupValues[1].uppercase() + m.groupValues[2]
        }

        /** Joins neighbouring words that translate one original word ("only begotten") into one range. */
        private fun mergeAdjacent(ranges: List<IntRange>, text: String): List<IntRange> {
            val out = ArrayList<IntRange>()
            for (r in ranges) {
                val last = out.lastOrNull()
                if (last != null && text.substring(last.last + 1, r.first).isBlank()) out[out.lastIndex] = last.first..r.last
                else out += r
            }
            return out
        }
    }
}
