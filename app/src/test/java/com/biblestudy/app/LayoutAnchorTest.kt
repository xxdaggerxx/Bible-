package com.biblestudy.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.biblestudy.app.data.BibleRepository
import com.biblestudy.app.data.UserDb
import com.biblestudy.app.ui.reflowPoints
import com.biblestudy.app.data.UserDb.Companion.toBlob
import com.biblestudy.app.model.ChapterData
import com.biblestudy.app.ui.ChapterLayout
import com.biblestudy.app.ui.LineSpacing
import com.biblestudy.app.ui.buildChapterLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Ink on the words must stay on the same words when headings or line spacing change. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutAnchorTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val measurer = TextMeasurer(createFontFamilyResolver(app), Density(1f, 1f), LayoutDirection.Ltr)
    private val font = FontFamily(Font(R.font.gentium_book_plus_regular), Font(R.font.gentium_book_plus_bold, FontWeight.Bold))
    private val kjv = BibleRepository(app, BibleRepository.KJV)
    private val bsb = BibleRepository(app, BibleRepository.BSB)

    private fun layout(headings: Boolean, spacing: LineSpacing): ChapterLayout {
        val data = ChapterData("KJV", 43, 1, kjv.chapter(43, 1), if (headings) bsb.headings(43, 1) else emptyList())
        return buildChapterLayout(measurer, font, "John", data, spacing)
    }

    @Test
    fun inkMovesWithItsWordWhenTheFontChanges() {
        val book = layout(headings = false, spacing = LineSpacing.NORMAL)
        val data = ChapterData("KJV", 43, 1, kjv.chapter(43, 1))
        val sans = buildChapterLayout(measurer, FontFamily.SansSerif, "John", data)
        val word = book.textOf(0, book.textLength).indexOf("was made flesh") + 9 // "flesh"
        assertTrue(book.charCenter(word) != sans.charCenter(word)) // the fonts lay out differently
        // An underline under "flesh", drawn in the book font.
        val left = book.charCenter(word).x - 4f
        val right = book.charCenter(word + 4).x + 4f
        val y = book.charCenter(word).y + 14f
        val drawn = floatArrayOf(left, y, 0.5f, (left + right) / 2f, y, 0.5f, right, y, 0.5f)
        val moved = sans.displayPoints(reflowPoints(book.linePoints(drawn), book, sans))
        // In the sans-serif layout it is still under "flesh".
        val mid = sans.offsetAt(moved[3], moved[4] - 14f)
        assertTrue("offset $mid", mid in word..word + 4)
        assertEquals(sans.charCenter(word).y + 14f, moved[4], 3f)
    }

    @Test
    fun bsbHasHeadingsForJohn() {
        val h = bsb.headings(43, 1)
        assertTrue(h.any { it.verse == 14 && it.text == "The Word Became Flesh" })
        assertTrue(kjv.headings(43, 1).isEmpty())
    }

    @Test
    fun inkFollowsItsWordsAcrossHeadingsAndSpacing() {
        val plain = layout(headings = false, spacing = LineSpacing.NORMAL)
        val others = listOf(
            layout(headings = true, spacing = LineSpacing.NORMAL),
            layout(headings = true, spacing = LineSpacing.EXTRA),
            layout(headings = false, spacing = LineSpacing.WIDE),
        )
        assertTrue(others[0].headings.isNotEmpty())
        // Points on "flesh" in verse 14 (below two headings), just under it, and a stroke across two lines.
        val word = plain.textOf(0, plain.textLength).indexOf("was made flesh") + 9
        val y0 = plain.charCenter(word).y
        val stroke = floatArrayOf(20f, y0, 1f, 80f, y0 + 10f, 1f, 140f, y0 + 60f, 1f)
        val line = plain.lineAt(y0 + 30f) // strokes are anchored on the line under their middle
        val stored = plain.linePoints(stroke)
        for (o in others) {
            // Glyphs keep their distance from the baseline at any line height, so the ink moves
            // exactly as far as its line's baseline moved, and keeps its shape.
            val moved = o.baseline(line) - plain.baseline(line)
            val shown = o.displayPoints(stored)
            for (k in stroke.indices step 3) {
                assertEquals(stroke[k], shown[k], 0.001f)
                assertEquals(stroke[k + 1] + moved, shown[k + 1], 0.5f)
            }
        }
        // Round trip within one layout is exact.
        val back = plain.displayPoints(stored)
        for (k in stroke.indices) assertEquals(stroke[k], back[k], 0.05f)
        // Headings push later verses down; verse 1 sits under the first heading.
        assertTrue(others[0].verseTop(14) > plain.verseTop(14) + 50f)
        assertTrue(others[0].isOnText(others[0].charCenter(word).y))
        assertFalse(others[0].isOnText(others[0].charCenter(0).y - 40f))
    }

    private fun createNotesAndBookmarks(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS layers(id INTEGER PRIMARY KEY, name TEXT, color INTEGER, visible INTEGER, locked INTEGER, sort INTEGER)")
        db.execSQL(
            "CREATE TABLE images(id INTEGER PRIMARY KEY, layer_id INTEGER NOT NULL, book INTEGER NOT NULL, " +
                "chapter INTEGER NOT NULL, region INTEGER NOT NULL, verse INTEGER NOT NULL, x REAL NOT NULL, " +
                "y REAL NOT NULL, w REAL NOT NULL, h REAL NOT NULL, file TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE notes(book INTEGER NOT NULL, chapter INTEGER NOT NULL, verse INTEGER NOT NULL, " +
                "text TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(book, chapter, verse))"
        )
        db.execSQL(
            "CREATE TABLE bookmarks(id INTEGER PRIMARY KEY, book INTEGER NOT NULL, chapter INTEGER NOT NULL, " +
                "verse INTEGER NOT NULL, created INTEGER NOT NULL)"
        )
    }

    @Test
    fun notesAndBookmarksFrom05SurviveTheUpgrade() {
        // A database from 0.5 (schema version 2): notes are single verses, bookmarks have no folders.
        val file = app.getDatabasePath(UserDb.NAME)
        file.parentFile?.mkdirs()
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createNotesAndBookmarks(db)
            db.execSQL(
                "CREATE TABLE strokes(id INTEGER PRIMARY KEY, layer_id INTEGER NOT NULL, version TEXT, book INTEGER NOT NULL, " +
                    "chapter INTEGER NOT NULL, region INTEGER NOT NULL, verse INTEGER NOT NULL, highlighter INTEGER NOT NULL, " +
                    "color INTEGER NOT NULL, width REAL NOT NULL, points BLOB NOT NULL, coords INTEGER NOT NULL DEFAULT 1)"
            )
            db.insert("strokes", null, ContentValues().apply {
                put("id", 5); put("layer_id", 1); put("version", "KJV"); put("book", 43); put("chapter", 3)
                put("region", 0); put("verse", 16); put("highlighter", 0); put("color", 0); put("width", 3f)
                put("points", floatArrayOf(10f, 3.5f, 0.5f).toBlob())
            })
            db.execSQL(
                "CREATE TABLE highlights(id INTEGER PRIMARY KEY, layer_id INTEGER, version TEXT, book INTEGER, chapter INTEGER, " +
                    "start_off INTEGER, end_off INTEGER, color INTEGER)"
            )
            db.execSQL("INSERT INTO notes VALUES(43, 3, 16, 'For God so loved', 1)")
            db.execSQL("INSERT INTO bookmarks VALUES(7, 19, 23, 1, 2)")
            db.version = 2
        }
        val user = UserDb(app)
        val n = user.noteCovering(43, 3, 16)!!
        assertEquals("For God so loved", n.text)
        assertEquals(16, n.endVerse)
        assertEquals(null, user.noteCovering(43, 3, 17))
        assertEquals("BOOK", user.loadText("KJV", 43, 3).first.single().font) // 0.5 ink was drawn in Gentium Book
        val b = user.bookmarks().single()
        assertEquals(7L, b.id)
        assertEquals("", b.folder)
        user.setNote(43, 3, 16, "For God so loved", endVerse = 18)
        assertEquals(16, user.noteCovering(43, 3, 18)!!.verse)
        user.addBookmark(b.copy(folder = "Psalms"))
        assertEquals("Psalms", user.bookmarks().single().folder)
        user.close()
    }

    @Test
    fun inkSavedBefore04IsMarkedForConversion() {
        // A database from 0.3 (schema version 1): strokes have no coords column.
        val file = app.getDatabasePath(UserDb.NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE strokes(id INTEGER PRIMARY KEY, layer_id INTEGER NOT NULL, version TEXT, book INTEGER NOT NULL, " +
                    "chapter INTEGER NOT NULL, region INTEGER NOT NULL, verse INTEGER NOT NULL, highlighter INTEGER NOT NULL, " +
                    "color INTEGER NOT NULL, width REAL NOT NULL, points BLOB NOT NULL)"
            )
            db.execSQL("CREATE TABLE layers(id INTEGER PRIMARY KEY, name TEXT, color INTEGER, visible INTEGER, locked INTEGER, sort INTEGER)")
            db.execSQL(
                "CREATE TABLE highlights(id INTEGER PRIMARY KEY, layer_id INTEGER, version TEXT, book INTEGER, chapter INTEGER, " +
                    "start_off INTEGER, end_off INTEGER, color INTEGER)"
            )
            createNotesAndBookmarks(db)
            db.insert("strokes", null, ContentValues().apply {
                put("id", 1); put("layer_id", 1); put("version", "KJV"); put("book", 43); put("chapter", 1)
                put("region", 0); put("verse", 1); put("highlighter", 0); put("color", 0); put("width", 3f)
                put("points", floatArrayOf(10f, 100f, 0.5f).toBlob())
            })
            db.version = 1
        }
        val (strokes, _) = UserDb(app).loadText("KJV", 43, 1)
        assertEquals(1, strokes.size)
        assertFalse(strokes[0].lineAnchored)

        // Converting with the pre-0.4 layout, then drawing with headings, keeps it on the same line.
        val plain = layout(headings = false, spacing = LineSpacing.NORMAL)
        val withHeadings = layout(headings = true, spacing = LineSpacing.NORMAL)
        val converted = plain.linePoints(strokes[0].points)
        val line = plain.lineAt(100f)
        assertEquals(100f + withHeadings.baseline(line) - plain.baseline(line), withHeadings.displayPoints(converted)[1], 0.5f)
    }

    @Test
    fun verseOffsetsMatchTheLaidOutText() {
        val l = layout(headings = true, spacing = LineSpacing.NORMAL)
        val verses = kjv.chapter(43, 1)
        val starts = com.biblestudy.app.ui.verseStartOffsets(verses)
        starts.forEachIndexed { i, off -> assertEquals(verses[i].verse, l.verseAtOffset(off)) }
        assertEquals(verses.last().verse, l.verseAtOffset(l.textLength - 1))
    }

    @Test
    fun aTappedWordFindsItsGreekWord() {
        val l = layout(headings = true, spacing = LineSpacing.NORMAL)
        // "And the Word was made flesh" (John 1:14): tapping inside "flesh" finds word 5, Greek sarx.
        val v14 = l.verseRange(14)!!
        val text = l.text.layoutInput.text.text
        val at = text.indexOf("flesh", v14.first) + 2
        assertEquals(14, l.verseAtOffset(at))
        assertEquals(5, l.wordAt(at))
        assertEquals(-1, l.wordAt(v14.first)) // on the verse number
        val study = com.biblestudy.app.data.StudyRepository(app)
        assertEquals("G4561", study.strongs("KJV", 43001014)[5])
        assertEquals("\u03c3\u03ac\u03c1\u03be", study.lexicon("G4561")!!.lemma)
    }
}
