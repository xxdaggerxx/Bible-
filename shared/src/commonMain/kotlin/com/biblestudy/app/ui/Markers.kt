package com.biblestudy.app.ui

import com.biblestudy.app.data.MarkRow
import com.biblestudy.app.model.Verse
import com.biblestudy.app.model.VerseId

/**
 * Which books, chapters and verses have the user's notes, for the markers in the book picker.
 * Ink, highlights and images count only on the layers passed in (the visible ones); typed notes
 * aren't on a layer, so they always count.
 */
class MarkerIndex(private val rows: List<MarkRow>, private val notes: Set<Int>) {

    /** Layers with something in [book] (and [chapter], if given), in the order of [visible]. */
    fun layers(visible: List<Long>, book: Int, chapter: Int? = null): List<Long> {
        val found = rows.filter { it.book == book && (chapter == null || it.chapter == chapter) }.mapTo(HashSet()) { it.layerId }
        return visible.filter { it in found }
    }

    /**
     * Layers with something on each verse of a chapter. [verses] is the chapter's text in the version
     * being read; it places highlights (stored by character offset) in their verse.
     */
    fun verseLayers(visible: List<Long>, book: Int, chapter: Int, verses: List<Verse>): Map<Int, List<Long>> {
        val starts = verseStartOffsets(verses)
        fun verseAt(offset: Int): Int {
            var i = 0
            while (i + 1 < starts.size && starts[i + 1] <= offset) i++
            return verses.getOrNull(i)?.verse ?: 1
        }
        val byVerse = HashMap<Int, HashSet<Long>>()
        for (r in rows) {
            if (r.book != book || r.chapter != chapter) continue
            val v = if (r.start >= 0) verseAt(r.start) else r.verse
            byVerse.getOrPut(v) { HashSet() }.add(r.layerId)
        }
        return byVerse.mapValues { (_, ids) -> visible.filter { it in ids } }.filterValues { it.isNotEmpty() }
    }

    fun hasNote(book: Int, chapter: Int? = null, verse: Int? = null): Boolean = notes.any {
        VerseId.book(it) == book && (chapter == null || VerseId.chapter(it) == chapter) &&
            (verse == null || VerseId.verse(it) == verse)
    }
}
