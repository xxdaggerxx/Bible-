package com.biblestudy.app

import com.biblestudy.app.data.BookIntros
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookIntrosTest {
    private val intros = BookIntros.parse(File("src/main/assets/intros/books.txt").readText())

    @Test
    fun everyBookHasACompleteIntroduction() {
        assertEquals((1..66).toList(), intros.keys.sorted())
        for (i in intros.values) {
            val label = "${i.book} ${i.name}"
            for ((field, text) in listOf(
                "author" to i.author, "date" to i.date, "place" to i.place, "audience" to i.audience,
                "type" to i.type, "background" to i.background, "purpose" to i.purpose, "themes" to i.themes,
                "people" to i.people, "places" to i.places, "connections" to i.connections,
            )) assertTrue("$label: $field", text.isNotBlank())
            assertTrue("$label: outline", i.outline.size >= 2)
            assertTrue("$label: key verses", i.keyVerses.isNotEmpty())
            assertTrue("$label: outline starts at 1:1", i.outline.first().passage.let { it.chapter == 1 && it.verse == 1 })
        }
    }

    @Test
    fun traditionalAuthorshipIsGiven() {
        assertTrue(intros.getValue(1).author.startsWith("Moses"))
        assertTrue(intros.getValue(23).author.startsWith("Isaiah"))
        assertTrue(intros.getValue(27).author.startsWith("Daniel"))
        assertTrue(intros.getValue(54).author.startsWith("Paul"))
        assertTrue(intros.getValue(58).author.startsWith("Unknown"))
    }

    @Test
    fun referencesParse() {
        val p = BookIntros.passage(1, "1:1-2:25")!!
        assertEquals(listOf(1, 1, 2, 25), listOf(p.chapter, p.verse, p.endChapter, p.endVerse))
        val q = BookIntros.passage(43, "3:16")!!
        assertEquals(listOf(3, 16, 3, 16), listOf(q.chapter, q.verse, q.endChapter, q.endVerse))
        val r = BookIntros.passage(20, "31:10-31")!!
        assertEquals(listOf(31, 10, 31, 31), listOf(r.chapter, r.verse, r.endChapter, r.endVerse))
    }
}
