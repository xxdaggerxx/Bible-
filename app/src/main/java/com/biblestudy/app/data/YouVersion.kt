package com.biblestudy.app.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The YouVersion Platform Bible API (BIB-12): Bibles we may not bundle (NIV, NASB, Amplified…),
 * read online and kept on the tablet as they're read (see [OnlineBible]).
 *
 * Requests carry the app key in the `X-YVP-App-Key` header. Chapters come as HTML, which is turned
 * into USFM ([toUsfm]) so the same reader as imported Bibles ([BibleImport.parseUsfm]) gets the
 * verses, the words of Jesus and the paragraphs.
 */
object YouVersion {
    /** A Bible the key may read. [books] are book ids 1–66 it has; [copyright] must be shown with its text. */
    data class Info(
        val id: Int,
        val abbreviation: String,
        val title: String,
        val books: Set<Int>,
        val copyright: String,
        val about: String,
    ) {
        /** The short code used in the app: "NIV11" → "NIV", "enggnv" → "GNV". */
        val code: String get() = codeOf(abbreviation)
    }

    class HttpException(val status: Int, message: String) : IOException(message)

    const val BASE = "https://api.youversion.com/v1"

    /** The app key: built in (BuildConfig, from youversion.properties when built), or one typed in Settings. */
    @Volatile var key: String = ""

    /** Fetches a URL: the status and body. Swapped in tests. */
    @Volatile var http: (String) -> Pair<Int, String> = ::get

    /** USFM book codes in Bible order (book id = index + 1). */
    val BOOKS = ("GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SNG ISA JER LAM " +
        "EZK DAN HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL MAT MRK LUK JHN ACT ROM 1CO 2CO GAL EPH PHP " +
        "COL 1TH 2TH 1TI 2TI TIT PHM HEB JAS 1PE 2PE 1JN 2JN 3JN JUD REV").split(" ")

    fun codeOf(abbreviation: String): String {
        val a = abbreviation.removePrefix("eng").uppercase()
        return a.removeSuffix("11").ifEmpty { a }
    }

    /** The English Bibles the key may read (those with all or part of the 66 books). */
    fun bibles(): List<Info> {
        val out = ArrayList<Info>()
        var token: String? = null
        do {
            val url = "$BASE/bibles?language_ranges[]=en&page_size=99" + (token?.let { "&page_token=" + enc(it) } ?: "")
            val json = JSONObject(call(url))
            val data = json.optJSONArray("data") ?: break
            for (i in 0 until data.length()) info(data.getJSONObject(i))?.let { out += it }
            token = json.optString("next_page_token").takeIf { it.isNotEmpty() && it != "null" }
        } while (token != null)
        return out
    }

    /** One Bible, with its copyright (the list leaves it out). */
    fun bible(id: Int): Info? = info(JSONObject(call("$BASE/bibles/$id")))

    private fun info(o: JSONObject): Info? {
        val books = o.optJSONArray("books") ?: return null
        val ids = (0 until books.length()).mapNotNullTo(HashSet()) { i -> (BOOKS.indexOf(books.getString(i)) + 1).takeIf { it > 0 } }
        if (ids.isEmpty()) return null
        val copyright = o.optString("copyright").takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("promotional_content").takeIf { it.isNotBlank() && it != "null" }
            ?: ""
        return Info(
            o.getInt("id"), o.optString("abbreviation"), o.optString("title"), ids,
            copyright.trim(), o.optString("info").takeIf { it != "null" }.orEmpty().trim(),
        )
    }

    /** A chapter as HTML; null if the Bible doesn't have it (a 404). */
    fun chapterHtml(id: Int, book: Int, chapter: Int): String? {
        val url = "$BASE/bibles/$id/passages/${BOOKS[book - 1]}.$chapter?format=html"
        return try {
            JSONObject(call(url)).optString("content")
        } catch (e: HttpException) {
            if (e.status == 404) null else throw e
        }
    }

    /** Verse ids matching [query] in Bible [id], best first, at most [max]. */
    fun searchVerses(id: Int, query: String, max: Int = 99): List<Int> {
        val q = query.trim().take(100)
        if (q.isEmpty()) return emptyList()
        val json = JSONObject(call("$BASE/search-verses?query=${enc(q)}&bible_id=$id&page_size=${max.coerceIn(1, 99)}"))
        val verses = json.optJSONArray("verses") ?: return emptyList()
        return (0 until verses.length()).mapNotNull { i -> verseId(verses.getJSONObject(i).optString("reference")) }
    }

