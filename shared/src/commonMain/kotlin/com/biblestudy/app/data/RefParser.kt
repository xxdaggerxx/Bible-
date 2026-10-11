package com.biblestudy.app.data

import com.biblestudy.app.model.BookInfo

data class Ref(val book: Int, val chapter: Int, val verse: Int?)

/** Parses typed references such as "John 3:16", "jn3.16", "1 cor 13", "Ps 23". */
object RefParser {
    // Common abbreviations, by book number (1 = Genesis ... 66 = Revelation).
    private val extra: Map<String, Int> = buildMap {
        fun a(book: Int, vararg keys: String) = keys.forEach { put(it, book) }
        a(1, "gen", "ge", "gn"); a(2, "ex", "exo", "exod"); a(3, "lev", "lv"); a(4, "num", "nm", "nu")
        a(5, "deut", "dt", "de"); a(6, "josh", "jos"); a(7, "judg", "jdg", "jg"); a(8, "ru", "rth")
        a(9, "1sam", "1sa", "1sm"); a(10, "2sam", "2sa", "2sm"); a(11, "1kgs", "1ki", "1kg", "1kings")
        a(12, "2kgs", "2ki", "2kg", "2kings"); a(13, "1chr", "1ch", "1chron"); a(14, "2chr", "2ch", "2chron")
        a(15, "ezr"); a(16, "neh", "ne"); a(17, "est", "esth"); a(18, "jb")
        a(19, "ps", "psa", "psalm", "pss", "psm"); a(20, "pr", "prov", "prv", "pro")
        a(21, "ecc", "eccl", "ec", "qoh"); a(22, "song", "sos", "sng", "ss", "songofsongs", "canticles")
        a(23, "isa", "is"); a(24, "jer", "je"); a(25, "lam", "la"); a(26, "ezek", "eze", "ezk")
        a(27, "dan", "dn", "da"); a(28, "hos", "ho"); a(29, "jl", "joel"); a(30, "am", "amo")
        a(31, "ob", "obad", "oba"); a(32, "jon", "jnh"); a(33, "mic", "mc"); a(34, "nah", "na")
        a(35, "hab", "hb"); a(36, "zeph", "zep", "zp"); a(37, "hag", "hg"); a(38, "zech", "zec", "zc")
        a(39, "mal", "ml"); a(40, "mt", "matt", "mat"); a(41, "mk", "mrk", "mar", "mr"); a(42, "lk", "luk", "lu")
        a(43, "jn", "jhn", "joh"); a(44, "ac", "act"); a(45, "rom", "rm", "ro")
        a(46, "1cor", "1co"); a(47, "2cor", "2co"); a(48, "gal", "ga"); a(49, "eph", "ephes")
        a(50, "phil", "php", "pp"); a(51, "col", "co"); a(52, "1thess", "1th", "1thes")
        a(53, "2thess", "2th", "2thes"); a(54, "1tim", "1ti", "1tm"); a(55, "2tim", "2ti", "2tm")
        a(56, "tit", "ti"); a(57, "phlm", "phm", "philem", "phile"); a(58, "heb", "he")
        a(59, "jas", "jam", "jm"); a(60, "1pet", "1pe", "1pt", "1p"); a(61, "2pet", "2pe", "2pt", "2p")
        a(62, "1jn", "1jo", "1jhn"); a(63, "2jn", "2jo", "2jhn"); a(64, "3jn", "3jo", "3jhn")
        a(65, "jud", "jude", "jd"); a(66, "rev", "re", "rv", "revelation", "revelations", "apocalypse")
    }

    private val pattern = Regex("^\\s*([1-3]?\\s*[A-Za-z][A-Za-z .]*?)\\s*(\\d+)(?:\\s*[:.]\\s*(\\d+))?\\s*$")

    /** The book a name or abbreviation refers to ("Rom", "1 cor", "Song of Solomon", "Psalms"), or null. */
    fun bookId(name: String, books: List<BookInfo>): Int? {
        val key = name.lowercase()
            .replace("iii ", "3").replace("ii ", "2").replace("i ", "1")
            .replace(Regex("[\\s.]"), "")
        if (key.isEmpty()) return null
        return extra[key]
            ?: books.firstOrNull { norm(it.name) == key }?.id
            ?: books.filter { norm(it.name).startsWith(key) }.singleOrNull()?.id
    }

    fun parse(input: String, books: List<BookInfo>): Ref? {
        val m = pattern.find(input) ?: return null
        val bookId = bookId(m.groupValues[1], books) ?: return null
        val book = books[bookId - 1]
        val chapter = m.groupValues[2].toIntOrNull() ?: return null
        if (chapter < 1 || chapter > book.chapters) return null
        val verse = m.groupValues[3].toIntOrNull()
        return Ref(bookId, chapter, verse)
    }

    private fun norm(name: String) = name.lowercase().replace(" ", "")
}
