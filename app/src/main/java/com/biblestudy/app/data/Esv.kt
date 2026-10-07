package com.biblestudy.app.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The ESV, read online from Crossway's ESV API (api.esv.org) (BIB-12), as YouVersion doesn't offer it.
 *
 * Crossway's terms are stricter than YouVersion's: non-commercial use, the ESV copyright notice
 * shown, and no more than 500 verses, or half of any book (whichever is less), kept on the tablet:
 * kept so until 1.20, now only with [OnlineBible.limitPublishers] on (users bring their own keys).
 * So the ESV keeps a rolling window of the chapters read last ([OnlineBible.trim]) and can't be
 * saved for offline.
 */
object Esv {
    /** The ESV's id among online Bibles (not a YouVersion id). */
    const val ID = 1_000_001

    /** Crossway's limit on verses kept on the tablet. */
    const val MAX_VERSES = 500

    const val COPYRIGHT = "Scripture quotations are from the ESV\u00ae Bible (The Holy Bible, English Standard Version\u00ae), " +
        "\u00a9 2001 by Crossway, a publishing ministry of Good News Publishers. Used by permission. All rights reserved."

    val info = YouVersion.Info(
        ID, "ESV", "English Standard Version", (1..66).toSet(), COPYRIGHT,
        "An \u201cessentially literal\u201d, word-for-word translation (2001, revised 2016), popular in evangelical " +
            "churches and for close study. Read online from Crossway and kept on this tablet as you read.",
    )

    /** The API key: built in (BuildConfig) or typed in Settings. */
    @Volatile var key: String = ""

    /** Fetches a URL: the status and body. Swapped in tests. */
    @Volatile var http: (String) -> Pair<Int, String> = ::get

    private const val BASE = "https://api.esv.org/v3"
    private const val OPTIONS = "include-footnotes=false&include-audio-link=false&include-headings=false" +
        "&include-passage-references=false&include-short-copyright=false&include-copyright=false" +
        "&include-chapter-numbers=false&include-first-verse-numbers=true&include-book-titles=false" +
        "&include-selahs=true&include-css-link=false&inline-styles=false"

    /** A chapter as USFM (see [toUsfm]); null if the ESV doesn't have it. */
    fun chapterUsfm(book: Int, chapter: Int): String? {
        val first = BibleImport.vid(book, chapter, 1)
        val json = JSONObject(call("$BASE/passage/html/?q=$first-${first + 998}&$OPTIONS"))
        val passages = json.optJSONArray("passages") ?: return null
        if (passages.length() == 0) return null
        return toUsfm(passages.getString(0), book, chapter)
    }

    /** Verses [from] to [to] of a chapter as USFM, for verse cards (see [OnlineBible.keep]); null if none. */
    fun versesUsfm(book: Int, chapter: Int, from: Int, to: Int): String? {
        val json = JSONObject(call("$BASE/passage/html/?q=${BibleImport.vid(book, chapter, from)}-${BibleImport.vid(book, chapter, to)}&$OPTIONS"))
        val passages = json.optJSONArray("passages") ?: return null
        if (passages.length() == 0) return null
        return toUsfm(passages.getString(0), book, chapter)
    }

    /** Verses matching [query], with their text, best first: (verse id, text). */
    fun search(query: String, books: List<com.biblestudy.app.model.BookInfo>, max: Int = 100): List<Pair<Int, String>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val json = JSONObject(call("$BASE/passage/search/?q=${enc(q)}&page-size=$max"))
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.getJSONObject(i)
            val ref = RefParser.parse(r.optString("reference"), books) ?: return@mapNotNull null
            val id = BibleImport.vid(ref.book, ref.chapter, ref.verse ?: return@mapNotNull null)
            id to r.optString("content").trim()
        }
    }

    private val VERSE_ID = Regex("\\bid=\"v(\\d{8})-")

    /**
     * Turns the ESV's chapter HTML into one USFM book holding that chapter, like
     * [YouVersion.toUsfm]: verse markers (from each verse number's id), paragraph and poetry line
     * markers, and the words of Christ. Verse numbers, titles and headings are left out.
     */
    fun toUsfm(html: String, book: Int, chapter: Int): String {
        val out = StringBuilder("\\id ${YouVersion.BOOKS[book - 1]}\n\\c $chapter\n")
        val hides = ArrayList<Boolean>()
        val closers = ArrayList<String>()
        val uppers = ArrayList<Boolean>()
        var pos = 0
        fun hidden() = hides.any { it }
        fun text(raw: String) = YouVersion.decode(raw).let { if (uppers.any { u -> u }) it.uppercase() else it }
        for (m in YouVersion.TAG.findAll(html)) {
            if (!hidden()) out.append(text(html.substring(pos, m.range.first)))
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            if (name == "br") { if (!hidden()) out.append(' '); continue }
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
                name == "b" && ("verse-num" in classes || "chapter-num" in classes) -> {
                    VERSE_ID.find(attrs)?.groupValues?.get(1)?.toIntOrNull()?.let { id ->
                        if (!hidden() && id / 1000 == book * 1000 + chapter) out.append("\n\\v ").append(id % 1000).append(' ')
                    }
                    hide = true
                }
                name.matches(Regex("h\\d")) || "footnote" in classes || "audio" in classes -> hide = true
                "woc" in classes && name == "span" -> { if (!hidden()) out.append("\\wj "); close = "\\wj*" }
                "divine-name" in classes || "small-caps" in classes -> {}
                name == "span" && "line" in classes -> if (!hidden()) out.append("\n\\q1\n")
                name == "p" -> if (!hidden()) out.append("\n\\p\n")
            }
            if (attrs.trimEnd().endsWith("/")) { if (!hidden() && close.isNotEmpty()) out.append(close); continue }
            hides += hide
            closers += close
            uppers += "divine-name" in classes || "small-caps" in classes
        }
        if (!hidden() && pos < html.length) out.append(text(html.substring(pos)))
        return out.toString()
    }

    private fun call(url: String): String {
        if (key.isBlank()) throw IOException("No ESV key. Add one in Settings \u2192 Bibles.")
        var wait = 1000L
        repeat(4) {
            val (status, body) = http(url)
            when {
                status in 200..299 -> return body
                status == 429 || status >= 500 -> { Thread.sleep(wait); wait *= 2 }
                status == 401 || status == 403 -> throw YouVersion.HttpException(status, "Crossway didn't accept the ESV key")
                else -> throw YouVersion.HttpException(status, "ESV API error $status")
            }
        }
        throw IOException("The ESV API is busy; try again in a minute")
    }

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Authorization", "Token $key")
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
