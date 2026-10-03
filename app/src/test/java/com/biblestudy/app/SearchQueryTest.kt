package com.biblestudy.app

import com.biblestudy.app.data.BibleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    @Test
    fun minusWordsAreExcluded() {
        assertEquals("love" to listOf("world"), BibleRepository.splitExcluded("love -world"))
        assertEquals("\"eternal life\"" to listOf("lov*"), BibleRepository.splitExcluded("\"eternal life\" -lov*"))
        assertEquals("a - b" to emptyList<String>(), BibleRepository.splitExcluded("a - b"))
        assertEquals(listOf("love"), BibleRepository.terms("love -world"))
    }

    @Test
    fun exclusionMatchesWholeWordsOrBeginnings() {
        assertTrue(BibleRepository.containsAny("For God so loved the world", listOf("world")))
        assertFalse(BibleRepository.containsAny("worldly things", listOf("world")))
        assertTrue(BibleRepository.containsAny("worldly things", listOf("world*")))
        assertTrue(BibleRepository.containsAny("The LORD's house", listOf("lord’s")))
        assertFalse(BibleRepository.containsAny("anything", emptyList()))
    }
}
