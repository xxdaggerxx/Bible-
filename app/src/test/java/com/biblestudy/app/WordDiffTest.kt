package com.biblestudy.app

import com.biblestudy.app.data.WordDiff
import org.junit.Assert.assertEquals
import org.junit.Test

class WordDiffTest {
    private fun marked(a: String, b: String) = WordDiff.changed(a, b).map { a.substring(it) }

    @Test
    fun marksOnlyTheWordsThatDiffer() {
        val kjv = "For God so loved the world, that he gave his only begotten Son"
        val web = "For God so loved the world, that he gave his one and only Son"
        assertEquals(listOf("begotten"), marked(kjv, web))
        assertEquals(listOf("one and"), marked(web, kjv))
    }

    @Test
    fun ignoresCaseAndPunctuation() {
        assertEquals(emptyList<String>(), marked("Jesus wept.", "jesus wept"))
        assertEquals(listOf("Jesus wept"), marked("Jesus wept.", ""))
    }
}
