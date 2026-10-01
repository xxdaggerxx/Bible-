package com.biblestudy.app

import androidx.test.core.app.ApplicationProvider
import com.biblestudy.app.data.BibleRepository
import com.biblestudy.app.data.Passage
import com.biblestudy.app.data.RefLinks
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RefLinksTest {
    private val books = BibleRepository(ApplicationProvider.getApplicationContext(), BibleRepository.KJV).books

    private fun list(s: String) = RefLinks.parseList(s, books).map { s.substring(it.start, it.end) to it.passage }
    private fun find(s: String) = RefLinks.find(s, books).map { s.substring(it.start, it.end) to it.passage }

    @Test
    fun parallelPassagesUnderHeadings() {
        assertEquals(
            listOf(
                "Matthew 3:13–17" to Passage(40, 3, 13, 3, 17),
                "Mark 1:9–11" to Passage(41, 1, 9, 1, 11),
                "Luke 3:21–22" to Passage(42, 3, 21, 3, 22),
            ),
            list("(Matthew 3:13–17; Mark 1:9–11; Luke 3:21–22)"),
        )
        // Book carried forward, verses continuing a chapter, chapter ranges, whole chapters, multi-word books.
        assertEquals(
            listOf("Luke 3:21–22" to Passage(42, 3, 21, 3, 22), "23" to Passage(42, 3, 23, 3, 23), "4:1" to Passage(42, 4, 1, 4, 1)),
            list("(Luke 3:21–22, 23; 4:1)"),
        )
        assertEquals(listOf("Psalms 1—41" to Passage(19, 1, 1, 41, 999)), list("Psalms 1—41"))
        assertEquals(listOf("Psalms 84:1–12" to Passage(19, 84, 1, 84, 12)), list("(Psalms 84:1–12)"))
        assertEquals(listOf("Song of Solomon 2:1" to Passage(22, 2, 1, 2, 1)), list("(Song of Solomon 2:1)"))
        assertEquals(listOf("2 Kings 18:13–19:37" to Passage(12, 18, 13, 19, 37)), list("(2 Kings 18:13–19:37)"))
    }

    @Test
    fun referencesInNotes() {
        assertEquals(
            listOf("Rom 8:28" to Passage(45, 8, 28, 8, 28), "1 Cor 13:4-7" to Passage(46, 13, 4, 13, 7), "Psalm 23" to Passage(19, 23, 1, 23, 999)),
            find("See Rom 8:28 and 1 Cor 13:4-7, also Psalm 23."),
        )
        assertEquals(listOf("jn 3:16" to Passage(43, 3, 16, 3, 16)), find("like jn 3:16 says"))
        // Ordinary words and numbers are not references.
        assertEquals(emptyList<Any>(), find("it is 5 minutes; read page 23 of the book"))
        assertEquals(emptyList<Any>(), find("Genesis 99:1")) // no such chapter
    }

    @Test
    fun plainReferencesInArticlesBecomeStudyLinks() {
        val t = com.biblestudy.app.ui.linkPlainRefs("Rachel's tomb (Genesis 35:19) near [[8001001-8001001|Rut.1.1]].", books)
        assertEquals("Rachel's tomb ([[1035019-1035019|Genesis 35:19]]) near [[8001001-8001001|Rut.1.1]].", t)
    }
}
