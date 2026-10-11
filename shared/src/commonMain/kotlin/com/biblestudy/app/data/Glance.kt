package com.biblestudy.app.data

import com.biblestudy.app.platform.AppContext
import com.biblestudy.app.platform.Lock
import com.biblestudy.app.platform.lineSequence
import com.biblestudy.app.platform.locked
import com.biblestudy.app.platform.readText

/** A chapter at a glance (STD-22): what's happening, where it fits, and a key verse. */
data class ChapterGlance(val keyVerse: Int, val fits: String, val what: String)

/**
 * The "Chapter at a glance" cards for all 1,189 chapters, written by AI before shipping
 * (tools/build_glance.py makes assets/study/glance.tsv). Read once, then kept in memory (about 180 KB).
 */
object Glance {
    private val lock = Lock()
    @kotlin.concurrent.Volatile private var cards: Map<Int, ChapterGlance>? = null

    fun get(context: AppContext, book: Int, chapter: Int): ChapterGlance? = load(context)[book * 1000 + chapter]

    private fun load(context: AppContext): Map<Int, ChapterGlance> = cards ?: locked(lock) {
        cards ?: runCatching {
            context.assets.lineSequence("study/glance.tsv").let { lines ->
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 5) null
                    else (p[0].toInt() * 1000 + p[1].toInt()) to ChapterGlance(p[2].toInt(), p[3], p[4])
                }.toMap()
            }
        }.getOrDefault(emptyMap()).also { cards = it }
    }
}
