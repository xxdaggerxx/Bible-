package com.biblestudy.app.data

import com.biblestudy.app.platform.*
import kotlin.math.ln

/** A Hebrew or Greek word a search word translates (TOP-1). */
data class SmartLemma(val strong: String, val xlit: String, val uses: Int)

/** A verse found by smart search, with why it was found (SRCH-14). */
data class SmartVerse(val id: Int, val score: Double, val reasons: List<String>)

/** A group of results: a Nave's subtopic, or a step in a person's life (TOP-6, PPL-1). Verses in Bible order. */
data class SmartGroup(val label: String, val ids: List<Int>)

/** A reflection that matches the search (SRCH-42). */
data class SmartReflection(val title: String, val start: Int, val end: Int)

data class SmartResult(
    /** The words searched, after question wording ("What does the Bible say about") is left out. */
    val words: List<String>,
    /** Every form searched for each word, for marking matches. */
    val forms: List<String>,
    val lemmas: List<SmartLemma>,
    /** The Nave's topic the search names, if any. */
    val topic: StudyEntry?,
    /** The person or place the search names, if any (PPL-1, PPL-3). */
    val name: NameEntry?,
    /** The passages that matter most, shown first (TOP-5). */
    val key: List<SmartVerse>,
    /** Nave's subtopics, or the steps of a person's life, in order. */
    val groups: List<SmartGroup>,
    /** Every verse found, best first. */
    val verses: List<SmartVerse>,
    val reflections: List<SmartReflection>,
    /** Ready-made sketch pages that cover the search (PPL-4), by their template id. */
    val pages: List<String>,
) {
    val byId: Map<Int, SmartVerse> by lazy { verses.associateBy { it.id } }
}

/**
 * Smart search (section 29, SRCH-10 to SRCH-44): one search across every built-in version that
 * finds the passages on a topic even where they don't use its words. No AI and no internet: it
 * reads assets/study/search.db (built by tools/build_search_db.py) with the word tags, Nave's
 * topics, Names & places, cross-references and the app's own study notes.
 */
class SmartSearch(private val context: AppContext, private val study: StudyRepository) {
    private val db: SqlDb by lazy { open() }

    private fun open(): SqlDb {
        val file = context.getDatabasePath("search_v$DB_VERSION.db")
        if (!file.exists()) {
            file.parent?.listFiles()?.filter { it.name.startsWith("search_v") && it.name != file.name }?.forEach { it.delete() }
            PackedAssets.copy(context, "study/search.db", file)
        }
        return context.sql.open(file.toString(), readOnly = true)
    }

    /**
     * Searches [raw] in [bibles] (their saved verses) and the study data. Null when there is
     * nothing to search for once question words are left out.
     */
    fun search(raw: String, bibles: List<BibleRepository>): SmartResult? {
        val words = contentWords(raw)
        if (words.isEmpty()) return null

        val name = findName(words)
        val theme = if (name != null) words.filter { it != name.name.lowercase() } else words
        val topic = if (name != null) study.topic(name.name) else findTopic(words)

        // What each word finds: verses using its Hebrew/Greek words, its English forms, the notes.
        class Found(val lemma: Map<Int, String>, val text: Set<Int>, val notes: Map<Int, String>)
        val lemmas = ArrayList<SmartLemma>()
        val allForms = ArrayList<String>()
        val found = theme.associateWith { w ->
            val forms = forms(w)
            allForms += forms
            val fam = family(w)
            lemmas += fam
            val lemma = HashMap<Int, String>()
            for (l in fam) for (id in lemmaVerses(l.strong)) lemma.putIfAbsent(id, l.xlit)
            val text = HashSet<Int>()
            for (b in bibles) text += b.versesWithAny(forms)
            Found(lemma, text, noteVerses(forms))
        }

        // Nave's references, weighted by how small their subtopic is (TOP-3, TOP-5).
        val groups = topic?.let { naveGroups(it.id) } ?: emptyList()
        val naveWeight = HashMap<Int, Double>()
        val naveLabel = HashMap<Int, String>()
        for (g in groups) {
            val w = 3.0 * (12.0 / g.ids.size.coerceAtLeast(1)).coerceIn(0.3, 1.0)
            for (id in g.ids) if (w > (naveWeight[id] ?: 0.0)) { naveWeight[id] = w; naveLabel[id] = g.label }
        }

        val candidates = HashSet<Int>()
        for (f in found.values) { candidates += f.lemma.keys; candidates += f.text; candidates += f.notes.keys }
        if (name == null) candidates += naveWeight.keys

        val need = if (found.size <= 3) found.size else found.size - 1
        val verses = ArrayList<SmartVerse>()
        for (id in candidates) {
            var score = naveWeight[id] ?: 0.0
            var matched = 0
            var inWords = false
            val reasons = LinkedHashSet<String>()
            naveLabel[id]?.let { reasons += "Nave's: $it" }
            for (f in found.values) {
                var s = 0.0
                f.lemma[id]?.let { s += 2; reasons += it; inWords = true }
                if (id in f.text) { s += 1; inWords = true }
                f.notes[id]?.let { s += 1; reasons += it }
                if (s > 0) matched++
                score += s
            }
            if (found.isNotEmpty() && matched < need && naveWeight[id] == null) continue
            if (name != null && found.isEmpty()) continue
            score += ln(1.0 + votes(id))
            if (!inWords) score -= 2
            verses += SmartVerse(id, score, reasons.toList())
        }
        verses.sortByDescending { it.score }

        val inWords = { v: SmartVerse -> found.values.any { v.id in it.lemma || v.id in it.text } }
        val key = if (name != null) emptyList() else verses.filter(inWords).take(KEY_COUNT)
        val shownGroups = when {
            name == null -> groups
            theme.isEmpty() -> groups
            else -> {
                // A person and a theme ("Abraham's trials"): the steps of their life the theme is in.
                val hit = verses.mapTo(HashSet()) { it.id }
                groups.filter { g -> g.ids.any { it in hit } || theme.any { w -> forms(w).any { g.label.lowercase().contains(it) } } }
                    .ifEmpty { groups }
            }
        }
        return SmartResult(
            words = words,
            forms = allForms.distinct(),
            lemmas = lemmas.distinctBy { it.strong }.sortedByDescending { it.uses },
            topic = topic,
            name = name,
            key = key,
            groups = shownGroups,
            verses = verses,
            reflections = reflections(theme.ifEmpty { words }),
            pages = readyMadePages(words),
        )
    }

