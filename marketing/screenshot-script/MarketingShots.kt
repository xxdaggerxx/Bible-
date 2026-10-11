package com.biblestudy.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.biblestudy.app.model.Tool
import com.biblestudy.app.ui.HIGHLIGHT_COLORS
import com.biblestudy.app.ui.PEN_COLORS
import com.biblestudy.app.ui.StudyViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Landing-page screenshots: margin sketches drawn with the real pen (not part of the test suite). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
class MarketingShots {
    @get:Rule(order = 0) val flusher = SnapshotFlusher()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val vm: StudyViewModel get() = ViewModelProvider(compose.activity)[StudyViewModel::class.java]

    private fun waitForLoaded() {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (compose.onAllNodesWithTag("loading").fetchSemanticsNodes().isEmpty() && vm.pendingLoads == 0) break
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        val dir = File("build/marketing").apply { mkdirs() }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // Screenshot pixels (2560 x 1600) to reader-node pixels.
    private fun at(x: Float, y: Float) = Offset(x - 84f, y - 325f)

    private val rnd = java.util.Random(7)
    private fun j() = (rnd.nextFloat() - .5f) * 2.2f

    /** One pen stroke through screenshot-pixel points, moving in small steps like a hand. */
    private fun stroke(pts: List<Offset>) {
        if (pts.isEmpty()) return
        compose.onNodeWithTag("reader0").performTouchInput {
            down(at(pts[0].x, pts[0].y))
            if (pts.size == 1) moveBy(Offset(1.5f, 1.5f))
            // At least ~16 move events (a quarter second), so a short stroke isn't read as a tap.
            val total = (1 until pts.size).sumOf { (pts[it] - pts[it - 1]).getDistance().toDouble() }.toFloat()
            val step = (total / 16f).coerceIn(1f, 9f)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                val n = (((b - a).getDistance()) / step).toInt().coerceAtLeast(1)
                for (k in 1..n) {
                    val p = a + (b - a) * (k / n.toFloat())
                    moveTo(at(p.x + j(), p.y + j()))
                }
            }
            up()
        }
        compose.waitForIdle()
    }

    private val font: Map<Char, List<List<Pair<Float, Float>>>> = mapOf(
        'A' to listOf(listOf(0f to 1f, .3f to 0f, .6f to 1f, .48f to .62f, .12f to .62f)),
        'B' to listOf(listOf(0f to 1f, 0f to 0f, .42f to 0f, .55f to .12f, .55f to .36f, .42f to .48f, 0f to .48f, .42f to .48f, .6f to .62f, .6f to .86f, .45f to 1f, 0f to 1f)),
        'D' to listOf(listOf(0f to 0f, 0f to 1f, .35f to 1f, .6f to .75f, .6f to .25f, .35f to 0f, 0f to 0f)),
        'E' to listOf(listOf(.6f to 0f, 0f to 0f, 0f to .5f, .45f to .5f, 0f to .5f, 0f to 1f, .6f to 1f)),
        'F' to listOf(listOf(.6f to 0f, 0f to 0f, 0f to .5f, .45f to .5f, 0f to .5f, 0f to 1f)),
        'G' to listOf(listOf(.6f to .15f, .45f to 0f, .15f to 0f, 0f to .2f, 0f to .8f, .15f to 1f, .45f to 1f, .6f to .8f, .6f to .55f, .35f to .55f)),
        'H' to listOf(listOf(0f to 0f, 0f to 1f, 0f to .5f, .6f to .5f, .6f to 0f, .6f to 1f)),
        'I' to listOf(listOf(.3f to 0f, .3f to 1f)),
        'L' to listOf(listOf(0f to 0f, 0f to 1f, .55f to 1f)),
        'M' to listOf(listOf(0f to 1f, 0f to 0f, .3f to .55f, .6f to 0f, .6f to 1f)),
        'N' to listOf(listOf(0f to 1f, 0f to 0f, .6f to 1f, .6f to 0f)),
        'O' to listOf((0..16).map { val t = it / 16f * 2 * PI; (.3f + .3f * sin(t).toFloat()) to (.5f - .5f * cos(t).toFloat()) }),
        'P' to listOf(listOf(0f to 1f, 0f to 0f, .45f to 0f, .6f to .15f, .6f to .35f, .45f to .5f, 0f to .5f)),
        'R' to listOf(listOf(0f to 1f, 0f to 0f, .45f to 0f, .6f to .15f, .6f to .35f, .45f to .5f, 0f to .5f, .3f to .5f, .6f to 1f)),
        'S' to listOf(listOf(.6f to .12f, .45f to 0f, .15f to 0f, 0f to .15f, 0f to .35f, .15f to .48f, .45f to .52f, .6f to .65f, .6f to .85f, .45f to 1f, .15f to 1f, 0f to .88f)),
        'T' to listOf(listOf(0f to 0f, .6f to 0f, .3f to 0f, .3f to 1f)),
        'U' to listOf(listOf(0f to 0f, 0f to .8f, .15f to 1f, .45f to 1f, .6f to .8f, .6f to 0f)),
        'W' to listOf(listOf(0f to 0f, .15f to 1f, .3f to .45f, .45f to 1f, .6f to 0f)),
        'Y' to listOf(listOf(0f to 0f, .3f to .5f, .6f to 0f, .3f to .5f, .3f to 1f)),
        '1' to listOf(listOf(.15f to .2f, .35f to 0f, .35f to 1f)),
        '2' to listOf(listOf(0f to .2f, .15f to 0f, .45f to 0f, .6f to .2f, .6f to .4f, 0f to 1f, .6f to 1f)),
        '3' to listOf(listOf(0f to .1f, .15f to 0f, .45f to 0f, .6f to .15f, .6f to .35f, .4f to .5f, .6f to .65f, .6f to .85f, .45f to 1f, .15f to 1f, 0f to .9f)),
        '4' to listOf(listOf(.45f to 1f, .45f to 0f, 0f to .65f, .6f to .65f)),
        '6' to listOf(listOf(.55f to .05f, .3f to 0f, .05f to .25f, 0f to .7f, .15f to 1f, .45f to 1f, .6f to .8f, .6f to .6f, .45f to .45f, .15f to .45f, 0f to .6f)),
        ':' to listOf(listOf(.2f to .3f), listOf(.2f to .85f)),
        '!' to listOf(listOf(.2f to 0f, .2f to .65f), listOf(.2f to .95f)),
        '=' to listOf(listOf(0f to .35f, .5f to .35f), listOf(0f to .7f, .5f to .7f)),
    )

