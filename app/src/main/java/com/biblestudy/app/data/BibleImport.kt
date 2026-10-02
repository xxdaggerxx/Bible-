package com.biblestudy.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Importing other Bible files the user has the right to use (BIB-4): USFM (one file, several, or
 * a .zip of them), OSIS XML, or a database in this app's own format. Each becomes a database
 * like the bundled ones, in app storage, and joins the version list (BIB-5).
 */
object BibleImport {
    /**
     * What a file holds, before it's saved: verses by id, and the names it gives itself. [red] holds
     * the words of Jesus as word ranges ("0-4,9-12") by verse when the file marks them ([marksRed]);
     * [paragraphs] the verses that start a paragraph or poetry line; [bridges] the last verse of
     * verses printed together ("1-2"), whose text is kept under the first.
     */
    class Parsed(
        val verses: Map<Int, String>,
        val bookNames: Map<Int, String>,
        val title: String?,
        val red: Map<Int, String> = emptyMap(),
        val marksRed: Boolean = false,
        val paragraphs: Set<Int> = emptySet(),
        val bridges: Map<Int, Int> = emptyMap(),
    )

    private val USFM = ("GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SNG ISA JER LAM " +
        "EZK DAN HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL MAT MRK LUK JHN ACT ROM 1CO 2CO GAL EPH PHP " +
        "COL 1TH 2TH 1TI 2TI TIT PHM HEB JAS 1PE 2PE 1JN 2JN 3JN JUD REV").split(" ")
    private val OSIS = ("Gen Exod Lev Num Deut Josh Judg Ruth 1Sam 2Sam 1Kgs 2Kgs 1Chr 2Chr Ezra Neh Esth Job Ps Prov Eccl " +
        "Song Isa Jer Lam Ezek Dan Hos Joel Amos Obad Jonah Mic Nah Hab Zeph Hag Zech Mal Matt Mark Luke " +
        "John Acts Rom 1Cor 2Cor Gal Eph Phil Col 1Thess 2Thess 1Tim 2Tim Titus Phlm Heb Jas 1Pet 2Pet " +
        "1John 2John 3John Jude Rev").split(" ")

    fun vid(book: Int, chapter: Int, verse: Int) = book * 1_000_000 + chapter * 1000 + verse

    // Where the words of Jesus start and end, carried through the text until the verse is split into words.
    private const val RED_ON = '\uE000'
    private const val RED_OFF = '\uE001'