    // ---------- the search words ----------

    /** The words of a search worth looking up: question wording, small words and "'s" left out. */
    fun contentWords(raw: String): List<String> {
        var q = raw.lowercase().replace('’', '\'')
        q = QUESTION.replace(q, "")
        return Regex("[a-z']+").findAll(q).map { it.value.removeSuffix("'s").trim('\'') }
            .filter { it.length > 1 && it !in STOP }.distinct().toList()
    }

    /** The forms searched for [word]: righteous, righteousness, righteously… (from the build). */
    fun forms(word: String): List<String> =
        db.rawQuery("SELECT alt FROM wordgroup WHERE form = ?", arrayOf(word)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)!!) }
        }.ifEmpty { listOf(word) }

    /** The Hebrew and Greek words [word] translates (TOP-1), most used first. */
    fun family(word: String): List<SmartLemma> =
        db.rawQuery("SELECT strong, uses FROM family WHERE form = ? AND uses >= $MIN_USES ORDER BY uses DESC LIMIT $MAX_LEMMAS", arrayOf(word)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)!! to c.getInt(1)) }
        }.map { (s, n) -> SmartLemma(s, study.lexicon(s)?.xlit?.ifBlank { s } ?: s, n) }

    /** Every verse using Hebrew or Greek word [strong] in the KJV, BSB or WEB. */
    fun lemmaVerses(strong: String): List<Int> =
        db.rawQuery("SELECT ids FROM lemma WHERE strong = ?", arrayOf(strong)).use { c ->
            if (c.moveToFirst()) c.getString(0)!!.split(',').mapNotNull { it.toIntOrNull() } else emptyList()
        }

    /** Cross-reference votes pointing at each verse (about 29,000 verses), read once. */
    private val weights: Map<Int, Int> by lazy {
        db.rawQuery("SELECT id, votes FROM weight", null).use { c ->
            HashMap<Int, Int>(32_768).also { m -> while (c.moveToNext()) m[c.getInt(0)] = c.getInt(1) }
        }
    }

    private fun votes(id: Int): Int = weights[id] ?: 0

    // ---------- names, topics and pages ----------

    /** A well-known person or place the search names, with a Nave's entry (PPL-1, PPL-3). */
    private fun findName(words: List<String>): NameEntry? {
        for (w in words) {
            if (w in NOT_NAMES) continue
            val n = study.nameSearch(w, limit = 10).filter { it.name.equals(w, ignoreCase = true) }.maxByOrNull { it.refCount } ?: continue
            if (n.refCount >= MIN_NAME_REFS && study.topic(n.name) != null) return n
        }
        return null
    }

    /** The Nave's topic the search names: the whole search, else one of its words or their forms. */
    private fun findTopic(words: List<String>): StudyEntry? {
        study.topic(words.joinToString(" "))?.let { return it }
        for (w in words) {
            study.topic(w)?.let { return it }
            for (f in forms(w)) study.topic(f)?.let { return it }
        }
        return null
    }

    /** A Nave's entry's paragraphs, in order, each with the verses it lists. */
    fun naveGroups(topic: Long): List<SmartGroup> =
        db.rawQuery("SELECT label, refs FROM nave_sub WHERE topic = ? ORDER BY ord", arrayOf(topic.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val ids = verses(c.getString(1)!!)
                    if (ids.isNotEmpty()) add(SmartGroup(c.getString(0)!!.ifBlank { "Passages" }, ids))
                }
            }
        }

    private fun readyMadePages(words: List<String>): List<String> =
        PAGES.filter { (_, keys) -> words.any { it in keys } }.map { it.first }

    // ---------- the app's own notes (SRCH-40 to SRCH-42) ----------

    /** Verses whose study notes use any of [forms]: verse → where it was found. */
    private fun noteVerses(forms: List<String>): Map<Int, String> {
        val out = HashMap<Int, String>()
        for ((kind, ranges, title) in notes(forms, "kind <> 'reflection' AND kind <> 'glance'")) {
            val why = when (kind) {
                "commentary" -> "Commentary note"
                "christ" -> "Points to Christ: $title"
                else -> title
            }
            for (id in verses(ranges)) out.putIfAbsent(id, why)
        }
        return out
    }

    /** Reflections that use every word (any of its forms), in Bible order (SRCH-42). */
    private fun reflections(words: List<String>): List<SmartReflection> {
        var keep: Map<String, Triple<String, String, String>>? = null
        for (w in words) {
            val rows = notes(forms(w), "kind = 'reflection'").associateBy { it.second }
            keep = keep?.filterKeys { it in rows } ?: rows
        }
        return keep.orEmpty().values.mapNotNull { (_, ranges, title) ->
            val (a, b) = ranges.split(',').first().split('-').map { it.toInt() }
            SmartReflection(title, a, b)
        }.sortedBy { it.start }.take(MAX_REFLECTIONS)
    }

    private fun notes(forms: List<String>, where: String): List<Triple<String, String, String>> {
        val q = forms.filter { it.all(Char::isLetter) }.joinToString(" OR ").ifEmpty { return emptyList() }
        return db.rawQuery(
            "SELECT n.kind, n.ranges, n.title FROM notes_fts f JOIN notes n ON n.id = f.docid WHERE notes_fts MATCH ? AND $where",
            arrayOf(q),
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0)!!, c.getString(1)!!, c.getString(2)!!)) } }
    }

    companion object {
        /** Bump when assets/study/search.db changes, so the new copy replaces the old one. */
        const val DB_VERSION = 1
        const val KEY_COUNT = 15
        private const val MIN_USES = 5
        private const val MAX_LEMMAS = 10
        private const val MAX_REFLECTIONS = 30
        private const val MIN_NAME_REFS = 20

        private val QUESTION = Regex(
            "^\\s*(what|who|where|when|how|why)\\b.*?\\b(say|says|said|teach|teaches|tell|tells|is|are|was|were|did|does)\\b(\\s+(about|of))?\\s*"
        )
        private val STOP = setOf(
            "a", "an", "the", "of", "in", "on", "at", "to", "for", "and", "or", "but", "is", "are", "was", "were", "be",
            "what", "does", "do", "did", "say", "says", "about", "bible", "scripture", "scriptures", "tell", "teach",
            "teaches", "me", "us", "we", "you", "how", "why", "who", "when", "where", "which", "with", "by", "from",
            "his", "her", "their", "its", "it", "as", "that", "this", "these", "those", "verses", "verse", "passages",
        )
        /** Words that are also someone's name but mean something else in a search. */
        private val NOT_NAMES = setOf("god", "lord", "faith", "grace", "hope", "joy", "love", "peace", "spirit", "word", "christ")

        /** Ready-made sketch pages (SketchTemplates) and the search words that call for them (PPL-4). */
        private val PAGES = listOf(
            "paul" to setOf("paul", "journeys", "journey", "missionary"),
            "exodus" to setOf("exodus", "wilderness", "moses"),
            "christ" to setOf("jesus", "christ"),
            "kings" to setOf("kings", "king", "judah", "israel", "divided"),
            "tribes" to setOf("tribes", "tribe", "jacob"),
            "temples" to setOf("temple", "temples", "solomon", "herod"),
            "tabernacle" to setOf("tabernacle"),
            "feasts" to setOf("feast", "feasts", "festival", "festivals", "passover", "pentecost"),
            "adam" to setOf("genealogy", "genealogies", "adam", "descent"),
        )

        /** The verse ids in "start-end,start-end": every verse of each range (ranges over 2 chapters keep their start). */
        fun verses(ranges: String): List<Int> = buildList {
            for (r in ranges.split(',')) {
                val parts = r.split('-')
                val a = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val b = parts.getOrNull(1)?.toIntOrNull() ?: a
                if (b < a || b - a > 2000) { add(a); continue }
                var id = a
                while (id <= b) {
                    if (id % 1000 in 1..176) add(id)
                    id++
                }
            }
        }
    }
}
