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
    /** What a file holds, before it's saved: verses by id, and the names it gives itself. */
    class Parsed(val verses: Map<Int, String>, val bookNames: Map<Int, String>, val title: String?)

    private val USFM = ("GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SNG ISA JER LAM " +
        "EZK DAN HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL MAT MRK LUK JHN ACT ROM 1CO 2CO GAL EPH PHP " +
        "COL 1TH 2TH 1TI 2TI TIT PHM HEB JAS 1PE 2PE 1JN 2JN 3JN JUD REV").split(" ")
    private val OSIS = ("Gen Exod Lev Num Deut Josh Judg Ruth 1Sam 2Sam 1Kgs 2Kgs 1Chr 2Chr Ezra Neh Esth Job Ps Prov Eccl " +
        "Song Isa Jer Lam Ezek Dan Hos Joel Amos Obad Jonah Mic Nah Hab Zeph Hag Zech Mal Matt Mark Luke " +
        "John Acts Rom 1Cor 2Cor Gal Eph Phil Col 1Thess 2Thess 1Tim 2Tim Titus Phlm Heb Jas 1Pet 2Pet " +
        "1John 2John 3John Jude Rev").split(" ")

    fun vid(book: Int, chapter: Int, verse: Int) = book * 1_000_000 + chapter * 1000 + verse

    // ---------- USFM ----------

    /** One or more USFM books. Footnotes, cross-references and word attributes are left out. */
    fun parseUsfm(texts: List<String>): Parsed {
        val verses = LinkedHashMap<Int, String>()
        val names = HashMap<Int, String>()
        var title: String? = null
        for (raw in texts) {
            val id = Regex("\\\\id\\s+(\\w+)").find(raw)?.groupValues?.get(1)?.uppercase() ?: continue
            val book = USFM.indexOf(id) + 1
            if (book == 0) continue
            Regex("\\\\h\\s+([^\\n\\\\]+)").find(raw)?.let { names[book] = it.groupValues[1].trim() }
            if (title == null) title = Regex("\\\\id\\s+\\w+\\s+-?\\s*([^\\n]+)").find(raw)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
            var text = raw.replace(Regex("\\\\f\\s.*?\\\\f\\*|\\\\x\\s.*?\\\\x\\*|\\\\fe\\s.*?\\\\fe\\*", RegexOption.DOT_MATCHES_ALL), " ")
            // Headings, titles and the like aren't verse text.
            text = text.replace(Regex("\\\\(?:id|ide|h|toc\\d|mt\\d?|ms\\d?|mr|s\\d?|sr|r|d|sp|rem|cl|cp)\\b[^\\n]*"), " ")
            var chapter = 0
            var cur = 0
            val parts = Regex("(\\\\c\\s+\\d+|\\\\v\\s+\\d+[-\\d]*)").split(text)
            val marks = Regex("(\\\\c\\s+\\d+|\\\\v\\s+\\d+[-\\d]*)").findAll(text).map { it.value }.toList()
            for ((i, part) in parts.withIndex()) {
                if (i > 0) {
                    val m = marks[i - 1]
                    if (m.startsWith("\\c")) { chapter = m.filter { it.isDigit() }.toInt(); cur = 0 }
                    else cur = Regex("\\d+").find(m.substringAfter("\\v"))!!.value.toInt()
                }
                if (chapter == 0 || cur == 0) continue
                val clean = cleanUsfm(part)
                if (clean.isEmpty()) continue
                val key = vid(book, chapter, cur)
                verses[key] = ((verses[key]?.plus(" ") ?: "") + clean).trim()
            }
        }
        return Parsed(verses, names, title)
    }

    private fun cleanUsfm(s: String): String = s
        .replace(Regex("\\|[^\\\\]*?(?=\\\\\\+?\\w+\\*)"), "") // \w word|strong="H1"\w* → word
        .replace(Regex("\\\\\\+?[a-z]+\\d?\\*?"), " ")
        .replace('¶', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    // ---------- OSIS ----------

    /** OSIS XML, with verses as containers or as sID/eID milestones. Notes and titles are left out. */
    fun parseOsis(input: InputStream): Parsed {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val verses = LinkedHashMap<Int, String>()
        var title: String? = null
        var current: Int? = null
        var skip = 0
        val buf = StringBuilder()
        fun flush() {
            val c = current ?: return
            // Joining text around a removed note can leave "world , that": close such gaps.
            val t = buf.toString().replace(Regex("\\s+"), " ").replace(Regex(" ([,.;:!?])"), "$1").trim()
            if (t.isNotEmpty()) verses[c] = ((verses[c]?.plus(" ") ?: "") + t).trim()
            buf.clear()
        }
        fun idOf(osisId: String?): Int? {
            val bits = osisId?.split(' ')?.first()?.split('.') ?: return null
            if (bits.size < 3) return null
            val book = OSIS.indexOf(bits[0]) + 1
            if (book == 0) return null
            return vid(book, bits[1].toIntOrNull() ?: return null, bits[2].toIntOrNull() ?: return null)
        }
        var inTitle = false
        val verseTags = ArrayList<Boolean>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "verse" -> {
                        // A self-closing <verse sID/> or <verse eID/> is a milestone; otherwise it holds the text.
                        verseTags.add(p.isEmptyElementTag)
                        val eid = p.getAttributeValue(null, "eID")
                        if (eid != null) { flush(); current = null }
                        else { flush(); current = idOf(p.getAttributeValue(null, "osisID") ?: p.getAttributeValue(null, "sID")) }
                    }
                    "note", "rdg" -> skip++
                    "title" -> if (current == null && title == null && p.depth <= 4) inTitle = true else skip++
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "verse" -> if (verseTags.removeLastOrNull() == false) { flush(); current = null }
                    "note", "rdg" -> skip--
                    "title" -> if (inTitle) inTitle = false else skip--
                }
                XmlPullParser.TEXT -> when {
                    inTitle -> title = p.text.trim().ifEmpty { null }
                    skip == 0 && current != null -> buf.append(p.text).append(' ')
                }
            }
        }
        flush()
        return Parsed(verses, emptyMap(), title)
    }

    // ---------- saving ----------

    /**
     * Writes a version's database (same tables as the bundled ones) and adds it to the list.
     * Book names come from the file where it gives them, else the KJV's.
     */
    fun save(context: Context, parsed: Parsed, code: String, name: String, copyright: String, kjvBooks: List<com.biblestudy.app.model.BookInfo>): BibleVersion {
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
        }
        val v = BibleVersion(code, name, file.absolutePath, copyright, summary = "Imported", description = "Imported from a file on this tablet.", imported = true)
        BibleRepository.addImported(context, v)
        return v
    }

    /** A database in this app's own format: copied in as it is, after checking its tables. */
    fun saveAppDb(context: Context, input: InputStream, code: String, name: String, copyright: String): BibleVersion {
        val dir = File(context.filesDir, "bibles").apply { mkdirs() }
        val file = File(dir, "${code.lowercase()}.db")
        file.outputStream().use { input.copyTo(it) }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM verses", null).use { c -> c.moveToFirst(); require(c.getInt(0) > 0) { "No verses found" } }
            db.rawQuery("SELECT COUNT(*) FROM books", null).use { c -> c.moveToFirst(); require(c.getInt(0) > 0) { "No books found" } }
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
