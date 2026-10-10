package com.biblestudy.app.data

import android.content.Context

/**
 * An Old Testament passage that points to Jesus Christ (AID-13), in the traditional view: a
 * [prophecy] the New Testament says he fulfilled (or one traditionally read of the Messiah), or a
 * type, a person, event or thing that pictures him. [ref] is the passage, [fulfilled] the New
 * Testament references (readable, so they show as links).
 */
class ChristEntry(
    val prophecy: Boolean,
    val title: String,
    private val ranges: LongArray,
    val ref: String,
    val fulfilled: String,
    val note: String,
) {
    val kindLabel: String get() = if (prophecy) "Prophecy" else "Picture of Christ"

    /** The first verse marked, for going there and for Bible order. */
    val first: Int get() = ranges[0].toInt()

    fun contains(id: Int): Boolean {
        var i = 0
        while (i < ranges.size) { if (id >= ranges[i] && id <= ranges[i + 1]) return true; i += 2 }
        return false
    }

    /** The verses marked in one chapter (verse numbers). */
    fun versesIn(book: Int, chapter: Int): List<Int> {
        val lo = BibleImport.vid(book, chapter, 0).toLong(); val hi = BibleImport.vid(book, chapter, 999).toLong()
        val out = ArrayList<Int>()
        var i = 0
        while (i < ranges.size) {
            val a = maxOf(ranges[i], lo); val b = minOf(ranges[i + 1], hi)
            if (a <= b) for (v in a..b) out += (v % 1000).toInt()
            i += 2
        }
        return out
    }
}

/**
 * Points to Christ (AID-13): Old Testament verses that point to Jesus, from
 * assets/study/christ.tsv (made by tools/build_christ.py). Marked with a soft gold line under the
 * whole verse; tapping the verse explains it, with where the New Testament fulfils it.
 */
object Christ {
    @Volatile private var entries: List<ChristEntry>? = null

    fun all(context: Context): List<ChristEntry> = entries ?: synchronized(this) {
        entries ?: runCatching {
            context.assets.open("study/christ.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line -> parse(line) }.toList()
            }
        }.getOrDefault(emptyList()).sortedBy { it.first }.also { entries = it }
    }

    /** The entry verse [id] belongs to, if any. */
    fun at(context: Context, id: Int): ChristEntry? = all(context).firstOrNull { it.contains(id) }

    /** The verses in a chapter that point to Christ (verse numbers). */
    fun verses(context: Context, book: Int, chapter: Int): Set<Int> {
        if (book >= 40) return emptySet()
        return all(context).flatMapTo(HashSet()) { it.versesIn(book, chapter) }
    }

    /** One line of christ.tsv: kind, title, ranges, reference, New Testament references, note. */
    fun parse(line: String): ChristEntry? {
        val p = line.split('\t')
        if (p.size < 6) return null
        val ranges = p[2].split(',').filter { it.isNotEmpty() }.flatMap { r -> r.split('-').map { it.toLong() } }.toLongArray()
        if (ranges.isEmpty() || ranges.size % 2 != 0) return null
        return ChristEntry(p[0] == "prophecy", p[1], ranges, p[3], p[4], p[5])
    }
}
