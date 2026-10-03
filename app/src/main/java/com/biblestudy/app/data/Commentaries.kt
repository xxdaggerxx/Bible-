package com.biblestudy.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * One of the bundled commentaries (STD-17), with a short introduction to it (STD-19).
 *
 * @param id the asset name (assets/commentaries/<id>.db.xz); "mhcc" is Matthew Henry's Concise,
 *   which lives in study.db.
 * @param covers "Whole Bible", "Old Testament" or "New Testament", or a book.
 * @param testament null for the whole Bible, else 1 (OT) or 2 (NT): the books it has notes on.
 */
data class CommentaryInfo(
    val id: String,
    val name: String,
    val short: String,
    val author: String,
    val years: String,
    val covers: String,
    val testament: Int?,
    val about: List<Pair<String, String>>,
) {
    /** Whether it comments on [book] at all. */
    fun covers(book: Int): Boolean = when (testament) {
        null -> true
        1 -> book in 1..39
        2 -> book in 40..66
        else -> book == 19 // Psalms only (Treasury of David)
    }
}

/**
 * The commentaries that come with the app (STD-17). They are public domain, and all hold the
 * traditional view. Except the Concise (in study.db), each is stored tightly packed (xz) and
 * unpacked into app storage the first time it is opened, which takes a few seconds.
 */
object Commentaries {
    const val CONCISE = "mhcc"

    val all = listOf(
        CommentaryInfo(
            CONCISE, "Matthew Henry's Concise Commentary", "Matthew Henry (Concise)", "Matthew Henry", "1706–1721 (abridged 1865)", "Whole Bible", null,
            listOf(
                "Who" to "Matthew Henry (1662–1714), an English Presbyterian minister in Chester and later Hackney, London, son of the Puritan Philip Henry.",
                "What" to "A shortened form of his Exposition of the Old and New Testaments, made after his death. It gives the plain meaning of each passage and then draws out lessons for faith and life.",
                "Best for" to "A quick, warm reading of a passage for devotion, prayer and teaching.",
                "Approach" to "Reformed and Puritan; it takes the Bible as God's word throughout.",
            ),
        ),
        CommentaryInfo(
            "mhc", "Matthew Henry's Complete Commentary", "Matthew Henry (Complete)", "Matthew Henry", "1706–1721", "Whole Bible", null,
            listOf(
                "Who" to "Matthew Henry (1662–1714), an English Presbyterian minister in Chester and later Hackney, London. He finished Genesis to Acts himself; after his death, thirteen fellow ministers completed Romans to Revelation from his notes.",
                "What" to "The full Exposition: each chapter outlined, then explained section by section, with practical and devotional application. Spurgeon said every minister should read it through at least once.",
                "Best for" to "Thorough devotional study and sermon preparation; it comments on paragraphs rather than single verses.",
                "Approach" to "Reformed and Puritan, warm and pastoral.",
            ),
        ),
        CommentaryInfo(
            "jfb", "Jamieson, Fausset and Brown Commentary", "Jamieson-Fausset-Brown", "Robert Jamieson, A. R. Fausset and David Brown", "1871", "Whole Bible", null,
            listOf(
                "Who" to "Three ministers: Robert Jamieson (Church of Scotland, Glasgow), Andrew Robert Fausset (Church of Ireland and England, York) and David Brown (Free Church of Scotland, Aberdeen).",
                "What" to "The Commentary Critical and Explanatory on the Whole Bible: short, careful notes verse by verse, on the meaning of words, history and customs, and how passages connect.",
                "Best for" to "A clear, scholarly note on the verse you're reading, without too much length.",
                "Approach" to "Evangelical and conservative; it defends the traditional authorship and the truth of Scripture.",
            ),
        ),
        CommentaryInfo(
            "wesley", "John Wesley's Explanatory Notes", "Wesley's Notes", "John Wesley", "1754–1765", "Whole Bible", null,
            listOf(
                "Who" to "John Wesley (1703–1791), Anglican minister and founder of the Methodist movement.",
                "What" to "Brief notes on key verses, written for ordinary readers. The New Testament notes (1754) drew on Bengel's Gnomon; the Old Testament followed in 1765, drawing on Matthew Henry and Matthew Poole.",
                "Best for" to "A short, plain explanation of a verse, with a pastoral turn.",
                "Approach" to "Methodist (Arminian): it stresses God's love for all and holiness of life.",
            ),
        ),
        CommentaryInfo(
            "geneva", "Geneva Bible Notes", "Geneva Notes", "The Geneva translators", "1560–1599", "Whole Bible", null,
            listOf(
                "Who" to "English Protestant exiles in Geneva, led by William Whittingham, in the circle of John Calvin and Theodore Beza.",
                "What" to "The margin notes of the Geneva Bible (1599 edition), the Bible of Shakespeare, the Puritans and the Pilgrim Fathers. The verse is shown with letters marking the notes that follow it.",
                "Best for" to "Seeing how the Reformers read a verse, in a line or two.",
                "Approach" to "Reformed (Calvinist).",
            ),
        ),
        CommentaryInfo(
            "barnes", "Albert Barnes' Notes on the New Testament", "Barnes' Notes", "Albert Barnes", "1832–1851", "New Testament", 2,
            listOf(
                "Who" to "Albert Barnes (1798–1870), Presbyterian minister of First Presbyterian Church, Philadelphia.",
                "What" to "Notes, Explanatory and Practical: detailed verse-by-verse notes written for Sunday school teachers and families, on words, history, customs and doctrine. This edition covers the New Testament.",
                "Best for" to "Detailed explanation of a New Testament verse in plain English.",
                "Approach" to "Evangelical Presbyterian (New School).",
            ),
        ),
        CommentaryInfo(
            "clarke", "Adam Clarke's Commentary", "Adam Clarke", "Adam Clarke", "1810–1826", "Whole Bible", null,
            listOf(
                "Who" to "Adam Clarke (about 1760–1832), Methodist minister and scholar, three times president of the Methodist Conference. It took him some forty years.",
                "What" to "A large commentary with critical notes: strong on the Hebrew and Greek, ancient history, customs and other old translations.",
                "Best for" to "Background, languages and history behind a verse.",
                "Approach" to "Methodist (Wesleyan, Arminian).",
            ),
        ),
        CommentaryInfo(
            "kd", "Keil and Delitzsch Commentary on the Old Testament", "Keil & Delitzsch", "Carl Friedrich Keil and Franz Delitzsch", "1861–1878", "Old Testament", 1,
            listOf(
                "Who" to "Carl Friedrich Keil (1807–1888) and Franz Delitzsch (1813–1890), German Lutheran Old Testament scholars.",
                "What" to "The classic scholarly commentary on the Hebrew text of the Old Testament, book by book, translated into English soon after it appeared.",
                "Best for" to "Deep study of the Hebrew, grammar and history of an Old Testament passage.",
                "Approach" to "Confessional Lutheran; written to defend the Old Testament as God's revelation against the rationalism of its day.",
            ),
        ),
        CommentaryInfo(
            "rwp", "Robertson's Word Pictures in the New Testament", "Robertson's Word Pictures", "A. T. Robertson", "1930–1933", "New Testament", 2,
            listOf(
                "Who" to "Archibald Thomas Robertson (1863–1934), professor of New Testament at the Southern Baptist Theological Seminary, Louisville, and a great Greek grammarian.",
                "What" to "Notes on the Greek words and grammar of the New Testament, verse by verse, explained so that readers without much Greek can follow.",
                "Best for" to "What the Greek words and tenses add to a verse.",
                "Approach" to "Baptist and evangelical.",
            ),
        ),
        CommentaryInfo(
            "calvin", "Calvin's Commentaries", "Calvin", "John Calvin", "1540–1564", "Most of the Bible", null,
            listOf(
                "Who" to "John Calvin (1509–1564), French Reformer and pastor in Geneva.",
                "What" to "His commentaries and lectures on most books of the Bible, in the 19th-century English translation of the Calvin Translation Society. Clear and to the point, following the plain sense of the text.",
                "Best for" to "The Reformation's reading of a passage, from its leading teacher.",
                "Approach" to "Reformed.",
            ),
        ),
        CommentaryInfo(
            "tdavid", "Spurgeon's Treasury of David", "Treasury of David", "Charles Haddon Spurgeon", "1869–1885", "Psalms", 3,
            listOf(
                "Who" to "Charles Haddon Spurgeon (1834–1892), Baptist pastor of the Metropolitan Tabernacle, London.",
                "What" to "His work on the Psalms, which took twenty years: an overview of each psalm, his exposition, and gathered comments from many older writers, with hints for preachers.",
                "Best for" to "Reading the Psalms devotionally and preaching from them. Each psalm is one long note.",
                "Approach" to "Reformed Baptist.",
            ),
        ),
    )