    /** Hand-lettered capitals, [h] pixels tall, slightly slanted. */
    private fun write(text: String, x0: Float, y0: Float, h: Float) {
        var x = x0
        for (c in text) {
            if (c == ' ') { x += h * .55f; continue }
            val g = font[c] ?: continue
            for (s in g) stroke(s.map { (u, v) -> Offset(x + u * h + (1 - v) * h * .12f, y0 + v * h) })
            x += h * (if (c == 'I' || c == ':' || c == '!' || c == '1') .45f else .85f)
        }
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, turns: Float = 1.08f, start: Double = -2.4) =
        (0..40).map { val t = start + it / 40.0 * 2 * PI * turns; Offset(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat()) }

    private fun pen(color: Int, size: Int = 2) = compose.runOnUiThread { vm.tool = Tool.PEN; vm.penColor = color; vm.penSize = size }

    private fun open(book: Int, chapter: Int) {
        compose.runOnUiThread {
            vm.changeWritingSounds(false); vm.chat.changeEnabled(false)
            vm.theme = com.biblestudy.app.ui.PageTheme.DARK; vm.fingerDraw = true; vm.readMode = false
            while (vm.panels.size > 1) vm.closePanel(vm.panels.lastIndex)
            vm.sidePane = null
            vm.setVersion(0, "KJV"); vm.goTo(0, book, chapter, 1, remember = false)
        }
        waitForLoaded()
    }

    private val GOLD = PEN_COLORS[5]   // orange, drawn warm gold on the dark page
    private val BLUE = PEN_COLORS[1]
    private val WHITE = PEN_COLORS[7]
    private val RED = PEN_COLORS[2]

