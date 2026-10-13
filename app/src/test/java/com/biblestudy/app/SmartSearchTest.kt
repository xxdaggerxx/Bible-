package com.biblestudy.app

import androidx.test.core.app.ApplicationProvider
import com.biblestudy.app.data.BibleRepository
import com.biblestudy.app.data.SmartResult
import com.biblestudy.app.data.SmartSearch
import com.biblestudy.app.data.StudyRepository
import com.biblestudy.app.platform.AndroidAppContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Smart search (section 29) on the real bundled data, including the searches that must pass (EVAL). */
@RunWith(RobolectricTestRunner::class)
class SmartSearchTest {
    private val ctx get() = AndroidAppContext(ApplicationProvider.getApplicationContext())
    private val study by lazy { StudyRepository(ctx) }
    private val smart by lazy { SmartSearch(ctx, study) }
    private val bibles by lazy { BibleRepository.BUNDLED.map { BibleRepository(ctx, it) } }

    private fun v(book: Int, chapter: Int, verse: Int) = book * 1_000_000 + chapter * 1000 + verse
    private fun search(q: String): SmartResult = smart.search(q, bibles) ?: error("no result for $q")
    private fun SmartResult.has(id: Int) = byId.containsKey(id)
    private fun SmartResult.inKey(id: Int) = key.any { it.id == id }

    @Test
    fun questionWordingAndSmallWordsAreLeftOut() {
        assertEquals(listOf("righteousness"), smart.contentWords("What does the Bible say about righteousness?"))
        assertEquals(listOf("prayer"), smart.contentWords("what is prayer"))
        assertEquals(listOf("paul", "travels"), smart.contentWords("Paul's travels"))
        assertEquals(listOf("abraham", "trials"), smart.contentWords("Abraham’s trials"))
        assertTrue(smart.contentWords("what is the").isEmpty())
        assertNull(smart.search("what is the", bibles))
    }

    @Test
    fun wordFormsGoTogetherButNotDifferentWords() {
        val r = smart.forms("righteousness")
        assertTrue(r.containsAll(listOf("righteous", "righteousness", "righteously")))
        assertFalse("unrighteous" in r)
        assertTrue("prayer" in smart.forms("pray") && "praise" !in smart.forms("pray"))
        assertTrue("seeds" in smart.forms("seed") && "see" !in smart.forms("seed"))
        assertFalse("testimony" in smart.forms("test"))
    }

    @Test
    fun aWordFindsItsHebrewAndGreekWords() {
        val fam = smart.family("righteousness").map { it.strong }
        assertTrue(fam.containsAll(listOf("H6662", "H6664", "H6666", "G1342", "G1343")))
        assertTrue(smart.family("seed").map { it.strong }.containsAll(listOf("H2233", "G4690")))
    }

    @Test
    fun righteousnessFindsAllThePassages() {
        val r = search("What does the Bible say about righteousness?")
        assertEquals("Righteousness", r.topic?.title)
        // TOP-1: every verse with the Hebrew word, even where the English has another word
        // (Genesis 30:33 is "righteousness" in the KJV, "honesty" in the BSB).
        assertTrue(r.has(v(1, 30, 33)))
        assertTrue(smart.lemmaVerses("H6666").all { r.has(it) })
        // SRCH-40: verses whose commentary note speaks of it (Titus 3:7, "justified").
        assertTrue(r.has(v(56, 3, 7)))
        // TOP-5: key passages first.
        assertTrue(r.inKey(v(1, 15, 6)))
        assertTrue(r.inKey(v(47, 5, 21)))
        // TOP-6: grouped by Nave's subtopics.
        val byFaith = r.groups.first { it.label.startsWith("By faith") }
        assertTrue(v(1, 15, 6) in byFaith.ids)
        assertTrue(r.verses.size > 1000)
        // SRCH-14: why each was found.
        assertTrue(r.byId.getValue(v(1, 15, 6)).reasons.any { it.startsWith("Nave's") })
    }

    @Test
    fun seedFindsThePromisedSeedAndTheSower() {
        val r = search("seed")
        for (id in listOf(v(1, 3, 15), v(1, 22, 18), v(48, 3, 16), v(42, 8, 11), v(48, 6, 7))) assertTrue("$id", r.has(id))
        assertTrue(r.inKey(v(1, 3, 15)) || r.inKey(v(1, 22, 18)))
    }

    @Test
    fun topicsGiveTheirBestKnownVersesFirst() {
        assertTrue(search("grace").let { it.inKey(v(49, 2, 8)) && it.inKey(v(43, 1, 14)) })
        assertTrue(search("prayer").inKey(v(40, 6, 9)))
        assertTrue(search("forgiveness").inKey(v(49, 1, 7)))
        assertTrue(search("suffering").inKey(v(60, 2, 21)))
        assertTrue(search("kingdom of God").inKey(v(40, 6, 33)))
        assertTrue(search("prayer").reflections.isNotEmpty())
    }

    @Test
    fun aPersonShowsTheirLifeStepByStep() {
        val r = search("Abraham")
        assertEquals("Abraham", r.name?.name)
        assertTrue(r.groups.size > 20)
        val trial = r.groups.first { it.label.startsWith("Trial of his faith") }
        assertTrue(v(1, 22, 1) in trial.ids)
        // A person and a theme: the steps the theme is in (PPL-2).
        val trials = search("Abraham's trials")
        assertEquals("Abraham", trials.name?.name)
        assertTrue(trials.groups.any { it.label.startsWith("Trial of his faith") })
    }

    @Test
    fun paulsTravelsOffersTheReadyMadePage() {
        val r = search("Paul's travels")
        assertEquals("Paul", r.name?.name)
        assertTrue("paul" in r.pages)
        assertNotNull(r.groups.firstOrNull())
    }

    @Test
    fun exactSearchSignsAreLeftToTheVersionSearch() {
        assertTrue(BibleRepository.isExactSearch("\"in the beginning\""))
        assertTrue(BibleRepository.isExactSearch("love -world"))
        assertTrue(BibleRepository.isExactSearch("lov*"))
        assertTrue(BibleRepository.isExactSearch("faith OR hope"))
        assertFalse(BibleRepository.isExactSearch("what does the Bible say about righteousness"))
        assertFalse(BibleRepository.isExactSearch("well-known"))
    }

    @Test
    fun rangesBecomeVerses() {
        assertEquals(listOf(v(1, 1, 1), v(1, 1, 2), v(1, 1, 3)), SmartSearch.verses("1001001-1001003"))
        assertEquals(listOf(v(43, 3, 16)), SmartSearch.verses("43003016-43003016"))
    }
}
