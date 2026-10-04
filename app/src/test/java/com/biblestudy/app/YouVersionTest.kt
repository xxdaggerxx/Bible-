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
}