    @Test
    fun john3() {
        open(43, 3)
        snap("john3-0")
        // Highlight "of water and of the Spirit" (verse 5) with the snapping highlighter.
        compose.runOnUiThread { vm.tool = Tool.HIGHLIGHTER; vm.highlightColor = HIGHLIGHT_COLORS[0]; vm.snapHighlights = true; vm.highlightSize = 1 }
        stroke(listOf(Offset(200f, 1322f), Offset(1440f, 1322f)))
        snap("john3-1")
        // Circle "born again" (verse 3).
        pen(GOLD)
        stroke(ellipse(600f, 990f, 125f, 42f))
        snap("john3-2")
        // Margin: a moon and stars by "came to Jesus by night".
        pen(WHITE)
        stroke((0..14).map { val t = -1.2 + it / 14.0 * 2.4; Offset(1800f + 46f * cos(t).toFloat(), 610f + 46f * sin(t).toFloat()) }.reversed())
        stroke((0..14).map { val t = -1.0 + it / 14.0 * 2.0; Offset(1822f + 34f * cos(t).toFloat(), 610f + 40f * sin(t).toFloat()) })
        for ((sx, sy) in listOf(1900f to 570f, 1940f to 640f, 1745f to 560f)) {
            stroke(listOf(Offset(sx - 20, sy), Offset(sx + 20, sy), Offset(sx, sy), Offset(sx, sy - 20), Offset(sx, sy + 20)))
        }
        write("BY NIGHT", 1990f, 585f, 40f)
        snap("john3-3")
        // "NEW BIRTH!" with an arrow to the circled words.
        pen(GOLD)
        write("NEW BIRTH!", 1720f, 900f, 46f)
        compose.onNodeWithTag("reader0").performTouchInput {
            down(at(1700f, 928f))
            repeat(16) { moveBy(Offset(-60.3f, 1.3f)) }
            repeat(3) { moveBy(Offset(14f, -14f)) }
            advanceEventTime(800); moveBy(Offset(1f, 0f)); up()
        }
        compose.waitForIdle()
        snap("john3-4")
        // A cross on a hill with light around it, beside water and Spirit.
        pen(WHITE)
        stroke(listOf(Offset(1880f, 1150f), Offset(1880f, 1420f)))
        stroke(listOf(Offset(1810f, 1215f), Offset(1950f, 1215f)))
        stroke((0..20).map { val t = PI + it / 20.0 * PI; Offset(1880f + 190f * cos(t).toFloat(), 1450f + 50f * sin(t).toFloat()) })
        pen(GOLD)
        for (k in 0 until 8) {
            val a = -PI / 2 + (k - 3.5) * 0.36
            stroke(listOf(Offset(1880f + 105f * cos(a).toFloat(), 1215f + 105f * sin(a).toFloat()), Offset(1880f + 150f * cos(a).toFloat(), 1215f + 150f * sin(a).toFloat())))
        }
        // Wind swirls (verse 8: the wind bloweth where it listeth).
        pen(BLUE)
        snap("john3-5")
        // Wind: three gusts, each ending in a curl.
        for ((k, y) in listOf(1180f, 1250f, 1320f).withIndex()) {
            val x0 = 2040f + k * 25f
            val line = (0..20).map { Offset(x0 + it * 13f, y + 7f * sin(it / 2.5).toFloat()) }
            val end = line.last()
            val curl = (1..14).map { val t = -PI / 2 + it / 14.0 * 1.6 * PI; val r = 26f - it; Offset(end.x + r * cos(t).toFloat(), end.y - 26f + 26f + r * sin(t).toFloat() + 0f) }
            stroke(line + curl)
        }
        write("BORN OF", 2040f, 1395f, 38f)
        write("THE SPIRIT", 2040f, 1455f, 38f)
        snap("john3-sketched")
    }

    @Test
    fun psalm23() {
        open(19, 23)
        snap("psalm23-0")
        // A shepherd's crook and a sheep, with the line lettered beside them.
        pen(GOLD)
        stroke(listOf(Offset(1800f, 1250f), Offset(1800f, 640f)) + (0..14).map { val t = PI + it / 14.0 * PI * 1.15; Offset(1845f + 45f * cos(t).toFloat(), 640f + 45f * sin(t).toFloat()) })
        pen(WHITE)
        stroke((0..36).map { val t = it / 36.0 * 2 * PI; val w = 1f + .12f * sin(t * 6).toFloat(); Offset(2020f + 90f * w * cos(t).toFloat(), 1080f + 55f * w * sin(t).toFloat()) })
        stroke(ellipse(2125f, 1050f, 30f, 24f, 1.0f, 0.0))
        for (lx in listOf(1975f, 2005f, 2045f, 2075f)) stroke(listOf(Offset(lx, 1128f), Offset(lx, 1180f)))
        stroke(listOf(Offset(1890f, 1200f), Offset(2260f, 1200f)))
        pen(GOLD)
        write("MY SHEPHERD", 1900f, 690f, 44f)
        write("I SHALL NOT WANT", 1890f, 770f, 34f)
        pen(BLUE)
        // Still waters: three gentle waves.
        for (k in 0 until 3) stroke((0..24).map { Offset(1900f + it * 14f, 1300f + k * 34f + 8f * sin(it / 3.0).toFloat()) })
        write("STILL WATERS", 1905f, 1415f, 36f)
        snap("psalm23-sketched")
    }

    @Test
    fun isaiah53() {
        open(23, 53)
        pen(RED)
        stroke(listOf(Offset(1900f, 560f), Offset(1900f, 900f)))
        stroke(listOf(Offset(1810f, 650f), Offset(1990f, 650f)))
        pen(WHITE)
        stroke((0..30).map { val t = it / 30.0 * 2 * PI; Offset(1900f + 30f * cos(t).toFloat() + 6f * sin(t * 7).toFloat(), 610f + 16f * sin(t).toFloat()) })
        pen(GOLD)
        write("BY HIS STRIPES", 2010f, 640f, 34f)
        write("WE ARE HEALED", 2010f, 700f, 34f)
        write("1 PET 2:24", 2010f, 790f, 40f)
        snap("isaiah53-sketched")
    }
}
