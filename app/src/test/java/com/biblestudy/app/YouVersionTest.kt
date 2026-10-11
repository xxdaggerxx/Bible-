package com.biblestudy.app

import com.biblestudy.app.data.BibleImport
import com.biblestudy.app.data.YouVersion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Online Bibles (BIB-12): YouVersion's chapter HTML read into verses, words of Jesus and paragraphs. */
@RunWith(RobolectricTestRunner::class)
class YouVersionTest {
    private fun chapter(name: String, book: Int, chapter: Int): BibleImport.Parsed {
        val html = JSONObject(javaClass.getResource("/youversion/$name")!!.readText()).getString("content")
        return BibleImport.parseUsfm(listOf(YouVersion.toUsfm(html, book, chapter)))
    }

    @Test
    fun readsVersesWordsOfJesusAndParagraphs() {
        val p = chapter("niv-JHN.3.json", 43, 3)
        assertEquals(36, p.verses.size)
        assertEquals(
            "For God so loved the world that he gave his one and only Son, that whoever believes in him shall not perish but have eternal life.",
            p.verses[BibleImport.vid(43, 3, 16)],
        )
        // Verse 1 starts with the text, not its label; footnotes are left out.
        assertEquals("Now there was a Pharisee, a man named Nicodemus who was a member of the Jewish ruling council.", p.verses[BibleImport.vid(43, 3, 1)])
        assertTrue(p.verses.values.joinToString("|"), p.verses.values.none { "3:3" in it })
        // Jesus' words in verse 3 are marked, the narrator's "Jesus replied," isn't.
        assertTrue("marksRed", p.marksRed)
        assertTrue(p.red.toString(), p.red[BibleImport.vid(43, 3, 3)]!!.startsWith("2-"))
        assertTrue(p.paragraphs.sorted().toString(), BibleImport.vid(43, 3, 1) in p.paragraphs && BibleImport.vid(43, 3, 3) in p.paragraphs)
        assertFalse(BibleImport.vid(43, 3, 2) in p.paragraphs)
    }

    @Test
    fun poetryLinesStayInTheirVerseAndTheLordIsInCapitals() {
        val p = chapter("niv-PSA.23.json", 19, 23)
        assertEquals(6, p.verses.size)
        assertEquals("The LORD is my shepherd, I lack nothing.", p.verses[BibleImport.vid(19, 23, 1)])
        assertEquals("He makes me lie down in green pastures, he leads me beside quiet waters,", p.verses[BibleImport.vid(19, 23, 2)])
        // The psalm's title isn't verse text.
        assertTrue(p.verses.values.none { "A psalm of David" in it })
    }

    @Test
    fun referencesAndCodes() {
        assertEquals(43003016, YouVersion.verseId("JHN.3.16"))
        assertEquals(1001001, YouVersion.verseId("GEN.1.1-3"))
        assertEquals("NIV", YouVersion.codeOf("NIV11"))
        assertEquals("NIVUK", YouVersion.codeOf("NIVUK11"))
        assertEquals("GNV", YouVersion.codeOf("enggnv"))
        assertEquals("NASB1995", YouVersion.codeOf("NASB1995"))
    }

    private fun esv(name: String, book: Int, chapter: Int): BibleImport.Parsed {
        val html = JSONObject(javaClass.getResource("/youversion/$name")!!.readText()).getJSONArray("passages").getString(0)
        return BibleImport.parseUsfm(listOf(com.biblestudy.app.data.Esv.toUsfm(html, book, chapter)))
    }

    @Test
    fun readsTheEsvFromCrossway() {
        val j = esv("esv-JHN.3.json", 43, 3)
        assertEquals(36, j.verses.size)
        assertEquals(
            "\u201cFor God so loved the world, that he gave his only Son, that whoever believes in him should not perish but have eternal life.",
            j.verses[BibleImport.vid(43, 3, 16)],
        )
        assertEquals("Now there was a man of the Pharisees named Nicodemus, a ruler of the Jews.", j.verses[BibleImport.vid(43, 3, 1)])
        assertTrue(j.marksRed)
        assertTrue(j.red.toString(), j.red[BibleImport.vid(43, 3, 3)]!!.startsWith("3-"))
        assertTrue(BibleImport.vid(43, 3, 9) in j.paragraphs)
        val p = esv("esv-PSA.23.json", 19, 23)
        assertEquals(6, p.verses.size)
        assertEquals("The LORD is my shepherd; I shall not want.", p.verses[BibleImport.vid(19, 23, 1)])
        assertEquals("He makes me lie down in green pastures. He leads me beside still waters.", p.verses[BibleImport.vid(19, 23, 2)])
        assertTrue(p.verses.values.none { "A Psalm of David" in it })
    }

    private fun nlt(name: String, book: Int, chapter: Int): BibleImport.Parsed =
        BibleImport.parseUsfm(listOf(com.biblestudy.app.data.Nlt.toUsfm(javaClass.getResource("/youversion/$name")!!.readText(), book, chapter)))

