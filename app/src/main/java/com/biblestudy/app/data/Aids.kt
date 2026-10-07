package com.biblestudy.app.data

import android.content.Context

/** The kinds of Bible aids (AID-1 to AID-12), each with its own switch in Settings. */
enum class AidKind(val label: String) {
    HARD("Hard word"), NAME("Person or place"), CUSTOM("Custom or feast"), SYMBOL("Symbol"), NUMBER("Bible number"),
}

/** One marked word or phrase: [range] in its verse's text; [key] is the entry id, name id or hard word. */
data class AidMark(val range: IntRange, val kind: AidKind, val key: String)

/**
 * A custom, feast, symbol or Bible number (AID-3 to AID-7), from assets/study/aids.tsv (made by
 * tools/build_aids.py). [category] is the kind in the file: feast, worship, life, group, rome,
 * symbol or number.
 */
class AidEntry(
    val category: String,
    val id: String,
    val title: String,
    val forms: List<List<String>>,
    val anywhere: Boolean,
    private val verses: LongArray,
    private val not: LongArray,
    val refs: String,
    val sources: String,
    val text: String,
) {
    val kind: AidKind get() = when (category) { "symbol" -> AidKind.SYMBOL; "number" -> AidKind.NUMBER; else -> AidKind.CUSTOM }

    /** A number for the entry that stays the same between versions of the app (for ink written on it). */
    val key: Long get() = id.hashCode().toLong() and 0x7fffffffL

    /** The whole entry as a study article: text, key verses (as links) and sources. */
    val article: String get() = "$text\n\nKey verses: $refs.\n\nSources: $sources."

    /** The heading it's listed under in the browse views. */
    val group: String get() = GROUPS[category] ?: "Other"

    /** Whether it may be marked in verse [id]. */
    fun markableIn(id: Int): Boolean = !inRanges(not, id) && (anywhere || inRanges(verses, id))

    companion object {
        val GROUPS = linkedMapOf(
            "feast" to "Feasts and holy days", "worship" to "Worship and the temple", "life" to "Daily life and law",
            "group" to "Groups in Jesus' day", "rome" to "Roman rule", "symbol" to "Symbols", "number" to "Bible numbers",
        )

        /** Ranges packed as lo, hi, lo, hi… */
        private fun inRanges(r: LongArray, id: Int): Boolean {
            var i = 0
            while (i < r.size) { if (id >= r[i] && id <= r[i + 1]) return true; i += 2 }
            return false
        }
    }
}

/** What's marked: one switch per kind (AID-10). */
data class AidSwitches(val hard: Boolean, val names: Boolean, val customs: Boolean, val symbols: Boolean) {
    val any: Boolean get() = hard || names || customs || symbols
    fun allows(k: AidKind) = when (k) {
        AidKind.HARD -> hard; AidKind.NAME -> names; AidKind.CUSTOM -> customs; AidKind.SYMBOL, AidKind.NUMBER -> symbols
    }
}

/**
 * Bible aids (AID-1 to AID-12): people and places, Jewish customs and feasts, symbols and Bible
 * numbers, marked in the text like hard words (STD-23), with the first mention of each in a chapter
 * marked. People and places are found through the word tags, so the right person is meant; the rest
 * by the words each version uses.
 */
object Aids {
    /** In a chapter naming more people and places than this (a genealogy), only the best known are marked. */
    private const val MANY_NAMES = 25
    private const val TOP_NAMES = 10

    @Volatile private var entries: List<AidEntry>? = null
    @Volatile private var index: Map<String, List<Pair<AidEntry, List<String>>>>? = null

    fun all(context: Context): List<AidEntry> = load(context)

    fun byId(context: Context, id: String): AidEntry? = load(context).firstOrNull { it.id == id }

    fun byKey(context: Context, key: Long): AidEntry? = load(context).firstOrNull { it.key == key }

    /**
     * The marks in a chapter's verses ([verses]: verse number → text), first mention of each entry,
     * person or place in the chapter: verse → marks.
     */
    fun marks(
        context: Context, study: StudyRepository, version: String, book: Int, chapter: Int,
        verses: List<Pair<Int, String>>, on: AidSwitches,
    ): Map<Int, List<AidMark>> {
        if (!on.any) return emptyMap()
        val out = HashMap<Int, MutableList<AidMark>>()
        val seen = HashSet<String>()
        if (on.customs || on.symbols) {
            for ((v, text) in verses) {
                for (m in phraseMarks(context, BibleImport.vid(book, chapter, v), text, on, seen)) out.getOrPut(v) { ArrayList() } += m
            }
        }
        if (on.names) {
            val names = study.chapterNameStrongs(book, chapter)
            // A genealogy: only the best-known names, so the page doesn't fill with lines.
            val all = names.values.flatMap { it.values }.distinctBy { it.id }
            val allowed = if (all.size > MANY_NAMES) all.sortedByDescending { it.refCount }.take(TOP_NAMES).map { it.id }.toSet() else null
            for ((v, text) in verses) {
                val inVerse = names[BibleImport.vid(book, chapter, v)] ?: continue
                for (m in nameMarks(study, version, BibleImport.vid(book, chapter, v), text, inVerse, seen, allowed)) out.getOrPut(v) { ArrayList() } += m
            }
        }
        if (on.hard) {
            for ((v, ranges) in HardWords.marks(context, version, verses)) {
                val mine = out.getOrPut(v) { ArrayList() }
                // Where an aid covers the word, its fuller card takes the hard word's place.
                for (r in ranges) if (mine.none { it.range.first <= r.last && r.first <= it.range.last }) {
                    val text = verses.firstOrNull { it.first == v }?.second ?: continue
                    mine += AidMark(r, AidKind.HARD, text.substring(r).lowercase())
                }
            }
        }
        return out.filterValues { it.isNotEmpty() }.mapValues { (_, l) -> l.sortedBy { it.range.first } }
    }

