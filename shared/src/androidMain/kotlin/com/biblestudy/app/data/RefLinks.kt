package com.biblestudy.app.data

import com.biblestudy.app.model.BookInfo
import com.biblestudy.app.model.VerseId

/** A passage from [chapter]:[verse] to [endChapter]:[endVerse] of one book. A whole chapter ends at verse 999. */
data class Passage(val book: Int, val chapter: Int, val verse: Int, val endChapter: Int, val endVerse: Int) {
    val startId: Int get() = VerseId.of(book, chapter, verse)
    val endId: Int get() = VerseId.of(book, endChapter, endVerse)
}

/** A reference found in some text: characters [start, end) of that text point to [passage]. */
data class RefLink(val start: Int, val end: Int, val passage: Passage)

/**
 * Finds Bible references in text so they can be shown as links (LINK-1, LINK-4).
 *
 * [parseList] reads reference lists such as the parallel passages under a section heading:
 * "(Matthew 3:13–17; Mark 1:9–11; Luke 3:21–22)". [find] picks references out of free text such
 * as a typed note: "see Rom 8:28 and 1 Cor 13:4-7".
 */
object RefLinks {
    /** A book name: "Mark", "1 Cor.", "Song of Solomon". */
    private const val BOOK = """(?:[1-3]\s*)?[A-Za-z][A-Za-z]*(?:\s+of\s+[A-Za-z]+)?\.?"""
    private const val NUMS = """(\d{1,3})(?:\s*:\s*(\d{1,3}))?(?:\s*[-–—]\s*(\d{1,3})(?:\s*:\s*(\d{1,3}))?)?"""

    /** One item of a list: an optional book, then chapter[:verse][–[chapter:]verse]. */
    private val listItem = Regex("""($BOOK)?\s*$NUMS""")

    /** A reference in free text: the book is required. */
    private val freeItem = Regex("""(?<![A-Za-z0-9])($BOOK)\s*$NUMS(?![0-9])""")

    /** Parses a list of references separated by ';' (and ',' for extra verses), carrying the book forward. */
    fun parseList(text: String, books: List<BookInfo>): List<RefLink> {
        val out = ArrayList<RefLink>()
        var book: Int? = null
        var chapter: Int? = null
        var hadVerse = false
        var pos = 0
        for (part in text.split(';', ',')) {
            val m = listItem.find(part)
            if (m != null && part.substring(0, m.range.first).all { !it.isLetterOrDigit() }) {
                val name = m.groupValues[1].trim()
                if (name.isNotEmpty()) {
                    book = RefParser.bookId(name, books)
                    chapter = null
                    hadVerse = false
                }
                val b = book
                val p = if (b == null) null else passage(b, m, books, chapter, hadVerse)
                if (p != null) {
                    val first = m.groups[1]?.range?.first ?: m.groups[2]!!.range.first // no leading space
                    out += RefLink(pos + first, pos + m.range.last + 1, p)
                    chapter = p.endChapter
                    hadVerse = m.groupValues[3].isNotEmpty() || (name.isEmpty() && hadVerse)
                }
            }
            pos += part.length + 1
        }
        return out
    }

    /**
     * References in free text. The book must be named; a lower-case book name only counts when a
     * verse follows ("jn 3:16"), so ordinary words and numbers aren't mistaken for references.
     */
    fun find(text: String, books: List<BookInfo>): List<RefLink> {
        val out = ArrayList<RefLink>()
        var from = 0
        while (true) {
            val m = freeItem.find(text, from) ?: break
            val link = linkOf(m, books)
            if (link != null) {
                out += link
                from = m.range.last + 1
            } else {
                // Not a reference ("and 1" in "and 1 Cor 13"): try again from the next character.
                from = m.range.first + 1
            }
        }
        return out
    }

    private fun linkOf(m: MatchResult, books: List<BookInfo>): RefLink? {
        val name = m.groupValues[1].trim()
        if (!name.first().isUpperCase() && !name.first().isDigit() && m.groupValues[3].isEmpty()) return null
        val b = RefParser.bookId(name, books) ?: return null
        return passage(b, m, books, chapter = null, prevHadVerse = false)?.let { RefLink(m.range.first, m.range.last + 1, it) }
    }

    /**
     * Groups: 2 = first number, 3 = verse after ':', 4 = number after the dash, 5 = verse after a
     * second ':'. A bare number following an item that had verses (e.g. the "23" in
     * "Luke 3:21–22, 23") is another verse of the same chapter.
     */
    private fun passage(book: Int, m: MatchResult, books: List<BookInfo>, chapter: Int?, prevHadVerse: Boolean): Passage? {
        val info = books.getOrNull(book - 1) ?: return null
        val n1 = m.groupValues[2].toInt()
        val v1 = m.groupValues[3].toIntOrNull()
        val n2 = m.groupValues[4].toIntOrNull()
        val v2 = m.groupValues[5].toIntOrNull()
        val p = when {
            v1 == null && prevHadVerse && chapter != null && m.groupValues[1].isBlank() ->
                Passage(book, chapter, n1, chapter, n2 ?: n1) // verses continuing the previous chapter
            v1 == null -> Passage(book, n1, 1, n2 ?: n1, 999) // chapter or chapter range
            n2 == null -> Passage(book, n1, v1, n1, v1) // single verse
            v2 == null -> Passage(book, n1, v1, n1, n2) // verses within a chapter
            else -> Passage(book, n1, v1, n2, v2) // across chapters
        }
        val ok = p.chapter in 1..info.chapters && p.endChapter in p.chapter..info.chapters && p.verse >= 1 &&
            (p.endChapter > p.chapter || p.endVerse >= p.verse)
        return if (ok) p else null
    }
}
