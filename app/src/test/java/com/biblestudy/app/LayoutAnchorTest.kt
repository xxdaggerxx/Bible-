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
}
