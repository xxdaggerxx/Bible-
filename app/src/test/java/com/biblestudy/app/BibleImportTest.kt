package com.biblestudy.app

import com.biblestudy.app.data.BibleImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
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
}
