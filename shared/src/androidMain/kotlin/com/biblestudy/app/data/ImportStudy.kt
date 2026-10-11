package com.biblestudy.app.data

import android.database.sqlite.SQLiteDatabase

/**
 * Gives an imported Bible (BIB-4) what the bundled versions get from study.db, in its own
 * database and the same tables, so every feature treats it alike:
 *  - word tags for word studies (STD-3, STD-8), worked out by [WordTagger];
 *  - the words of Jesus (BIB-8): the file's own marks, or, when it has none, its quotations in
 *    the verses where the WEB marks Jesus speaking (as for the BSB);
 *  - paragraph and poetry starts (READ-6): the file's own, or, when it has none, the BSB's.
 */
object ImportStudy {
    /** Bump when what's built here changes, so imported versions are rebuilt on the next start. */
    const val VERSION = "1"

    /** Whether [db] already has study tables built by this version of the app. */
    fun isBuilt(db: SQLiteDatabase): Boolean =
        StudyRepository.hasTable(db, "tags") && runCatching {
            db.rawQuery("SELECT value FROM meta WHERE key = 'study'", null).use { c -> c.moveToFirst() && c.getString(0) == VERSION }
        }.getOrDefault(false)

    /**
     * Builds the tables for version [code] in [db] (open for writing). [parsed] is what the file
     * held; null when only the saved text is known (a version imported before this existed).
     * [references] are the bundled KJV, BSB and WEB; [progress] goes from 0 to 1.
     */
    @Synchronized
    fun build(
        db: SQLiteDatabase,
        code: String,
        parsed: BibleImport.Parsed?,
        study: StudyRepository,
        references: List<BibleRepository>,
        progress: (Float) -> Unit = {},
    ) {
        val verses = parsed?.verses?.toSortedMap()
            ?: db.rawQuery("SELECT id, text FROM verses ORDER BY id", null).use { c ->
                java.util.TreeMap<Int, String>().apply { while (c.moveToNext()) put(c.getInt(0), c.getString(1)) }
            }
        progress(0f)
        val tagger = tagger(study, references)
        progress(0.1f)

        val paragraphs = parsed?.paragraphs?.takeIf { it.isNotEmpty() }
            ?: study.paragraphIds(BibleRepository.BSB.code).filterTo(HashSet()) { it in verses }
        val red = if (parsed?.marksRed == true) parsed.red else guessRed(verses, study, references.firstOrNull { it.code == BibleRepository.WEB.code })
        val bridges = parsed?.bridges ?: emptyMap()

        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
            createTables(db)
            for (t in listOf("tags", "red", "paragraphs")) db.execSQL("DELETE FROM $t")
            for (id in paragraphs) db.execSQL("INSERT INTO paragraphs VALUES(?, ?)", arrayOf<Any>(code, id))
            for ((id, words) in red) db.execSQL("INSERT INTO red VALUES(?, ?, ?)", arrayOf<Any>(code, id, words))
            var done = 0
            for ((id, text) in verses) {
                val ids = (id..(id - id % 1000 + (bridges[id] ?: id % 1000))).toList()
                val strongs = tagger.tag(ids, text)
                if (strongs.any { it != null }) db.execSQL("INSERT INTO tags VALUES(?, ?, ?)", arrayOf<Any>(code, id, WordTagger.encode(id, strongs)))
                if (++done % 500 == 0) progress(0.1f + 0.9f * done / verses.size)
            }
            db.execSQL("INSERT OR REPLACE INTO meta VALUES('study', ?)", arrayOf(VERSION))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        progress(1f)
    }