    fun info(id: String): CommentaryInfo = all.firstOrNull { it.id == id } ?: all.first()

    /** Where a packed commentary is unpacked; the version changes when the bundled data does. */
    private fun file(context: Context, id: String) = File(context.filesDir, "commentaries/$id-v1.db")

    fun isUnpacked(context: Context, id: String) = id == CONCISE || file(context, id).exists()

    private val open = HashMap<String, SQLiteDatabase>()

    /** Opens commentary [id], unpacking it first if needed (slow the first time: call off the main thread). */
    @Synchronized
    fun open(context: Context, id: String): SQLiteDatabase {
        open[id]?.let { if (it.isOpen) return it }
        val f = file(context, id)
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.parentFile?.listFiles()?.filter { it.name.startsWith("$id-") && it != f }?.forEach { it.delete() }
            val tmp = File(f.path + ".part")
            context.assets.open("commentaries/$id.db.xz").use { raw ->
                org.tukaani.xz.XZInputStream(raw.buffered(1 shl 16)).use { xz -> tmp.outputStream().use { xz.copyTo(it, 1 shl 16) } }
            }
            tmp.renameTo(f)
        }
        return SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).also { open[id] = it }
    }

    /**
     * The notes on one chapter: its book's introduction (in chapter 1), the chapter's introduction,
     * then the notes in order, including any that run into it from the chapter before.
     */
    fun chapter(context: Context, id: String, book: Int, chapter: Int): List<CommentarySection> {
        val db = open(context, id)
        val lo = book * 1_000_000 + chapter * 1000
        val rows = db.rawQuery(
            "SELECT start, end, body FROM entries WHERE start <= ? AND end >= ? AND start >= ? ORDER BY start",
            arrayOf((lo + 999).toString(), lo.toString(), (book * 1_000_000 + (chapter - 1).coerceAtLeast(0) * 1000).toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(CommentarySection(c.getInt(0), c.getInt(1), c.getString(2))) } }
        if (chapter != 1) return rows
        val intro = db.rawQuery("SELECT start, end, body FROM entries WHERE start = ?", arrayOf((book * 1_000_000).toString()))
            .use { c -> buildList { while (c.moveToNext()) add(CommentarySection(c.getInt(0), c.getInt(1), c.getString(2))) } }
        return intro + rows
    }
}