    /** "JHN.3.16" (or "JHN.3.16-17") → the app's verse id. */
    fun verseId(ref: String): Int? {
        val p = ref.substringBefore('-').split('.')
        if (p.size < 3) return null
        val book = BOOKS.indexOf(p[0].uppercase()) + 1
        val c = p[1].toIntOrNull() ?: return null
        val v = p[2].toIntOrNull() ?: return null
        return if (book > 0) BibleImport.vid(book, c, v) else null
    }

    private fun call(url: String): String {
        if (key.isBlank()) throw IOException("No YouVersion key. Add one in Settings → Bibles.")
        var wait = 1000L
        repeat(4) {
            val (status, body) = http(url)
            when {
                status in 200..299 -> return body
                status == 429 || status >= 500 -> { Thread.sleep(wait); wait *= 2 } // busy: wait and try again
                status == 401 || status == 403 -> throw HttpException(status, "YouVersion didn't accept the key")
                else -> throw HttpException(status, "YouVersion error $status")
            }
        }
        throw IOException("YouVersion is busy; try again in a minute")
    }

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("X-YVP-App-Key", key)
        c.setRequestProperty("Accept", "application/json")
        return try {
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            status to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- HTML → USFM ----------

    internal val TAG = Regex("<(/?)(\\w+)([^>]*)>")
    internal val CLASS = Regex("class=\"([^\"]*)\"")
    private val VERSE = Regex("\\bv=\"([^\"]+)\"")

    /** Classes of blocks that aren't verse text: headings, parallel references, titles. */
    private val SKIP_BLOCKS = Regex("^(yv-h|s\\d?|ms\\d?|mr|r|sr|d|sp|mt\\d?|cl|qa)$")

    /** Paragraph and poetry blocks, each starting a paragraph or line (READ-6). */
    private val PARAGRAPH_BLOCKS = Regex("^(p|m|pi\\d?|mi|pc|pmo|nb|li\\d?|q\\d?|qc|qm\\d?|b)$")

    /**
     * Turns a chapter's HTML into one USFM book holding that chapter: verse markers (`\v`),
     * paragraph and poetry markers (`\p`, `\q1`…), and the words of Jesus (`\wj … \wj*`). Notes,
     * verse labels and headings are left out.
     */
    fun toUsfm(html: String, book: Int, chapter: Int): String {
        val out = StringBuilder("\\id ${BOOKS[book - 1]}\n\\c $chapter\n")
        // Open elements: whether each one hides its text, and what to write when it closes.
        val hides = ArrayList<Boolean>()
        val closers = ArrayList<String>()
        // "The LORD" is set in small capitals (class "nd"); written in capitals, as in the other versions.
        val uppers = ArrayList<Boolean>()
        var pos = 0
        fun hidden() = hides.any { it }
        fun text(raw: String) = decode(raw).let { if (uppers.any { u -> u }) it.uppercase() else it }
        for (m in TAG.findAll(html)) {
            if (!hidden()) out.append(text(html.substring(pos, m.range.first)))
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            if (name == "br") { if (!hidden()) out.append(' '); continue }
            if (closing) {
                if (hides.isNotEmpty()) { hides.removeAt(hides.lastIndex); uppers.removeAt(uppers.lastIndex); val c = closers.removeAt(closers.lastIndex); if (!hidden()) out.append(c) }
                continue
            }
            val classes = CLASS.find(attrs)?.groupValues?.get(1)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
            var hide = false
            var close = ""
            when {
                "yv-v" in classes -> {
                    VERSE.find(attrs)?.groupValues?.get(1)?.let { v -> if (!hidden()) out.append("\n\\v ").append(v.replace(Regex("[^0-9-]"), "")).append(' ') }
                }
                "yv-vlbl" in classes || "yv-n" in classes || classes.any { it == "f" || it == "x" } -> hide = true
                classes.any { SKIP_BLOCKS.matches(it) } -> hide = true
                "wj" in classes -> { if (!hidden()) out.append("\\wj "); close = "\\wj*" }
                name == "div" && classes.any { PARAGRAPH_BLOCKS.matches(it) } -> {
                    val c = classes.first { PARAGRAPH_BLOCKS.matches(it) }
                    if (!hidden()) out.append("\n\\").append(if (c.startsWith("q") || c.startsWith("li")) "q1" else "p").append('\n')
                }
            }
            if (attrs.trimEnd().endsWith("/")) { if (!hidden() && close.isNotEmpty()) out.append(close); continue } // <span ... />
            hides += hide
            closers += close
            uppers += "nd" in classes
        }
        if (!hidden() && pos < html.length) out.append(text(html.substring(pos)))
        return out.toString()
    }

    internal fun decode(s: String): String = s
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace('\\', ' ')
}
