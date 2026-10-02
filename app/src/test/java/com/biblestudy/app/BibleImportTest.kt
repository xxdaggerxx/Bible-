package com.biblestudy.app

import androidx.test.core.app.ApplicationProvider
import com.biblestudy.app.data.BibleImport
import com.biblestudy.app.data.BibleRepository
import com.biblestudy.app.data.ImportStudy
import com.biblestudy.app.data.StudyRepository
import com.biblestudy.app.model.SearchScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BibleImportTest {
    @Test
    fun usfmKeepsVerseTextAndDropsNotesTagsAndHeadings() {
        val usfm = """
            \id JHN - Test Bible
            \h John
            \c 3
            \s1 Jesus and Nicodemus
            \p
            \v 16 \w For|strong="G1063"\w* God so \w loved|strong="G25"\w* the world,\f + \fr 3:16 \ft Or only\f* that he gave
            \q his only Son.
            \v 17 For God did not send his Son.
        """.trimIndent()
        val p = BibleImport.parseUsfm(listOf(usfm))
        assertEquals("For God so loved the world, that he gave his only Son.", p.verses[43003016])
        assertEquals("For God did not send his Son.", p.verses[43003017])
        assertEquals("John", p.bookNames[43])
        assertEquals("Test Bible", p.title)
        assertFalse(p.verses.values.any { "Nicodemus" in it })
    }

    @Test
    fun osisMilestonesAndContainers() {
        val milestones = """
            <osis><osisText><header><work><title>Test OSIS</title></work></header>
            <div type="book" osisID="John"><chapter osisID="John.3">
            <verse sID="John.3.16" osisID="John.3.16"/>For God so loved the world<note>a note</note>, that he gave.<verse eID="John.3.16"/>
            <verse sID="John.3.17" osisID="John.3.17"/>Not to condemn.<verse eID="John.3.17"/>
            </chapter></div></osisText></osis>
        """.trimIndent()
        val a = BibleImport.parseOsis(milestones.byteInputStream())
        assertEquals("For God so loved the world, that he gave.", a.verses[43003016])
        assertEquals("Not to condemn.", a.verses[43003017])
        val containers = """<osis><osisText><div><verse osisID="Gen.1.1">In the beginning.</verse></div></osisText></osis>"""
        assertEquals("In the beginning.", BibleImport.parseOsis(containers.byteInputStream()).verses[1001001])
    }

    @Test
    fun usfmKeepsWordsOfJesusParagraphsAndJoinedVerses() {
        val usfm = """
            \id MAT
            \c 5
            \s1 The Beatitudes
            \p
            \v 1 He sat down.
            \v 2 And he taught them, saying:
            \q1
            \v 3 \wj “Blessed are the poor in spirit,\wj*
            \q2 \wj for theirs is the kingdom.\wj*
            \p
            \v 4-5 \wj “Blessed are those who mourn.\wj* Then he said
            \p more
            \v 6 After.
        """.trimIndent()
        val p = BibleImport.parseUsfm(listOf(usfm))
        assertEquals("“Blessed are the poor in spirit, for theirs is the kingdom.", p.verses[40005003])
        assertTrue(p.marksRed)
        assertEquals("0-10", p.red[40005003]) // every word of verse 3
        assertEquals("0-4", p.red[40005004]) // not "Then he said more"
        assertEquals(5, p.bridges[40005004])
        // Chapter start, a poetry line and a paragraph start a paragraph; a break partway through verse 4 doesn't move verse 6.
        assertEquals(setOf(40005001, 40005003, 40005004), p.paragraphs)
    }

    @Test
    fun osisKeepsWordsOfJesusAndParagraphs() {
        val osis = """
            <osis><osisText><div type="book" osisID="John"><chapter osisID="John.3">
            <p><verse sID="John.3.16" osisID="John.3.16"/><q who="Jesus" marker="“">For God so loved the world</q>, he said.<verse eID="John.3.16"/>
            <verse sID="John.3.17" osisID="John.3.17"/><q who="Jesus" sID="q1"/>For God did not send<verse eID="John.3.17"/></p>
            <p><verse sID="John.3.18" osisID="John.3.18 John.3.19"/>Whoever believes.<q eID="q1"/> Then<verse eID="John.3.18"/></p>
            </chapter></div></osisText></osis>
        """.trimIndent()
        val p = BibleImport.parseOsis(osis.byteInputStream())
        assertEquals("For God so loved the world, he said.", p.verses[43003016])
        assertEquals("0-5", p.red[43003016])
        assertEquals("0-4", p.red[43003017])
        assertEquals("0-1", p.red[43003018])
        assertEquals(19, p.bridges[43003018])
        assertEquals(setOf(43003016, 43003018), p.paragraphs)
    }

    // ---------- the ESV and NLT test passages (src/test/resources/import) ----------

    private fun fixture(version: String): List<String> =
        File(javaClass.classLoader!!.getResource("import/${version.lowercase()}")!!.toURI())
            .listFiles()!!.sorted().map { it.readText() }

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val opened = ArrayList<android.database.sqlite.SQLiteDatabase>()

    /** App files survive between tests in a run: leave no imported versions or open databases behind. */
    @After
    fun removeImported() {
        opened.forEach { it.close() }
        for (code in listOf("ESV", "NLT", "NLT2")) BibleRepository.removeImported(app, code)
    }

    /** Imports a test passage as [code], with word tags and the rest, and returns the study library reading it. */
    private fun import(code: String, files: List<String>): Pair<StudyRepository, BibleRepository> {
        val study = StudyRepository(app)
        val refs = BibleRepository.BUNDLED.map { BibleRepository(app, it) }
        val kjv = refs[0]
        val parsed = BibleImport.parseUsfm(files)
        val v = BibleImport.save(app, parsed, code, code, "test", kjv.books) { db, p -> ImportStudy.build(db, code, p, study, refs) }
        val bible = BibleRepository(app, v)
        study.ownDb = { if (it == code) bible.database else null }
        opened += refs.map { it.database } + bible.database
        return study to bible
    }

    /** The Strong's number given to [word] (its [nth] appearance) in verse [id]. */
    private fun StudyRepository.tagOf(code: String, bible: BibleRepository, id: Int, word: String, nth: Int = 0): String? {
        val text = bible.verseText(id)!!
        val words = StudyRepository.words(text).map { text.substring(it) }
        val i = words.withIndex().filter { it.value.equals(word, ignoreCase = true) }[nth].index
        return strongs(code, id)[i]
    }

    @Test
    fun esvImportKeepsTextRedLettersAndParagraphs() {
        val p = BibleImport.parseUsfm(fixture("ESV"))
        assertEquals(
            "“For God so loved the world, that he gave his only Son, that whoever believes in him should not perish but have eternal life.",
            p.verses[43003016],
        )
        assertEquals("The Lord is my shepherd; I shall not want.", p.verses[19023001])
        assertEquals("In the beginning, God created the heavens and the earth.", p.verses[1001001])
        assertFalse(p.verses.values.any { "Or For this is how" in it || "Beatitudes" in it || "Psalm of David" in it })
        assertEquals(31, p.verses.size)
        assertTrue(p.marksRed)
        assertEquals("0-23", p.red[43003016]) // all 24 words
        assertTrue(p.paragraphs.containsAll(listOf(1001001, 1001003, 19023001, 19023004, 40005001, 40005003, 43003016)))
        assertFalse(43003017 in p.paragraphs)
    }

    @Test
    fun esvWordStudiesWorkAfterImport() {
        val (study, esv) = import("ESV", fixture("ESV"))
        assertEquals("G25", study.tagOf("ESV", esv, 43003016, "loved"))
        assertEquals("G2316", study.tagOf("ESV", esv, 43003016, "God"))
        assertEquals("G2889", study.tagOf("ESV", esv, 43003016, "world"))
        assertEquals("G622", study.tagOf("ESV", esv, 43003016, "perish"))
        assertEquals("H1254", study.tagOf("ESV", esv, 1001001, "created"))
        assertEquals("H7307", study.tagOf("ESV", esv, 1001002, "Spirit"))
        assertEquals("H7462", study.tagOf("ESV", esv, 19023001, "shepherd"))
        assertEquals("G3107", study.tagOf("ESV", esv, 40005003, "Blessed"))
        // "the" is never given the number of a nearby word ("face", "spirit").
        assertNotEquals("H6440", study.tagOf("ESV", esv, 1001002, "the"))
        // Most words are tagged.
        val all = esv.allVerses()
        val words = all.sumOf { (_, t) -> StudyRepository.words(t).size }
        val tagged = all.sumOf { (id, _) -> study.strongs("ESV", id).count { it != null } }
        println("ESV: $tagged of $words words tagged")
        assertTrue("tagged $tagged of $words", tagged >= words * 0.7)
        // Every verse using a word, words of Jesus and paragraphs come from the ESV's own tables.
        assertTrue(study.occurrences("ESV", "G25", { esv.verseText(it) }).any { it.id == 43003016 })
        val john3 = esv.chapter(43, 3).associate { it.verse to it.text }
        assertTrue(study.redLetters("ESV", 43, 3, john3)[16]!!.isNotEmpty())
        assertTrue(16 in study.paragraphStarts("ESV", 43, 3))
        assertTrue(esv.search("loved", SearchScope.ALL, 43).any { it.verse == 16 && it.chapter == 3 })
        // The bundled versions still read study.db.
        assertEquals("G25", study.strongs("KJV", 43003016)[3])
    }

    @Test
    fun nltWordStudiesWorkAfterImport() {
        val (study, nlt) = import("NLT", fixture("NLT"))
        assertEquals("God loved the world", nlt.verseText(43003016)!!.substringAfter("how ").substringBefore(":"))
        assertEquals("G25", study.tagOf("NLT", nlt, 43003016, "loved"))
        assertEquals("G5207", study.tagOf("NLT", nlt, 43003016, "Son"))
        assertEquals("G166", study.tagOf("NLT", nlt, 43003016, "eternal"))
        assertEquals("H7462", study.tagOf("NLT", nlt, 19023001, "shepherd"))
        assertEquals("H1254", study.tagOf("NLT", nlt, 1001001, "created"))
        val all = nlt.allVerses()
        val words = all.sumOf { (_, t) -> StudyRepository.words(t).size }
        val tagged = all.sumOf { (id, _) -> study.strongs("NLT", id).count { it != null } }
        println("NLT: $tagged of $words words tagged")
        assertTrue("tagged $tagged of $words", tagged >= words * 0.6)
        // Poetry lines in the Beatitudes; a paragraph break partway through Genesis 1:5 stays inside it.
        assertTrue(listOf(3, 4, 5, 11).all { it in study.paragraphStarts("NLT", 40, 5) })
        assertEquals("God called the light “day” and the darkness “night.” And evening passed and morning came, marking the first day.", nlt.verseText(1001005))
    }

    @Test
    fun wordsOfJesusAreWorkedOutWhenTheFileHasNone() {
        // The NLT passages without their red-letter marks: Jesus' quotations are found from the WEB.
        val plain = fixture("NLT").map { it.replace(Regex("\\\\wj\\*?\\s?"), "") }
        val (study, nlt) = import("NLT2", plain)
        val john3 = nlt.chapter(43, 3).associate { it.verse to it.text }
        val red = study.redLetters("NLT2", 43, 3, john3)
        assertTrue(red[16]!!.isNotEmpty())
        assertTrue(study.redLetters("NLT2", 40, 5, nlt.chapter(40, 5).associate { it.verse to it.text })[3]!!.isNotEmpty())
        // Not the narrator: "Jesus went up on the mountainside".
        assertTrue(study.redLetters("NLT2", 40, 5, nlt.chapter(40, 5).associate { it.verse to it.text })[1] == null)
        assertTrue(16 in study.paragraphStarts("NLT2", 43, 3))
    }
}