    /**
     * Removes the red-letter marks from [s]: the plain text, with single spaces and none before
     * closing punctuation, and the word ranges spoken by Jesus. [startRed] is whether the verse
     * starts inside his words; the third value is whether it ends inside them.
     */
    internal fun stripRed(s: String, startRed: Boolean): Triple<String, String, Boolean> {
        var red = startRed
        val out = StringBuilder()
        val flags = ArrayList<Boolean>()
        for (ch in s) {
            when {
                ch == RED_ON -> red = true
                ch == RED_OFF -> red = false
                ch.isWhitespace() -> if (out.isNotEmpty() && out.last() != ' ') { out.append(' '); flags += red }
                else -> {
                    // "world ," left where a note or mark was taken out → "world,".
                    if (ch in ",.;:!?\u201d\u2019)" && out.isNotEmpty() && out.last() == ' ') { out.setLength(out.length - 1); flags.removeAt(flags.size - 1) }
                    out.append(ch); flags += red
                }
            }
        }
        if (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        val text = out.toString()
        val words = StudyRepository.words(text).map { flags[it.first] }
        return Triple(text, wordRanges(words), red)
    }

    /** Word-index ranges "a-b,c-d" of the words that are true. */
    internal fun wordRanges(flags: List<Boolean>): String {
        val out = ArrayList<String>()
        var start = -1
        for (i in 0..flags.size) {
            val f = i < flags.size && flags[i]
            if (f && start < 0) start = i
            if (!f && start >= 0) { out += "$start-${i - 1}"; start = -1 }
        }
        return out.joinToString(",")
    }

    // ---------- USFM ----------

    /** Paragraph and poetry markers; a verse after one starts a paragraph or line (READ-6). */
    private val PARAGRAPH = Regex("\\\\(?:p|m|pi\\d?|mi|pc|pmo|nb|li\\d?|q\\d?|qc|qm\\d?|b)(?=\\s)")

    /**
     * One or more USFM books. Footnotes, cross-references and word attributes are left out; the
     * words of Jesus (\wj), paragraph and poetry markers and joined verses ("\v 1-2") are kept.
     */
    fun parseUsfm(texts: List<String>): Parsed {
        val verses = LinkedHashMap<Int, String>()
        val names = HashMap<Int, String>()
        val red = HashMap<Int, String>()
        val paragraphs = HashSet<Int>()
        val bridges = HashMap<Int, Int>()
        var marksRed = false
        var title: String? = null
        for (raw in texts) {
            val id = Regex("\\\\id\\s+(\\w+)").find(raw)?.groupValues?.get(1)?.uppercase() ?: continue
            val book = USFM.indexOf(id) + 1
            if (book == 0) continue
            Regex("\\\\h\\s+([^\\n\\\\]+)").find(raw)?.let { names[book] = it.groupValues[1].trim() }
            if (title == null) title = Regex("\\\\id\\s+\\w+\\s+-?\\s*([^\\n]+)").find(raw)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
            if (Regex("\\\\\\+?wj\\s").containsMatchIn(raw)) marksRed = true
            var text = raw.replace(Regex("\\\\f\\s.*?\\\\f\\*|\\\\x\\s.*?\\\\x\\*|\\\\fe\\s.*?\\\\fe\\*", RegexOption.DOT_MATCHES_ALL), "")
            // Headings, titles and the like aren't verse text.
            text = text.replace(Regex("\\\\(?:id|ide|h|toc\\d|toca\\d|mt\\d?|ms\\d?|mr|s\\d?|sr|r|d|sp|rem|cl|cp|cd|sts|usfm)\\b[^\\n]*"), " ")
            var chapter = 0
            var cur = 0
            var pending = false // the next verse starts a paragraph
            var inRed = false
            val marker = Regex("(\\\\c\\s+\\d+|\\\\v\\s+\\d+[-\\d]*)")
            val parts = marker.split(text)
            val marks = marker.findAll(text).map { it.value }.toList()
            for ((i, part) in parts.withIndex()) {
                if (i > 0) {
                    val m = marks[i - 1]
                    if (m.startsWith("\\c")) { chapter = m.filter { it.isDigit() }.toInt(); cur = 0; pending = true; inRed = false }
                    else {
                        val nums = Regex("\\d+").findAll(m.substringAfter("\\v")).map { it.value.toInt() }.toList()
                        cur = nums[0]
                        if (chapter > 0) {
                            if (nums.size > 1 && nums[1] > cur) bridges[vid(book, chapter, cur)] = nums[1]
                            if (pending || part.trimStart().startsWith("\u00b6")) paragraphs += vid(book, chapter, cur)
                        }
                        pending = false
                    }
                }
                // A paragraph marker with no verse text after it starts a paragraph at the next verse.
                PARAGRAPH.findAll(part).lastOrNull()?.let { p ->
                    if (StudyRepository.words(cleanUsfm(part.substring(p.range.last + 1))).isEmpty()) pending = true
                }
                val (clean, ranges, endRed) = stripRed(cleanUsfm(part), inRed)
                inRed = endRed
                if (chapter == 0 || cur == 0 || clean.isEmpty()) continue
                val key = vid(book, chapter, cur)
                val before = verses[key]
                verses[key] = if (before == null) clean else "$before $clean"
                if (before == null && ranges.isNotEmpty() && book >= 40) red[key] = ranges
            }
        }
        return Parsed(verses, names, title, red, marksRed, paragraphs.filterTo(HashSet()) { it in verses }, bridges)
    }

    private fun cleanUsfm(s: String): String = s
        .replace(Regex("\\\\\\+?wj\\s"), RED_ON.toString())
        .replace(Regex("\\\\\\+?wj\\*"), RED_OFF.toString())
        .replace(Regex("\\|[^\\\\]*?(?=\\\\\\+?\\w+\\*)"), "") // \w word|strong="H1"\w* → word
        .replace(Regex("\\\\\\+?[a-z]+\\d?\\*"), "") // closing marks sit against the word or punctuation
        .replace(Regex("\\\\\\+?[a-z]+\\d?"), " ")
        .replace('\u00b6', ' ')

    // ---------- OSIS ----------

    /**
     * OSIS XML, with verses as containers or as sID/eID milestones. Notes and titles are left out;
     * the words of Jesus (<q who="Jesus">), paragraphs, poetry lines and joined verses are kept.
     */
    fun parseOsis(input: InputStream): Parsed {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val verses = LinkedHashMap<Int, String>()
        val red = HashMap<Int, String>()
        val paragraphs = HashSet<Int>()
        val bridges = HashMap<Int, Int>()
        var marksRed = false
        var title: String? = null
        var current: Int? = null
        var skip = 0
        var inRed = false
        var verseStartRed = false
        var pending = false
        var lastChapter = -1
        val buf = StringBuilder()
        fun flush() {
            val c = current ?: return
            val (t, ranges, _) = stripRed(buf.toString(), verseStartRed)
            if (t.isNotEmpty()) {
                val before = verses[c]
                verses[c] = if (before == null) t else "$before $t"
                if (before == null && ranges.isNotEmpty() && c >= 40_000_000) red[c] = ranges
            }
            buf.clear()
        }
        fun idOf(osisId: String?): Int? {
            val bits = osisId?.trim()?.split(' ')?.first()?.split('.') ?: return null
            if (bits.size < 3) return null
            val book = OSIS.indexOf(bits[0]) + 1
            if (book == 0) return null
            return vid(book, bits[1].toIntOrNull() ?: return null, bits[2].toIntOrNull() ?: return null)
        }
        fun start(osisId: String?) {
            flush()
            val id = idOf(osisId)
            current = id
            verseStartRed = inRed
            if (id == null) return
            // "John.3.16 John.3.17": verses printed together.
            val last = osisId!!.trim().split(Regex("\\s+")).last().split('.').getOrNull(2)?.toIntOrNull()
            if (last != null && last > id % 1000) bridges[id] = last
            if (pending || id / 1000 != lastChapter) paragraphs += id
            pending = false
            lastChapter = id / 1000
        }
        var inTitle = false
        val verseTags = ArrayList<Boolean>()
        val jesusMilestones = HashSet<String>()
        val quotes = ArrayList<Boolean>() // for each open <q>: does it hold the words of Jesus
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "verse" -> {
                        // A self-closing <verse sID/> or <verse eID/> is a milestone; otherwise it holds the text.
                        verseTags.add(p.isEmptyElementTag)
                        val eid = p.getAttributeValue(null, "eID")
                        if (eid != null) { flush(); current = null }
                        else start(p.getAttributeValue(null, "osisID") ?: p.getAttributeValue(null, "sID"))
                    }
                    "q" -> {
                        val jesus = p.getAttributeValue(null, "who")?.equals("Jesus", ignoreCase = true) == true
                        val sid = p.getAttributeValue(null, "sID")
                        val eid = p.getAttributeValue(null, "eID")
                        if (jesus) marksRed = true
                        if (p.isEmptyElementTag) {
                            if (jesus && sid != null) { jesusMilestones += sid; inRed = true; buf.append(RED_ON) }
                            if (eid != null && jesusMilestones.remove(eid)) { inRed = false; buf.append(RED_OFF) }
                            quotes.add(false)
                        } else {
                            if (jesus) { inRed = true; buf.append(RED_ON) }
                            quotes.add(jesus)
                        }
                    }
                    "p", "lg", "l" -> if (p.getAttributeValue(null, "eID") == null) pending = true
                    "milestone" -> if (p.getAttributeValue(null, "type")?.let { it == "x-p" || it == "pilcrow" || it == "x-paragraph" } == true) pending = true
                    "note", "rdg" -> skip++
                    "title" -> if (current == null && title == null && p.depth <= 4) inTitle = true else skip++
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "verse" -> if (verseTags.removeLastOrNull() == false) { flush(); current = null }
                    "q" -> if (quotes.removeLastOrNull() == true) { inRed = false; buf.append(RED_OFF) }
                    "note", "rdg" -> skip--
                    "title" -> if (inTitle) inTitle = false else skip--
                }
                XmlPullParser.TEXT -> when {
                    inTitle -> title = p.text.trim().ifEmpty { null }
                    skip == 0 && current != null -> {
                        buf.append(p.text).append(' ')
                        // A paragraph that starts partway through a verse doesn't start the next one.
                        if (p.text.isNotBlank()) pending = false
                    }
                }
            }
        }
        flush()
        return Parsed(verses, emptyMap(), title, red, marksRed, paragraphs.filterTo(HashSet()) { it in verses }, bridges)
    }

    // ---------- saving ----------

    /**
     * Writes a version's database (same tables as the bundled ones) and adds it to the list.
     * Book names come from the file where it gives them, else the KJV's. [study] then adds word
     * tags, the words of Jesus and paragraphs (see [ImportStudy]).
     */
    fun save(
        context: Context, parsed: Parsed, code: String, name: String, copyright: String, kjvBooks: List<com.biblestudy.app.model.BookInfo>,
        study: (SQLiteDatabase, Parsed?) -> Unit = { _, _ -> },
    ): BibleVersion {
        require(parsed.verses.isNotEmpty()) { "No verses found" }
        val dir = File(context.filesDir, "bibles").apply { mkdirs() }
        val file = File(dir, "${code.lowercase()}.db")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
            db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY, name TEXT NOT NULL, osis TEXT NOT NULL, chapters INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE verses(id INTEGER PRIMARY KEY, book INTEGER NOT NULL, chapter INTEGER NOT NULL, verse INTEGER NOT NULL, text TEXT NOT NULL)")
            db.execSQL("CREATE INDEX verses_bc ON verses(book, chapter)")
            db.execSQL("CREATE VIRTUAL TABLE verses_fts USING fts4(text, content=\"verses\")")
            db.execSQL("CREATE TABLE xrefs(from_id INTEGER NOT NULL, to_start INTEGER NOT NULL, to_end INTEGER NOT NULL, votes INTEGER NOT NULL)")
            db.beginTransaction()
            try {
                for ((k, v) in listOf("code" to code, "name" to name, "copyright" to copyright, "schema" to "1")) {
                    db.execSQL("INSERT INTO meta VALUES(?, ?)", arrayOf(k, v))
                }
                // Every Bible book, with the chapter count the KJV has (so navigation works the
                // same); a book the file leaves out simply shows no text.
                for (b in kjvBooks) {
                    db.execSQL("INSERT INTO books VALUES(?, ?, ?, ?)", arrayOf<Any>(b.id, parsed.bookNames[b.id] ?: b.name, b.osis, b.chapters))
                }
                for ((id, text) in parsed.verses.toSortedMap()) {
                    db.execSQL(
                        "INSERT INTO verses VALUES(?, ?, ?, ?, ?)",
                        arrayOf<Any>(id, id / 1_000_000, (id / 1000) % 1000, id % 1000, text),
                    )
                }
                db.execSQL("INSERT INTO verses_fts(verses_fts) VALUES('rebuild')")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            study(db, parsed)
        }
        val v = BibleVersion(code, name, file.absolutePath, copyright, summary = "Imported", description = "Imported from a file on this tablet.", imported = true)
        BibleRepository.addImported(context, v)
        return v
    }

    /**
     * A database in this app's own format: copied in as it is, after checking its tables. Word
     * tags and the rest are added by [study] if it doesn't have them yet.
     */
    fun saveAppDb(context: Context, input: InputStream, code: String, name: String, copyright: String, study: (SQLiteDatabase, Parsed?) -> Unit = { _, _ -> }): BibleVersion {
        val dir = File(context.filesDir, "bibles").apply { mkdirs() }
        val file = File(dir, "${code.lowercase()}.db")
        file.outputStream().use { input.copyTo(it) }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM verses", null).use { c -> c.moveToFirst(); require(c.getInt(0) > 0) { "No verses found" } }
            db.rawQuery("SELECT COUNT(*) FROM books", null).use { c -> c.moveToFirst(); require(c.getInt(0) > 0) { "No books found" } }
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // Its tables hold the code it was made with; they're rebuilt under this one.
            if (!ImportStudy.isBuilt(db) || StudyRepository.hasTable(db, "tags") && db.rawQuery("SELECT 1 FROM tags WHERE version <> ? LIMIT 1", arrayOf(code)).use { it.moveToFirst() }) study(db, null)
        }
        val v = BibleVersion(code, name, file.absolutePath, copyright, summary = "Imported", description = "Imported from a file on this tablet.", imported = true)
        BibleRepository.addImported(context, v)
        return v
    }

    /** Reads what was picked: a .zip of USFM, several USFM files, an OSIS .xml, or a .db. */
    fun parse(names: List<String>, open: (Int) -> InputStream): Parsed {
        val usfm = ArrayList<String>()
        for ((i, n) in names.withIndex()) {
            val lower = n.lowercase()
            when {
                lower.endsWith(".zip") -> ZipInputStream(open(i)).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val en = e.name.lowercase()
                        if (en.endsWith(".usfm") || en.endsWith(".sfm") || en.endsWith(".ptx")) usfm += z.readBytes().toString(Charsets.UTF_8)
                        if (en.endsWith(".xml")) return parseOsis(z)
                    }
                }
                lower.endsWith(".xml") || lower.endsWith(".osis") -> return open(i).use { parseOsis(it) }
                else -> usfm += open(i).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }
        return parseUsfm(usfm)
    }

    fun toJson(v: BibleVersion): JSONObject = JSONObject().put("code", v.code).put("name", v.name).put("file", v.asset).put("copyright", v.copyright)

    fun fromJson(a: JSONArray): List<BibleVersion> = List(a.length()) { i ->
        a.getJSONObject(i).let {
            BibleVersion(it.getString("code"), it.getString("name"), it.getString("file"), it.getString("copyright"),
                summary = "Imported", description = "Imported from a file on this tablet.", imported = true)
        }
    }
}