    /** The tables this fills (also made empty in an online Bible's new database). */
    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tags(version TEXT NOT NULL, id INTEGER NOT NULL, words TEXT NOT NULL, PRIMARY KEY(version, id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS red(version TEXT NOT NULL, id INTEGER NOT NULL, words TEXT NOT NULL, PRIMARY KEY(version, id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS paragraphs(version TEXT NOT NULL, id INTEGER NOT NULL, PRIMARY KEY(version, id))")
    }

    /** The word tagger, matching words against the bundled [references] (KJV, BSB, WEB). Slow to make: keep it. */
    fun tagger(study: StudyRepository, references: List<BibleRepository>): WordTagger =
        WordTagger(study.translations, references.map { reference(it, study) }, { id -> study.original(id) }, { id -> study.lexicon(id)?.let { it.kjv + " " + it.def } })

    /**
     * Adds word tags, the words of Jesus and paragraph starts for the verses of one downloaded
     * chapter of online Bible [code] (BIB-12), inside the caller's transaction. [guessRed]: find the
     * words of Jesus as for an imported Bible, because this Bible hasn't marked them so far.
     */
    fun addChapter(
        db: SQLiteDatabase, code: String, parsed: BibleImport.Parsed, study: StudyRepository, tagger: WordTagger,
        guessRed: Boolean, web: BibleRepository?,
    ) {
        val verses = parsed.verses.toSortedMap()
        val paragraphs = parsed.paragraphs.ifEmpty { study.paragraphIds(BibleRepository.BSB.code).filterTo(HashSet()) { it in verses } }
        val red = if (parsed.marksRed || !guessRed) parsed.red else guessRed(verses, study, web)
        for (id in paragraphs) db.execSQL("INSERT OR REPLACE INTO paragraphs VALUES(?, ?)", arrayOf<Any>(code, id))
        for ((id, words) in red) db.execSQL("INSERT OR REPLACE INTO red VALUES(?, ?, ?)", arrayOf<Any>(code, id, words))
        for ((id, text) in verses) {
            val ids = (id..(id - id % 1000 + (parsed.bridges[id] ?: id % 1000))).toList()
            val strongs = tagger.tag(ids, text)
            if (strongs.any { it != null }) db.execSQL("INSERT OR REPLACE INTO tags VALUES(?, ?, ?)", arrayOf<Any>(code, id, WordTagger.encode(id, strongs)))
        }
    }

    private fun reference(bible: BibleRepository, study: StudyRepository) = WordTagger.Reference { id ->
        bible.verseText(id)?.let { it to study.strongs(bible.code, id) }
    }

    /**
     * The words of Jesus for a version whose file doesn't mark them: in each verse where the WEB
     * marks him speaking, the quotations (or the parts of them in this verse) whose words are
     * closer to the WEB's words of Jesus than to the rest of the WEB verse.
     */
    fun guessRed(verses: Map<Int, String>, study: StudyRepository, web: BibleRepository?): Map<Int, String> {
        if (web == null) return emptyMap()
        val webRed = study.redRows(web.code)
        val out = HashMap<Int, String>()
        var quoted = false
        var chapter = -1
        for ((id, text) in verses.toSortedMap()) {
            if (id < StudyRepository.NT_START) continue
            if (id / 1000 != chapter) { chapter = id / 1000; quoted = false }
            // Quotation stretches as [first word, last word, still open].
            val segs = ArrayList<IntArray>()
            val words = ArrayList<String>()
            for (m in QUOTES_AND_WORDS.findAll(text)) {
                val t = m.value
                when (t) {
                    "“" -> quoted = true
                    "”" -> quoted = false
                    "\"" -> quoted = !quoted
                    else -> {
                        val last = segs.lastOrNull()
                        if (quoted && (last == null || last[1] != words.size - 1 || last[2] == 0)) segs += intArrayOf(words.size, words.size, 1)
                        else if (quoted) last!![1] = words.size
                        words += WordTagger.norm(t)
                    }
                }
                if ((t == "”" || (t == "\"" && !quoted)) && segs.isNotEmpty()) segs.last()[2] = 0
            }
            val ranges = webRed[id] ?: continue
            val webText = web.verseText(id) ?: continue
            val webWords = StudyRepository.words(webText).map { WordTagger.norm(webText.substring(it)) }
            val spoken = HashSet<Int>()
            for (r in ranges.split(',')) {
                val (a, b) = r.split('-').map { it.toInt() }
                for (i in a..b) spoken += i
            }
            val redWords = webWords.filterIndexedTo(HashSet()) { i, _ -> i in spoken }
            val restWords = webWords.filterIndexedTo(HashSet()) { i, _ -> i !in spoken }
            val flags = BooleanArray(words.size).toMutableList()
            for (s in segs) {
                val seg = words.subList(s[0], s[1] + 1)
                if (seg.count { it in redWords } > seg.count { it in restWords }) for (i in s[0]..s[1]) flags[i] = true
            }
            BibleImport.wordRanges(flags).takeIf { it.isNotEmpty() }?.let { out[id] = it }
        }
        return out
    }

    private val QUOTES_AND_WORDS = Regex("[“”\"]|[\\p{L}\\p{M}\\p{N}_’']+")
}
