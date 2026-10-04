package com.biblestudy.app.data

import android.content.Context

/** A chapter at a glance (STD-22): what's happening, where it fits, and a key verse. */
data class ChapterGlance(val keyVerse: Int, val fits: String, val what: String)

/**
 * The "Chapter at a glance" cards for all 1,189 chapters, written by AI before shipping
 * (tools/build_glance.py makes assets/study/glance.tsv). Read once, then kept in memory (about 180 KB).
 */
object Glance {
    @Volatile private var cards: Map<Int, ChapterGlance>? = null

    fun get(context: Context, book: Int, chapter: Int): ChapterGlance? = load(context)[book * 1000 + chapter]

    private fun load(context: Context): Map<Int, ChapterGlance> = cards ?: synchronized(this) {
        cards ?: runCatching {
            context.assets.open("study/glance.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 5) null
                    else (p[0].toInt() * 1000 + p[1].toInt()) to ChapterGlance(p[2].toInt(), p[3], p[4])
                }.toMap()
            }
        }.getOrDefault(emptyMap()).also { cards = it }
    }
}