    @Test
    fun readsTheNltFromTyndale() {
        val j = nlt("nlt-JHN.3.html", 43, 3)
        assertEquals(36, j.verses.size)
        assertEquals(
            "\u201cFor this is how God loved the world: He gave his one and only Son, so that everyone who believes in him will not perish but have eternal life.",
            j.verses[BibleImport.vid(43, 3, 16)],
        )
        assertEquals("There was a man named Nicodemus, a Jewish religious leader who was a Pharisee.", j.verses[BibleImport.vid(43, 3, 1)])
        // Footnotes are left out; Jesus' words are marked.
        assertTrue(j.verses.values.joinToString(), j.verses.values.none { "born from above" in it || "3:3" in it })
        assertTrue(j.marksRed)
        assertTrue(j.red.toString(), j.red[BibleImport.vid(43, 3, 3)]!!.startsWith("2-"))
        val p = nlt("nlt-PSA.23.html", 19, 23)
        assertEquals(6, p.verses.size)
        assertEquals("The LORD is my shepherd; I have all that I need.", p.verses[BibleImport.vid(19, 23, 1)])
        assertTrue(p.verses.values.none { "A psalm of David" in it || "Is My Shepherd" in it })
        // Search results come with their text.
        val html = javaClass.getResource("/youversion/nlt-search.html")!!.readText()
        val (realKey, realHttp) = com.biblestudy.app.data.Nlt.key to com.biblestudy.app.data.Nlt.http
        com.biblestudy.app.data.Nlt.key = "test"
        com.biblestudy.app.data.Nlt.http = { 200 to html }
        val hits = try { com.biblestudy.app.data.Nlt.search("born again") } finally {
            com.biblestudy.app.data.Nlt.key = realKey; com.biblestudy.app.data.Nlt.http = realHttp
        }
        assertTrue(hits.toString(), hits.any { it.first == BibleImport.vid(43, 3, 3) && "born again" in it.second })
    }

    @Test
    fun asksTyndaleForThessaloniansAndJohnsLettersByItsOwnNames() {
        val nlt = com.biblestudy.app.data.Nlt
        // Tyndale returns no verses for "1Thess", "2Thess", "1John", "2John" or "3John".
        assertEquals(listOf("1Thes", "2Thes", "1Jn", "2Jn", "3Jn"), listOf(52, 53, 62, 63, 64).map(nlt::code))
        assertEquals("John", nlt.code(43))
        assertEquals("Col", nlt.code(51))
        assertEquals(52, nlt.bookOf("1Thes"))
        assertEquals(64, nlt.bookOf("3Jn"))
        assertEquals(43, nlt.bookOf("John"))
        val (realKey, realHttp) = nlt.key to nlt.http
        val asked = ArrayList<String>()
        nlt.key = "test"
        nlt.http = { url -> asked += url; 200 to "<table><tr><td><a href=\"x\">1Thes.4.9</a></td><td>Now concerning brotherly love</td></tr></table>" }
        val hits = try {
            runCatching { nlt.chapterUsfm(52, 1) }
            nlt.search("brotherly love")
        } finally {
            nlt.key = realKey; nlt.http = realHttp
        }
        assertTrue(asked.toString(), "ref=1Thes.1&" in asked.first())
        assertEquals(listOf(BibleImport.vid(52, 4, 9)), hits.map { it.first })
    }

    @Test
    fun anEmptyPageFromTyndaleIsTriedAgainNotKeptEmpty() {
        val nlt = com.biblestudy.app.data.Nlt
        // Hebrews 4 as Tyndale sends it: all 16 verses.
        val heb = BibleImport.parseUsfm(listOf(nlt.toUsfm(javaClass.getResource("/youversion/nlt-HEB.4.html")!!.readText(), 58, 4)))
        assertEquals((1..16).map { BibleImport.vid(58, 4, it) }, heb.verses.keys.sorted())
        assertTrue(heb.verses[BibleImport.vid(58, 4, 12)]!!.startsWith("For the word of God is alive and powerful."))
        // Busy, or a key it doesn't take: an empty page with "OK". That's an error, not a chapter with no verses.
        val (realKey, realHttp) = nlt.key to nlt.http
        nlt.key = "test"
        var asked = 0
        nlt.http = { asked++; 200 to "" }
        val failed = try { runCatching { nlt.chapterUsfm(58, 4) } } finally { nlt.key = realKey; nlt.http = realHttp }
        assertTrue(failed.exceptionOrNull() is java.io.IOException)
        assertEquals(3, asked)
        // A moment's hiccup: the second try brings the chapter.
        val page = javaClass.getResource("/youversion/nlt-HEB.4.html")!!.readText()
        var n = 0
        nlt.key = "test"
        nlt.http = { if (n++ == 0) 200 to "" else 200 to page }
        val usfm = try { nlt.chapterUsfm(58, 4) } finally { nlt.key = realKey; nlt.http = realHttp }
        assertTrue("\\v 16 " in usfm)
    }
}
