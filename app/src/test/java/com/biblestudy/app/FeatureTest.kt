package com.biblestudy.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
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

    /** Settings are saved between tests in the same run, so start each test from the defaults. */
    @Before
    fun resetSettings() {
        compose.waitForIdle()
        compose.runOnUiThread {
            vm.fingerDraw = false
            vm.tool = Tool.PEN
            vm.clearSelection()
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

        // Drag the selection down by 150 px.
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset((x0 + x1) / 2f, y))
            repeat(10) { moveBy(Offset(0f, 15f)) }
            up()
        }
        compose.waitForIdle()
        val moved = vm.textStrokesFor("KJV", 43, 3).single().points
        assertEquals(original[1] + 150f / z, moved[1], 1.5f)
        assertEquals(original[0], moved[0], 1.5f)

        // Recolour, then undo twice to get back to the original stroke.
        compose.runOnUiThread { vm.recolorSelection(0xFFC62828.toInt()) }
        assertEquals(0xFFC62828.toInt(), vm.textStrokesFor("KJV", 43, 3).single().color)
        compose.runOnUiThread { vm.undo(); vm.undo() }
        compose.waitForIdle()
        val restored = vm.textStrokesFor("KJV", 43, 3).single()
        assertEquals(original[1], restored.points[1], 0.01f)
        assertNull(vm.selection)
    }
}
