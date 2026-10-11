package com.biblestudy.app.data

import android.content.Context

/** A hard word's short meaning (STD-23), and the Bible dictionary article to read more, if any. */
data class HardWord(val word: String, val meaning: String, val term: String, val oldWord: Boolean)

/**
 * Hard words explained (STD-23): about 1,500 words a lay reader may not know, with a one-line
 * meaning, written by AI before shipping (tools/build_hardwords.py makes assets/study/hardwords.tsv).
 * Old English words ("wist", "froward", "corn") count only in the King James Version; Bible words
 * ("propitiation", "Pharisee", "cubit") count in every version.
 */
object HardWords {
    private class Entry(val headword: String, val meaning: String, val term: String, val oldWord: Boolean)

    @Volatile private var entries: Map<String, Entry>? = null

    /** Whether old English words count in [version]. */
    fun oldEnglish(version: String) = version.equals("KJV", ignoreCase = true)

    /** The hard word [text] is, if any (a word as it stands in a verse: "Wist", "Pharisees'"). */
    fun lookup(context: Context, version: String, text: String): HardWord? {
        val key = text.takeWhile { it.isLetter() }.lowercase()
        val e = load(context)[key] ?: return null
        if (e.oldWord && !oldEnglish(version)) return null
        return HardWord(text.takeWhile { it.isLetter() }, e.meaning, e.term, e.oldWord)
    }

    /**
     * The words to mark in a chapter: verse → character ranges in its text. Only the first time each
     * hard word (in any of its forms) comes in the chapter, so the page doesn't fill with lines.
     */
    fun marks(context: Context, version: String, verses: List<Pair<Int, String>>): Map<Int, List<IntRange>> {
        val all = load(context)
        if (all.isEmpty()) return emptyMap()
        val old = oldEnglish(version)
        val seen = HashSet<String>()
        val out = HashMap<Int, MutableList<IntRange>>()
        for ((verse, text) in verses) {
            for (r in StudyRepository.words(text)) {
                val letters = text.substring(r).takeWhile { it.isLetter() }
                val e = all[letters.lowercase()] ?: continue
                if (e.oldWord && !old) continue
                if (!seen.add(e.headword)) continue
                out.getOrPut(verse) { ArrayList() } += r.first until r.first + letters.length
            }
        }
        return out
    }

    private fun load(context: Context): Map<String, Entry> = entries ?: synchronized(this) {
        entries ?: runCatching {
            context.assets.open("study/hardwords.tsv").bufferedReader().useLines { lines ->
                val map = HashMap<String, Entry>()
                for (line in lines) {
                    val p = line.split('\t')
                    if (p.size < 4) continue
                    val forms = p[1].split(',')
                    val e = Entry(forms[0], p[2], p[3], p[0] == "kjv")
                    for (f in forms) map[f] = e
                }
                map
            }
        }.getOrDefault(emptyMap()).also { entries = it }
    }
}
