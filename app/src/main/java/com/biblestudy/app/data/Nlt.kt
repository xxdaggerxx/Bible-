package com.biblestudy.app.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The NLT, read online from Tyndale's NLT API (api.nlt.to) (BIB-12), as YouVersion doesn't offer it.
 *
 * Tyndale's terms for a key: non-commercial use, at most 500 verses a request and 5,000 requests a
 * day. Kept on the tablet as it's read, like the other online Bibles (a 500-verse window until
 * 1.20: see [OnlineBible.limitPublishers]).
 */
object Nlt {
    /** The NLT's id among online Bibles (not a YouVersion id). */
    const val ID = 1_000_002

    const val COPYRIGHT = "Scripture quotations are taken from the Holy Bible, New Living Translation, copyright \u00a9 1996, 2004, 2015 " +
        "by Tyndale House Foundation. Used by permission of Tyndale House Publishers, Carol Stream, Illinois 60188. All rights reserved."

    val info = YouVersion.Info(
        ID, "NLT", "New Living Translation", (1..66).toSet(), COPYRIGHT,
        "A thought-for-thought translation (1996, revised 2015) in natural, everyday English, good for daily reading and " +
            "newcomers. Read online from Tyndale and kept on this tablet as you read.",
    )

    /** The API key: built in (BuildConfig) or typed in Settings. */
    @Volatile var key: String = ""

    /** Fetches a URL: the status and body. Swapped in tests. */
    @Volatile var http: (String) -> Pair<Int, String> = ::get

    private const val BASE = "https://api.nlt.to/api"

    /** A chapter as USFM (see [toUsfm]); null if the NLT doesn't have it. */
    fun chapterUsfm(book: Int, chapter: Int): String? {
        val html = call("$BASE/passages?ref=${BibleImport.OSIS[book - 1]}.$chapter&version=NLT&key=${enc(key)}")
        if ("verse_export" !in html) return null
        return toUsfm(html, book, chapter)
    }

    private val RESULT = Regex("<td><a[^>]*>([1-3]?[A-Za-z]+)\\.(\\d+)\\.(\\d+)</a></td>\\s*<td>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)

    /** Verses matching [query], with their text: (verse id, text). */
    fun search(query: String): List<Pair<Int, String>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val html = call("$BASE/search?text=${enc(q)}&version=NLT&key=${enc(key)}")
        return RESULT.findAll(html).mapNotNull { m ->
            val book = BibleImport.OSIS.indexOf(m.groupValues[1]) + 1
            if (book == 0) return@mapNotNull null
            val text = YouVersion.decode(m.groupValues[4].replace(Regex("<[^>]*>"), "")).replace(Regex("\\s+"), " ").trim()
            BibleImport.vid(book, m.groupValues[2].toInt(), m.groupValues[3].toInt()) to text
        }.toList()
    }

    private val VERSE = Regex("\\bvn=\"([0-9-]+)\"")
    private val CHAPTER = Regex("\\bch=\"(\\d+)\"")

    /**
     * Turns the NLT's chapter HTML into one USFM book holding that chapter, like [Esv.toUsfm]: each
     * `verse_export` element starts a verse; paragraphs and poetry lines, the words of Jesus
     * (class "red") and "LORD" in capitals are kept; footnotes, titles and subheadings are left out.
     */
    fun toUsfm(html: String, book: Int, chapter: Int): String {
        val body = html.substringAfter("id=\"bibletext\"", html)
        val out = StringBuilder("\\id ${YouVersion.BOOKS[book - 1]}\n\\c $chapter\n")
        val hides = ArrayList<Boolean>()
        val closers = ArrayList<String>()
        val uppers = ArrayList<Boolean>()
        var pos = 0
        fun hidden() = hides.any { it }
        fun text(raw: String) = YouVersion.decode(raw).let { if (uppers.any { u -> u }) it.uppercase() else it }
        for (m in YouVersion.TAG.findAll(body)) {
            if (!hidden()) out.append(text(body.substring(pos, m.range.first)))
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            if (name == "br") { if (!hidden()) out.append(' '); continue }
            if (name == "verse_export") {
                // Not nested: a verse starts here (or ends at the closing tag).
                if (!closing) {
                    val ch = CHAPTER.find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                    val vn = VERSE.find(attrs)?.groupValues?.get(1)
                    if (vn != null && (ch == null || ch == chapter)) out.append("\n\\v ").append(vn).append(' ')
                }
                continue
            }
            if (closing) {
                if (hides.isNotEmpty()) {
                    hides.removeAt(hides.lastIndex); uppers.removeAt(uppers.lastIndex)
                    val c = closers.removeAt(closers.lastIndex)
                    if (!hidden()) out.append(c)
                }
                continue
            }
            val classes = YouVersion.CLASS.find(attrs)?.groupValues?.get(1)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
            var hide = false
            var close = ""
            when {
                name.matches(Regex("h\\d")) -> hide = true
                classes.any { it == "vn" || it == "tn" || it == "a-tn" || it == "psa-title" || it.startsWith("subhead") || it == "chapter-number" } -> hide = true
                "red" in classes -> { if (!hidden()) out.append("\\wj "); close = "\\wj*" }
                name == "p" && classes.any { it.startsWith("poet") } -> if (!hidden()) out.append("\n\\q1\n")
                name == "p" -> if (!hidden()) out.append("\n\\p\n")
            }
            if (name == "a" && "a-tn" in classes) hide = true
            if (attrs.trimEnd().endsWith("/")) { if (!hidden() && close.isNotEmpty()) out.append(close); continue }
            hides += hide
            closers += close
            uppers += "sc" in classes
        }
        if (!hidden() && pos < body.length) out.append(text(body.substring(pos)))
        return out.toString()
    }

    private fun call(url: String): String {
        if (key.isBlank()) throw IOException("No NLT key. Add one in Settings \u2192 Bibles.")
        var wait = 1000L
        repeat(4) {
            val (status, body) = http(url)
            when {
                status in 200..299 -> return body
                status == 429 || status >= 500 -> { Thread.sleep(wait); wait *= 2 }
                status == 401 || status == 403 -> throw YouVersion.HttpException(status, "Tyndale didn't accept the NLT key")
                else -> throw YouVersion.HttpException(status, "NLT API error $status")
            }
        }
        throw IOException("The NLT API is busy; try again in a minute")
    }

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        return try {
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            status to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
