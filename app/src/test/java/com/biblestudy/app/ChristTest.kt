package com.biblestudy.app

import com.biblestudy.app.data.Christ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChristTest {
    private val lines = File("src/main/assets/study/christ.tsv").readLines().filter { it.isNotBlank() }

    @Test
    fun everyEntryLoadsAndPointsFromTheOldTestamentToTheNew() {
        val all = lines.map { Christ.parse(it) }
        assertTrue("every line reads", all.all { it != null })
        val entries = all.filterNotNull()
        assertTrue(entries.size >= 140)
        assertTrue(entries.all { it.first / 1_000_000 in 1..39 })
        assertTrue(entries.all { it.fulfilled.startsWith("[[") && it.note.isNotBlank() })
        // Both kinds, and the best known are there.
        assertTrue(entries.any { it.prophecy } && entries.any { !it.prophecy })
        val micah = entries.single { it.contains(33_005_002) }
        assertTrue(micah.prophecy)
        assertEquals("Out of Bethlehem", micah.title)
        assertTrue("Matthew 2:1-6" in micah.fulfilled)
        assertEquals(listOf(8, 9), entries.single { it.contains(4_021_008) }.versesIn(4, 21))
    }
}