    /** Everything an aid explains at word [word] of verse [verseId] (not only first mentions), for the verse pop-up. */
    fun at(
        context: Context, study: StudyRepository, version: String, verseId: Int, text: String, word: Int, on: AidSwitches,
    ): List<Pair<AidMark, Any>> {
        val r = StudyRepository.words(text).getOrNull(word) ?: return emptyList()
        val found = ArrayList<Pair<AidMark, Any>>()
        if (on.customs || on.symbols) {
            for (m in phraseMarks(context, verseId, text, on, HashSet())) {
                if (m.range.first <= r.last && r.first <= m.range.last) byId(context, m.key)?.let { found += m to it }
            }
        }
        if (on.names) {
            val inVerse = study.chapterNameStrongs(verseId / 1_000_000, (verseId / 1000) % 1000)[verseId].orEmpty()
            for (m in nameMarks(study, version, verseId, text, inVerse, HashSet(), null)) {
                if (m.range.first <= r.last && r.first <= m.range.last) inVerse.values.firstOrNull { it.id.toString() == m.key }?.let { found += m to it }
            }
        }
        return found
    }

    /** Customs, feasts, symbols and numbers named in one verse, longest wording first; [seen] keeps first mentions. */
    private fun phraseMarks(context: Context, verseId: Int, text: String, on: AidSwitches, seen: MutableSet<String>): List<AidMark> {
        val idx = index ?: run { load(context); index } ?: return emptyList()
        val words = StudyRepository.words(text)
        val lower = words.map { normal(text.substring(it)) }
        val out = ArrayList<AidMark>()
        for (i in words.indices) {
            val candidates = idx[lower[i]] ?: continue
            for ((e, form) in candidates) {
                if (e.id in seen || !on.allows(e.kind) || !e.markableIn(verseId)) continue
                if (i + form.size > words.size) continue
                if ((1 until form.size).any { lower[i + it] != form[it] }) continue
                out += AidMark(words[i].first..words[i + form.size - 1].last, e.kind, e.id)
                seen += e.id
            }
        }
        return out
    }

    /**
     * People and places in one verse, found by the Strong's number of each word among the names
     * the verse is known to mention. The word must be capitalised and start like the name (so a
     * word tagged with a shared number, like "LORD", isn't taken for a place).
     */
    private fun nameMarks(
        study: StudyRepository, version: String, verseId: Int, text: String, inVerse: Map<String, NameEntry>,
        seen: MutableSet<String>, allowed: Set<Long>?,
    ): List<AidMark> {
        if (inVerse.isEmpty()) return emptyList()
        val tags = runCatching { study.strongs(version, verseId) }.getOrDefault(emptyList())
        val words = StudyRepository.words(text)
        val out = ArrayList<AidMark>()
        for ((i, r) in words.withIndex()) {
            val strong = tags.getOrNull(i)?.let { StudyRepository.normalizeStrong(it) } ?: continue
            val n = inVerse[strong] ?: continue
            val key = "n${n.id}"
            if (key in seen || (allowed != null && n.id !in allowed)) continue
            val w = text.substring(r)
            if (!w.first().isUpperCase() || !w.first().equals(n.name.first(), ignoreCase = true)) continue
            out += AidMark(r, AidKind.NAME, n.id.toString())
            seen += key
        }
        return out
    }

    /** A word as matched: lower case, with a curly apostrophe made straight. */
    private fun normal(w: String) = w.lowercase().replace('’', '\'')

    private fun load(context: Context): List<AidEntry> = entries ?: synchronized(this) {
        entries ?: runCatching {
            context.assets.open("study/aids.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 10) return@mapNotNull null
                    fun ranges(s: String) = s.split(',').filter { it.isNotEmpty() }
                        .flatMap { r -> r.split('-').map { it.toLong() } }.toLongArray()
                    AidEntry(
                        p[0], p[1], p[2], p[3].split('|').map { f -> f.split(' ').filter { it.isNotEmpty() }.map(::normal) },
                        p[4] == "1", ranges(p[5]), ranges(p[6]), p[7], p[8], p[9],
                    )
                }.toList()
            }
        }.getOrDefault(emptyList()).also { list ->
            // First word of each wording → the entries and wordings starting with it, longest first.
            index = list.flatMap { e -> e.forms.map { f -> e to f } }.filter { it.second.isNotEmpty() }
                .groupBy { it.second.first() }.mapValues { (_, l) -> l.sortedByDescending { it.second.size } }
            entries = list
        }
    }
}
