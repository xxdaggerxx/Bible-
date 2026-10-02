package com.biblestudy.app.data

/**
 * Strong's numbers for the words of an imported Bible (BIB-4, STD-3), worked out on the tablet
 * when it's imported, since the files publishers give out have none.
 *
 * Each English word is matched to one of the Hebrew or Greek words of its own verse:
 *  1. the same word (or a form of it: "love", "loved") in the KJV, BSB or WEB of that verse, the
 *     one nearest in position when it appears more than once;
 *  2. otherwise, how often the three translate each of the verse's original words that way
 *     across the whole Bible;
 *  3. otherwise, the English meaning given with the original word, or its Strong's entry, when
 *     only one of the verse's words fits.
 * Small words ("the", "of") are only tagged when that pairing is common across the Bible, so
 * tapping one never opens a word study for its neighbour. Measured on the BSB and WEB (tagging
 * each from the other two), about nine in ten words are tagged and about nine in ten of those
 * match the published tags.
 */
class WordTagger(
    model: Model,
    private val references: List<Reference>,
    private val original: (Int) -> List<OriginalWord>,
    private val lexicon: (String) -> String?,
) {
    /** A tagged version: every verse's text with the Strong's number of each word (as [StudyRepository.words] splits it). */
    interface Reference {
        fun forEachVerse(f: (id: Int, text: String, strongs: List<String?>) -> Unit)
        fun verse(id: Int): Pair<String, List<String?>>?
    }

    /**
     * How the reference versions translate: stem → Strong's number → how often ([pairs]), and
     * stem → how often it appears at all ([counts]). See [learn].
     */
    class Model(val pairs: HashMap<String, HashMap<String, Int>>, val counts: HashMap<String, Int>)

    private val pairs = model.pairs
    private val counts = model.counts
    private val lexiconStems = HashMap<String, Set<String>>()

    /**
     * The Strong's number of each word of [text] (as [StudyRepository.words] splits it), or null.
     * [ids] is the verse, or every verse of a joined range ("1-2") whose text is kept under the first.
     */
    fun tag(ids: List<Int>, text: String): List<String?> {
        val words = StudyRepository.words(text).map { text.substring(it) }
        if (words.isEmpty()) return emptyList()
        val candidates = LinkedHashSet<String>()
        val glosses = HashMap<String, MutableSet<String>>()
        for (id in ids) for (w in original(id)) {
            val s = StudyRepository.normalizeStrong(w.strong) ?: continue
            candidates += s
            for (g in StudyRepository.words(w.gloss)) glosses.getOrPut(stem(w.gloss.substring(g))) { HashSet() } += s
        }
        // The same words in the tagged versions of this verse, with where they sit (0 to 1).
        val local = HashMap<String, MutableList<Pair<Float, String>>>()
        for (r in references) {
            val tw = ArrayList<String>()
            val ts = ArrayList<String?>()
            for (id in ids) {
                val (t, s) = r.verse(id) ?: continue
                val ranges = StudyRepository.words(t)
                if (ranges.size != s.size) continue
                ranges.forEach { tw += t.substring(it) }
                ts += s
            }
            val last = maxOf(1, tw.size - 1).toFloat()
            for (j in tw.indices) {
                val s = ts[j] ?: continue
                local.getOrPut(stem(tw[j])) { ArrayList() } += (j / last) to s
            }
        }
        val last = maxOf(1, words.size - 1).toFloat()
        return words.mapIndexed { j, w ->
            val k = stem(w)
            val position = j / last
            val here = local[k]
            val best: String? = if (here != null) {
                val score = HashMap<String, Float>()
                for ((p, s) in here) score[s] = (score[s] ?: 0f) + 1f - kotlin.math.abs(p - position)
                score.maxByOrNull { it.value }?.key
            } else {
                val total = counts[k] ?: 0
                val byWord = pairs[k]
                var pick: String? = null
                var most = 0
                if (byWord != null) for (s in candidates) {
                    val c = byWord[s] ?: 0
                    if (c >= 1 && c >= 0.02 * total && c > most) { pick = s; most = c }
                }
                pick ?: glosses[k]?.singleOrNull()
                    ?: if (norm(w) in SMALL) null else candidates.filter { k in lexiconStems(it) }.singleOrNull()
            }
            // Small words only keep a number they're commonly used for.
            if (best != null && norm(w) in SMALL && (pairs[k]?.get(best) ?: 0) < 0.05 * maxOf(1, counts[k] ?: 0)) null else best
        }
    }

    private fun lexiconStems(strong: String): Set<String> = lexiconStems.getOrPut(strong) {
        val t = lexicon(strong) ?: return@getOrPut emptySet()
        StudyRepository.words(t).mapTo(HashSet()) { stem(t.substring(it)) }
    }

    companion object {
        private val SUFFIXES = listOf("ing", "edst", "eth", "est", "ies", "ied", "ed", "es", "s", "ly")

        /** Words too common to tag unless the pairing is a usual one. */
        private val SMALL = ("the a an of and to in on at by for from with is was are were be been am will shall would should " +
            "may might can could not no that this these those it its he him his she her they them their we us our you your " +
            "i me my who whom which what as so but or if then there when than all").split(' ').toSet()

        fun norm(w: String) = w.lowercase().replace('’', '\'').trim('\'')

        /** A rough stem, so "loved", "loves" and "loving" match ("lov"). */
        fun stem(w: String): String {
            var s = norm(w)
            if (s.endsWith("'s")) s = s.dropLast(2)
            for (suf in SUFFIXES) if (s.endsWith(suf) && s.length - suf.length >= 3) return s.dropLast(suf.length)
            return s
        }

        /** Learns from the [references] how each Strong's number is translated (a few seconds on a tablet). */
        fun learn(references: List<Reference>): Model {
            val pairs = HashMap<String, HashMap<String, Int>>()
            val counts = HashMap<String, Int>()
            for (r in references) r.forEachVerse { _, text, strongs ->
                val ranges = StudyRepository.words(text)
                if (ranges.size != strongs.size) return@forEachVerse
                for (i in ranges.indices) {
                    val k = stem(text.substring(ranges[i]))
                    counts[k] = (counts[k] ?: 0) + 1
                    val s = strongs[i] ?: continue
                    val m = pairs.getOrPut(k) { HashMap() }
                    m[s] = (m[s] ?: 0) + 1
                }
            }
            return Model(pairs, counts)
        }

        /** Strong's numbers as stored in a tags row: " 25 2316  1063 ", the testament's letter left off. */
        fun encode(verseId: Int, strongs: List<String?>): String {
            val ot = verseId < StudyRepository.NT_START
            return strongs.joinToString(" ", " ", " ") { s ->
                when {
                    s == null -> ""
                    (s[0] == 'H') == ot -> s.substring(1)
                    else -> s
                }
            }
        }
    }
}
