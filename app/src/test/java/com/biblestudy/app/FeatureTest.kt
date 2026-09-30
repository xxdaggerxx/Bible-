package com.biblestudy.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import com.biblestudy.app.model.SearchScope
import com.biblestudy.app.model.Tool
import com.biblestudy.app.ui.Page
import com.biblestudy.app.ui.StudyViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real app on a JVM-emulated Galaxy Tab S9 (landscape) to check the 0.2 features.
 * Pen input is simulated with "Draw with finger", which sends touches down the same pen path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
class FeatureTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm: StudyViewModel get() = ViewModelProvider(compose.activity)[StudyViewModel::class.java]

    /**
     * Chapters and saved ink load on background threads, which waitForIdle doesn't track.
     * Wait until the page is laid out and its annotations are read from the database.
     */
    private fun waitForLoaded() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("loading").fetchSemanticsNodes().isEmpty() && vm.pendingLoads == 0
        }
        compose.waitForIdle()
    }

    /** Settings and notes are saved between tests in the same run, so start each test from the defaults. */
    @Before
    fun resetSettings() {
        waitForLoaded()
        compose.runOnUiThread {
            vm.fingerDraw = false
            vm.tool = Tool.PEN
            vm.clearSelection()
            vm.setVersion(0, "KJV")
            vm.goTo(0, 43, 3, remember = false)
            vm.partialEraser = false
            vm.panels[0].back.clear()
            vm.panels[0].forward.clear()
            vm.panels[0].zoomRel.keys.forEach { vm.panels[0].zoomRel[it] = 1f }
        }
        waitForLoaded()
        // The notes database also survives between tests: start with no ink on John 3.
        compose.runOnUiThread {
            for (v in listOf("KJV", "BSB", "WEB")) {
                vm.textStrokesFor(v, 43, 3).toList().forEach { vm.removeItem(it) }
                vm.highlightsFor(v, 43, 3).toList().forEach { vm.removeItem(it) }
            }
            vm.marginStrokesFor(43, 3).toList().forEach { vm.removeItem(it) }
        }
        compose.waitForIdle()
    }

    private fun readerSize() = compose.onNodeWithTag("reader0").fetchSemanticsNode().size

    /** Page units to reader pixels at fit-width, with only the right margin shown. */
    private fun zoom(): Float = readerSize().width / (Page.COL_W + vm.marginWidth(left = false))

    private fun snap(name: String) {
        compose.waitForIdle()
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun continuousScrollMovesIntoNextChapterAndBack() {
        compose.onNodeWithText("John 3").assertExists()
        var swipes = 0
        while (compose.onAllNodesWithText("John 4").fetchSemanticsNodes().isEmpty() && swipes < 40) {
            compose.onNodeWithTag("reader0").performTouchInput { swipeUp(durationMillis = 300) }
            compose.waitForIdle()
            swipes++
        }
        compose.onNodeWithText("John 4").assertExists()
        assertEquals(4, vm.panels[0].chapter)
        snap("10-scrolled-into-john-4")

        // One swipe back down crosses back into chapter 3.
        compose.onNodeWithTag("reader0").performTouchInput { swipeDown(durationMillis = 300) }
        compose.waitForIdle()
        compose.onNodeWithText("John 3").assertExists()
    }

    @Test
    fun switchingVersionsKeepsInkWithItsVersion() {
        compose.waitForIdle()
        // Draw on the KJV words.
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.PEN }
        val z = zoom()
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset((Page.COL_PAD + 100f) * z, 520f))
            repeat(10) { moveBy(Offset(20f, 0f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(1, vm.textStrokesFor("KJV", 43, 3).size)

        // Switch to the BSB with the version picker.
        compose.runOnUiThread { vm.fingerDraw = false } // taps on the menu shouldn't draw
        compose.onNodeWithContentDescription("Change Bible version").performClick()
        compose.onNodeWithText("BSB", substring = true).performClick()
        waitForLoaded()
        assertEquals("BSB", vm.panels[0].version)
        compose.onNodeWithText("BSB").assertExists()
        assertEquals(0, vm.textStrokesFor("BSB", 43, 3).size) // KJV ink stays on the KJV
        assertEquals(1, vm.textStrokesFor("KJV", 43, 3).size)
        snap("13-bsb")

        // Each version has its own text and search index.
        assertTrue(vm.text("BSB").verseText(43003016)!!.contains("one and only Son"))
        assertTrue(vm.text("WEB").verseText(43003016)!!.contains("only born Son"))
        assertTrue(vm.text("BSB").search("\"one and only Son\"", SearchScope.ALL, 43).isNotEmpty())
        assertTrue(vm.text("KJV").search("\"one and only Son\"", SearchScope.ALL, 43).isEmpty())
    }

    @Test
    fun draggingTheMarginGripWidensTheMargin() {
        compose.waitForIdle()
        val size = readerSize()
        val before = vm.marginWidth(left = false)
        val edgeX = Page.COL_W * zoom() // right margin's inner edge at fit-width
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset(edgeX, size.height / 2f))
            repeat(6) { moveBy(Offset(-40f, 0f)) }
            up()
        }
        compose.waitForIdle()
        val after = vm.marginWidth(left = false)
        assertTrue("margin should widen: $before -> $after", after > before + 50f)
        snap("11-wider-margin")
    }

    @Test
    fun lassoSelectsMovesRecoloursAndUndoes() {
        compose.waitForIdle()
        compose.runOnUiThread {
            vm.fingerDraw = true
            vm.tool = Tool.PEN
        }
        val z = zoom()
        val y = 520f
        val x0 = (Page.COL_PAD + 100f) * z
        val x1 = (Page.COL_PAD + 400f) * z
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset(x0, y))
            for (i in 1..20) moveTo(Offset(x0 + (x1 - x0) * i / 20f, y + if (i % 2 == 0) 10f else -10f))
            up()
        }
        compose.waitForIdle()
        val strokes = vm.textStrokesFor("KJV", 43, 3)
        assertEquals(1, strokes.size)
        val original = strokes[0].points.copyOf()

        // Lasso a loop around the stroke.
        compose.runOnUiThread { vm.tool = Tool.LASSO }
        val pad = 60f
        val loop = listOf(
            Offset(x0 - pad, y - pad), Offset(x1 + pad, y - pad),
            Offset(x1 + pad, y + pad), Offset(x0 - pad, y + pad), Offset(x0 - pad, y - pad + 5f),
        )
        compose.onNodeWithTag("reader0").performTouchInput {
            down(loop[0])
            for (i in 1 until loop.size) {
                val a = loop[i - 1]; val b = loop[i]
                for (k in 1..10) moveTo(a + (b - a) * (k / 10f))
            }
            up()
        }
        compose.waitForIdle()
        assertNotNull(vm.selection)
        assertEquals(1, vm.selection!!.ids.size)
        snap("12-lasso-selection")

        // Drag the selection 150 px to the right. (Vertical moves re-anchor the stroke on its new
        // line; LayoutAnchorTest covers line coordinates.)
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset((x0 + x1) / 2f, y))
            repeat(10) { moveBy(Offset(15f, 0f)) }
            up()
        }
        compose.waitForIdle()
        val moved = vm.textStrokesFor("KJV", 43, 3).single().points
        assertEquals(original[0] + 150f / z, moved[0], 1.5f)
        assertEquals(original[1], moved[1], 0.0001f)

        // Recolour, then undo twice to get back to the original stroke.
        compose.runOnUiThread { vm.recolorSelection(0xFFC62828.toInt()) }
        assertEquals(0xFFC62828.toInt(), vm.textStrokesFor("KJV", 43, 3).single().color)
        compose.runOnUiThread { vm.undo(); vm.undo() }
        compose.waitForIdle()
        val restored = vm.textStrokesFor("KJV", 43, 3).single()
        assertEquals(original[1], restored.points[1], 0.01f)
        assertNull(vm.selection)
    }

    @Test
    fun backAndForwardReturnToEarlierPassages() {
        compose.runOnUiThread { vm.goTo(0, 19, 23) } // e.g. from the book picker
        waitForLoaded()
        compose.onNodeWithText("Psalms 23").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        waitForLoaded()
        assertEquals(43 to 3, vm.panels[0].book to vm.panels[0].chapter)
        compose.onNodeWithContentDescription("Forward").performClick()
        waitForLoaded()
        assertEquals(19 to 23, vm.panels[0].book to vm.panels[0].chapter)
        // The chapter arrows don't add to history.
        compose.onNodeWithContentDescription("Next chapter").performClick()
        assertEquals(1, vm.panels[0].back.size)
    }

    @Test
    fun doubleTapTogglesFitWidthAndLastZoom() {
        val fit = vm.panels[0].zoom
        compose.onNodeWithTag("reader0").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertTrue("zoomed in", vm.panels[0].zoom > fit * 1.2f)
        assertTrue(vm.panels[0].zoomRel.values.any { it > 1.2f }) // remembered for this orientation
        compose.onNodeWithTag("reader0").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertEquals(fit, vm.panels[0].zoom, 0.001f)
    }

    @Test
    fun partialEraserCutsAStrokeInTwo() {
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.PEN }
        val z = zoom()
        val y = 520f
        val x0 = (Page.COL_PAD + 60f) * z
        val x1 = (Page.COL_PAD + 560f) * z
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset(x0, y))
            for (i in 1..40) moveTo(Offset(x0 + (x1 - x0) * i / 40f, y))
            up()
        }
        compose.waitForIdle()
        assertEquals(1, vm.textStrokesFor("KJV", 43, 3).size)

        // Erase straight down through the middle.
        compose.runOnUiThread { vm.tool = Tool.ERASER; vm.partialEraser = true }
        val mid = (x0 + x1) / 2f
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset(mid, y - 60f))
            for (i in 1..12) moveTo(Offset(mid, y - 60f + 10f * i))
            up()
        }
        compose.waitForIdle()
        assertEquals(2, vm.textStrokesFor("KJV", 43, 3).size)
        compose.runOnUiThread { vm.undo() } // one step restores the whole stroke
        compose.waitForIdle()
        assertEquals(1, vm.textStrokesFor("KJV", 43, 3).size)
    }

    @Test
    fun longPressSelectsTextToHighlight() {
        val z = zoom()
        // A word in the middle of the first line of verse 2.
        compose.onNodeWithTag("reader0").performTouchInput { longClick(Offset((Page.COL_PAD + 250f) * z, 600f)) }
        compose.waitForIdle()
        compose.onNodeWithText("Copy").assertExists()
        compose.onNodeWithText("(KJV)", substring = true).assertExists()
        snap("14-text-selection")
        compose.onNodeWithText("Highlight").performClick()
        compose.waitForIdle()
        assertEquals(1, vm.highlightsFor("KJV", 43, 3).size)
        compose.onAllNodesWithText("Copy").assertCountEquals(0)
    }

    @Test
    fun typedNotesAreSearchable() {
        compose.runOnUiThread { vm.setNote(com.biblestudy.app.model.VerseTarget(43, 3, 16), "God's love for the whole world") }
        compose.waitUntil(5_000) { vm.user.searchNotes("whole world", 1, 66).isNotEmpty() }
        val hits = vm.user.searchNotes("love world", 40, 66)
        assertEquals(43003016, hits.single().let { it.book * 1_000_000 + it.chapter * 1_000 + it.verse })
        assertTrue(vm.user.searchNotes("love world", 1, 39).isEmpty()) // Old Testament only
        assertTrue(vm.user.searchNotes("100%", 1, 66).isEmpty())
        compose.runOnUiThread { vm.setNote(com.biblestudy.app.model.VerseTarget(43, 3, 16), "") }
    }
}
