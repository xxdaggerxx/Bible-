package com.biblestudy.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.biblestudy.app.ui.PaneKind
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performImeAction
import com.biblestudy.app.model.VerseTarget
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.biblestudy.app.data.RefLinks
import com.biblestudy.app.model.ChapterData
import com.biblestudy.app.ui.buildChapterLayout
import androidx.lifecycle.ViewModelProvider
import com.biblestudy.app.model.SearchScope
import com.biblestudy.app.model.Tool
import com.biblestudy.app.ui.Page
import com.biblestudy.app.ui.StudyViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.biblestudy.app.model.Region
import com.biblestudy.app.ui.LineSpacing
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
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

    /** On failure, prints the state objects written most often during the test (to find update loops). */
    @get:Rule
    val writes = object : org.junit.rules.TestWatcher() {
        val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        var handle: androidx.compose.runtime.snapshots.ObserverHandle? = null
        var flusher: androidx.compose.runtime.snapshots.ObserverHandle? = null
        override fun starting(d: org.junit.runner.Description) {
            counts.clear()
            // Apply state changes made outside composition, as Compose's GlobalSnapshotManager does
            // on a device. In a long Robolectric run that manager stops doing it after a few tests,
            // leaving changes pending so Compose never reports idle.
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val posted = java.util.concurrent.atomic.AtomicBoolean(false)
            flusher = androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver {
                if (posted.compareAndSet(false, true)) main.post {
                    posted.set(false)
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                }
            }
            handle = androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { changed, _ ->
                for (c in changed) {
                    val k = c.toString().take(160)
                    counts.merge(k, 1, Int::plus)
                }
            }
        }
        override fun failed(e: Throwable, d: org.junit.runner.Description) {
            val out = StringBuilder("STATE WRITES in ${d.methodName}:\n")
            counts.entries.sortedByDescending { it.value }.take(12).forEach { out.append("  ${it.value}  ${it.key}\n") }
            runCatching {
                val m = compose.activity.let { ViewModelProvider(it)[StudyViewModel::class.java] }
                out.append("  pendingLoads=${m.pendingLoads} sidePane=${m.sidePane} active=${m.activePanel} max=${m.maxPanels}\n")
                m.panels.forEach { p ->
                    out.append("  panel ${p.version} ${p.book}:${p.chapter} top=${p.topVerse} pending=${p.pendingVerse} nav=${p.navGen} " +
                        "view=${p.viewW}x${p.viewH} zoom=${p.zoom} pan=${p.panX},${p.panY}\n")
                }
            }.onFailure { out.append("  vm: $it\n") }
            runCatching {
                val global = Class.forName("android.view.WindowManagerGlobal")
                val instance = global.getMethod("getInstance").invoke(null)
                @Suppress("UNCHECKED_CAST")
                val roots = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>
                out.append("  windows: ${roots.size}\n")
                for (r in roots) out.append("    ${r.javaClass.simpleName} attached=${r.isAttachedToWindow} shown=${r.isShown} ctx=${r.context}\n")
                fun walk(v: android.view.View, depth: Int) {
                    val cls = v.javaClass.name
                    if (cls.contains("AndroidComposeView")) {
                        val pending = runCatching {
                            v.javaClass.getMethod("getHasPendingMeasureOrLayout").invoke(v)
                        }.getOrElse { "?" }
                        out.append("      compose view ${v.width}x${v.height} attached=${v.isAttachedToWindow} pendingLayout=$pending layoutRequested=${v.isLayoutRequested}\n")
                    }
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
                }
                for (r in roots) walk(r, 0)
            }
            runCatching {
                // Tasks waiting on Compose's test scheduler (a coroutine that keeps rescheduling itself shows here).
                val clock = compose.mainClock
                var sched: Any? = null
                var c: Class<*>? = clock.javaClass
                while (c != null && sched == null) {
                    for (f in c.declaredFields) {
                        f.isAccessible = true
                        val v = f.get(clock)
                        if (v != null && v.javaClass.name.contains("TestCoroutineScheduler")) sched = v
                    }
                    c = c.superclass
                }
                out.append("  scheduler: ${sched?.javaClass?.name}\n")
                val events = sched!!.javaClass.getDeclaredField("events").apply { isAccessible = true }.get(sched)
                var ec: Class<*>? = events.javaClass
                var af: java.lang.reflect.Field? = null
                while (ec != null && af == null) { af = ec.declaredFields.firstOrNull { it.name == "a" }; ec = ec.superclass }
                val arr = af!!.apply { isAccessible = true }.get(events) as Array<*>?
                arr?.filterNotNull()?.take(10)?.forEach { e ->
                    val fields = e.javaClass.declaredFields.joinToString { f -> f.isAccessible = true; "${f.name}=${f.get(e)}".take(300) }
                    out.append("    event: $fields\n")
                }
            }.onFailure { out.append("  scheduler: $it\n") }
            runCatching {
                // Compose roots created but never attached keep Compose "busy" forever.
                fun fieldOf(o: Any, type: String): Any? {
                    var c: Class<*>? = o.javaClass
                    while (c != null) {
                        for (f in c.declaredFields) {
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            if (v.javaClass.name.contains(type)) return v
                        }
                        c = c.superclass
                    }
                    return null
                }
                val env = fieldOf(compose, "AndroidComposeUiTestEnvironment")!!
                val reg = fieldOf(env, "ComposeRootRegistry")!!
                @Suppress("UNCHECKED_CAST")
                val created = reg.javaClass.getMethod("getCreatedComposeRoots").invoke(reg) as Set<Any>
                out.append("  created roots: ${created.size}\n")
                val idling = fieldOf(env, "ComposeIdlingResource")
                if (idling != null) {
                    val idle = idling.javaClass.getMethod("isIdleNow").invoke(idling)
                    val flags = idling.javaClass.declaredFields.filter { it.type == java.lang.Boolean.TYPE }
                        .joinToString { f -> f.isAccessible = true; "${f.name}=${f.get(idling)}" }
                    out.append("  idle=$idle $flags\n")
                    val rec = fieldOf(idling, "Recomposer")
                    if (rec != null) {
                        out.append("  recomposer pendingWork=${rec.javaClass.getMethod("getHasPendingWork").invoke(rec)}\n")
                    }
                    out.append("  snapshot pending=${androidx.compose.runtime.snapshots.Snapshot.current.hasPendingChanges()}\n")
                } else out.append("  no idling resource on env\n")
                for (r in created) {
                    val v = r as android.view.View
                    out.append("    ${v.javaClass.simpleName} attached=${v.isAttachedToWindow} parent=${v.parent?.javaClass?.simpleName} ctx=${v.context.javaClass.simpleName}\n")
                }
            }.onFailure { out.append("  roots: $it\n") }
            File("build/state-writes.txt").appendText(out.toString())
        }
        override fun finished(d: org.junit.runner.Description) { handle?.dispose(); flusher?.dispose() }
    }

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
            while (vm.panels.size > 1) vm.closePanel(vm.panels.lastIndex)
            vm.linkPanels = false
            vm.setVersion(0, "KJV")
            vm.goTo(0, 43, 3, remember = false)
            vm.partialEraser = false
            vm.highlightsAllVersions = true
            vm.sidePane = null
            vm.paneVerse = null
            vm.compareVersions = false
            vm.readMode = false
            vm.underlineMode = false
            vm.changeTextFont(com.biblestudy.app.model.TextFont.BOOK)
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
            vm.textsFor(43, 3).toList().forEach { vm.removeItem(it) }
        }
        compose.waitForIdle()
    }

    /**
     * Finish each test with nothing loading in the background and a single panel, so the next test's
     * app doesn't inherit half-finished work (which stalls it) or a split view.
     */
    @After
    fun settle() {
        waitForLoaded()
        compose.runOnUiThread {
            vm.linkPanels = false
            while (vm.panels.size > 1) vm.closePanel(vm.panels.lastIndex)
        }
        waitForLoaded()
    }

    private fun readerSize() = compose.onNodeWithTag("reader0").fetchSemanticsNode().size

    /** Page units to reader pixels at fit-width, with only the right margin shown. */
    private fun zoom(): Float = readerSize().width / (Page.COL_W + vm.marginWidth(left = false))

    private fun snap(name: String) {
        compose.waitForIdle()
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        // Dialogs and pop-overs are separate windows: draw each one above the app, in stacking order.
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val roots = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>
        for (root in roots) {
            if (root === view || !root.isShown || root.width == 0) continue
            if (root === ShadowDialog.getLatestDialog()?.window?.decorView) canvas.drawColor(0x66000000)
            val at = IntArray(2).also { root.getLocationOnScreen(it) }
            val params = root.layoutParams as? android.view.WindowManager.LayoutParams
            val x = if (at[0] != 0 || params == null) at[0] else params.x
            val y = if (at[1] != 0 || params == null) at[1] else params.y
            canvas.save()
            // Robolectric doesn't place dialog windows; centre those.
            if (x == 0 && y == 0 && root.width < view.width) canvas.translate((view.width - root.width) / 2f, (view.height - root.height) / 2f)
            else canvas.translate(x.toFloat(), y.toFloat())
            root.draw(canvas)
            canvas.restore()
        }
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

    @Test
    fun bookPickerMarksWhereNotesAreOnVisibleLayers() {
        // Draw on John 3 on the default layer.
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.PEN }
        val z = zoom()
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset((Page.COL_PAD + 100f) * z, 620f))
            repeat(10) { moveBy(Offset(20f, 0f)) }
            up()
        }
        compose.waitForIdle()
        val verse = vm.textStrokesFor("KJV", 43, 3).single().verse
        compose.runOnUiThread { vm.fingerDraw = false }

        // Book, chapter and verse are all marked.
        compose.onNodeWithText("John 3").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("John has notes").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Genesis has notes").assertCountEquals(0)
        snap("15-picker-books")
        compose.onNodeWithText("John").performClick()
        compose.onNodeWithContentDescription("John 3 has notes").assertExists()
        compose.onAllNodesWithContentDescription("John 4 has notes").assertCountEquals(0)
        compose.onNodeWithText("3").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("John 3:$verse has notes").fetchSemanticsNodes().isNotEmpty() }
        snap("16-picker-verses")

        // Hiding the layer hides its markers.
        compose.runOnUiThread { vm.setAllLayersVisible(false) }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("John 3:$verse has notes").assertCountEquals(0)
        compose.onNodeWithContentDescription("Back to chapters").performClick()
        compose.onNodeWithContentDescription("Back to books").performClick()
        compose.onAllNodesWithContentDescription("John has notes").assertCountEquals(0)
        compose.onNodeWithContentDescription("Close").performClick()

        // Typed notes aren't on a layer, so they are always marked.
        compose.runOnUiThread { vm.setNote(com.biblestudy.app.model.VerseTarget(19, 23, 1), "The Lord is my shepherd") }
        compose.waitUntil(5_000) { vm.user.notedVerses().contains(19023001) }
        compose.onNodeWithText("John 3").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Psalms has notes").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread {
            vm.setAllLayersVisible(true)
            vm.setNote(com.biblestudy.app.model.VerseTarget(19, 23, 1), "")
        }
    }

    @Test
    fun linkedPanelsScrollTogetherByVerse() {
        compose.runOnUiThread {
            vm.toggleSplit()
            vm.setVersion(1, "WEB")
        }
        waitForLoaded()
        compose.onAllNodesWithContentDescription("Link panels")[0].performClick()
        waitForLoaded()
        assertTrue(vm.linked)

        // Scroll the left (KJV) panel into John 4: the right (WEB) panel follows, verse for verse.
        var swipes = 0
        while (vm.panels[0].chapter == 3 && swipes < 40) {
            compose.onNodeWithTag("reader0").performTouchInput { swipeUp(durationMillis = 300) }
            compose.waitForIdle()
            swipes++
        }
        waitForLoaded()
        assertEquals(4, vm.panels[0].chapter)
        assertEquals(4, vm.panels[1].chapter)
        assertTrue(
            "top verses ${vm.panels[0].topVerse} and ${vm.panels[1].topVerse}",
            kotlin.math.abs(vm.panels[0].topVerse - vm.panels[1].topVerse) <= 1,
        )
        snap("17-linked-panels")

        // A jump in one panel takes the other along (Psalm 119 is long enough to put verse 50 at the top).
        compose.runOnUiThread { vm.goTo(0, 19, 119, 50) }
        waitForLoaded()
        assertEquals(19 to 119, vm.panels[1].book to vm.panels[1].chapter)
        assertTrue("right panel at ${vm.panels[1].topVerse}", kotlin.math.abs(vm.panels[1].topVerse - 50) <= 1)

        // Unlinked, the panels move independently.
        compose.onAllNodesWithContentDescription("Unlink panels")[0].performClick()
        compose.runOnUiThread { vm.goTo(0, 43, 1) }
        waitForLoaded()
        assertEquals(19 to 119, vm.panels[1].book to vm.panels[1].chapter)
    }

    @Test
    fun headingLinksOpenAPassagePopover() {
        compose.runOnUiThread { vm.goTo(0, 43, 1) }
        waitForLoaded()
        // Lay out John 1 the way the reader does, to find the "Genesis 1:1\u20132" link under its first heading.
        val measurer = TextMeasurer(createFontFamilyResolver(compose.activity), Density(1f, 1f), LayoutDirection.Ltr)
        val font = FontFamily(Font(R.font.gentium_book_plus_regular), Font(R.font.gentium_book_plus_bold, FontWeight.Bold))
        val layout = buildChapterLayout(
            measurer, font, "John", ChapterData("KJV", 43, 1, vm.text("KJV").chapter(43, 1), vm.headings(43, 1)), vm.lineSpacing,
        ) { RefLinks.parseList(it, vm.bible.books) }
        val block = layout.headings.first()
        val (lineIndex, links) = block.links.entries.first()
        val link = links.first()
        assertEquals("Genesis 1:1\u20132", vm.passageLabel(link.passage))
        val box = block.lines[lineIndex].getBoundingBox((link.start + link.end) / 2)
        val z = vm.panels[0].zoom
        val x = (Page.COL_PAD + box.center.x) * z
        val y = (Page.TEXT_TOP + layout.headingLineTops(block)[lineIndex] + box.center.y) * z + vm.panels[0].panY
        compose.onNodeWithTag("reader0").performTouchInput { click(Offset(x, y)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Genesis 1:1\u20132 (KJV)").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("In the beginning", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("the earth was without form", substring = true).assertCountEquals(1)
        snap("18-passage-popover")

        // Open beside: split view with Genesis 1 in the other panel, John 1 still here.
        compose.onNodeWithText("Open beside").performClick()
        waitForLoaded()
        assertEquals(2, vm.panels.size)
        assertEquals(1 to 1, vm.panels[1].book to vm.panels[1].chapter)
        assertEquals(43 to 1, vm.panels[0].book to vm.panels[0].chapter)
        assertNull(vm.passagePop)
    }

    @Test
    fun referencesInNotesAreLinks() {
        compose.runOnUiThread { vm.openVerse(43, 3, 16) }
        compose.waitForIdle()
        // (References chosen so they aren't also in this verse's cross-reference list.)
        compose.onNode(hasSetTextAction()).performTextInput("Like Ruth 1:16, and Ps 23.")
        compose.onNodeWithText("Ruth 1:16").assertExists()
        compose.onNodeWithText("Psalms 23").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("is my shepherd", substring = true).fetchSemanticsNodes().isNotEmpty() }
        snap("19-note-link")
        compose.onNodeWithContentDescription("Close passage").performClick()
        compose.onNodeWithText("Ruth 1:16").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Intreat me not to leave thee", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Go to").performClick()
        waitForLoaded()
        assertEquals(8 to 1, vm.panels[0].book to vm.panels[0].chapter)
        compose.runOnUiThread { vm.setNote(com.biblestudy.app.model.VerseTarget(43, 3, 16), "") }
    }

    @Test
    fun longPressOnHighlightedTextStillShowsTheBar() {
        val z = zoom()
        val at = Offset((Page.COL_PAD + 250f) * z, 600f)
        // Highlight a word with a long press, then long-press the same, now highlighted, word.
        compose.onNodeWithTag("reader0").performTouchInput { longClick(at) }
        compose.onNodeWithText("Highlight").performClick()
        compose.waitForIdle()
        assertEquals(1, vm.highlightsFor("KJV", 43, 3).size)
        compose.onNodeWithTag("reader0").performTouchInput { longClick(at) }
        compose.waitForIdle()
        compose.onNodeWithText("Copy").assertExists()
        compose.onNodeWithText("Share").assertExists()
        compose.onNodeWithText("Note").assertExists()
    }

    /** Highlights a word with a long press and returns the new highlight. */
    private fun highlightWordAt(at: Offset): com.biblestudy.app.model.Highlight {
        compose.onNodeWithTag("reader0").performTouchInput { longClick(at) }
        compose.onNodeWithText("Highlight").performClick()
        compose.waitForIdle()
        return vm.highlightsFor(vm.panels[0].version, 43, 3).last()
    }

    @Test
    fun longPressOnAHighlightRecoloursOrRemovesIt() {
        val at = Offset((Page.COL_PAD + 250f) * zoom(), 600f)
        val h = highlightWordAt(at)
        compose.onNodeWithTag("reader0").performTouchInput { longClick(at) }
        compose.waitForIdle()
        compose.onNodeWithText("Remove highlight").assertExists()
        compose.onNodeWithText("Highlight").assertDoesNotExist()
        snap("40-highlight-edit")

        // Pick another colour: the same highlight changes colour, and undo changes it back.
        val colours = compose.onAllNodesWithContentDescription("Highlight colour")
        colours[1].performClick()
        compose.waitForIdle()
        val recoloured = vm.highlightsFor("KJV", 43, 3).single()
        assertEquals(h.id, recoloured.id)
        assertTrue(recoloured.color != h.color)
        compose.runOnUiThread { vm.undo() }
        compose.waitForIdle()
        assertEquals(h.color, vm.highlightsFor("KJV", 43, 3).single().color)

        compose.onNodeWithText("Remove highlight").performClick()
        compose.waitForIdle()
        assertEquals(0, vm.highlightsFor("KJV", 43, 3).size)
        compose.runOnUiThread { vm.undo() }
        compose.waitForIdle()
        assertEquals(1, vm.highlightsFor("KJV", 43, 3).size)
    }

    @Test
    fun highlightsShowAsWholeVersesInOtherVersions() {
        val at = Offset((Page.COL_PAD + 250f) * zoom(), 600f)
        val h = highlightWordAt(at)
        // Start from the top of the chapter, so verse 1 is where the long press below expects it.
        compose.runOnUiThread { vm.setVersion(0, "BSB"); vm.goTo(0, 43, 3, remember = false) }
        waitForLoaded()
        val cross = vm.crossHighlights("BSB", 43, 3)
        assertEquals(1, cross.size)
        assertEquals(h.id, cross[0].source.id)
        assertEquals(cross[0].fromVerse, cross[0].toVerse)
        snap("41-highlight-in-bsb")

        // Holding a finger on it in the BSB selects the whole verse and can change the KJV highlight.
        // The BSB's heading pushes verse 1 down, so look for it: a long press elsewhere shows the
        // ordinary bar, which Copy dismisses.
        var y = 60f
        while (true) {
            compose.onNodeWithTag("reader0").performTouchInput { longClick(Offset(at.x, y)) }
            compose.waitForIdle()
            if (compose.onAllNodesWithText("Remove highlight").fetchSemanticsNodes().isNotEmpty()) break
            if (compose.onAllNodesWithText("Copy").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Copy").performClick()
            y += 25f
            compose.mainClock.advanceTimeBy(600) // so the next press isn't taken as a double-tap
            assertTrue("no whole-verse highlight found", y < 700f)
        }
        compose.onNodeWithText("Remove highlight").performClick()
        compose.waitForIdle()
        assertEquals(0, vm.highlightsFor("KJV", 43, 3).size)
        compose.runOnUiThread { vm.undo() }
        compose.waitForIdle()

        // The setting turns it off.
        compose.runOnUiThread { vm.highlightsAllVersions = false }
        assertTrue(vm.crossHighlights("BSB", 43, 3).isEmpty())
    }

    @Test
    fun highlightsListShowsEveryHighlightAndGoesThere() {
        val h = highlightWordAt(Offset((Page.COL_PAD + 250f) * zoom(), 600f))
        compose.runOnUiThread { vm.goTo(0, 1, 1, remember = false) }
        waitForLoaded()
        compose.onNodeWithContentDescription("Bookmarks").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Highlights").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("John 3:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        snap("42-highlights-list")
        compose.onAllNodesWithText("John 3:", substring = true)[0].performClick()
        waitForLoaded()
        assertEquals(43, vm.panels[0].book)
        assertEquals(3, vm.panels[0].chapter)
        assertEquals(h.version, vm.panels[0].version)
    }

    @Test
    fun verseWindowComparesEveryVersion() {
        compose.runOnUiThread { vm.openVerse(43, 3, 16) }
        compose.onNodeWithText("Compare versions").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("one and only Son", substring = true).assertExists()
        compose.onNodeWithText("only born Son", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithText("only begotten Son", substring = true).fetchSemanticsNodes().isNotEmpty())
        snap("43-compare-versions")
        compose.onNodeWithText("WEB").performClick()
        waitForLoaded()
        assertEquals("WEB", vm.panels[0].version)
    }

    @Test
    fun bookIntroductionsOpenFromThePickerAndHeader() {
        compose.onNodeWithText("John 3").performClick()
        compose.onNodeWithContentDescription("About Romans").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("About Romans").assertExists()
        compose.onNodeWithText("Historical background").assertExists()
        snap("44-book-intro")
        // An outline section opens the book there and closes the picker.
        compose.onNodeWithText("Made right by faith").performScrollTo().performClick()
        waitForLoaded()
        assertEquals(45, vm.panels[0].book)
        assertEquals(3, vm.panels[0].chapter)
        compose.onNodeWithText("Choose a book").assertDoesNotExist()

        // The chapter header opens the current book's introduction.
        compose.onNodeWithContentDescription("About this book").performClick()
        compose.onNodeWithText("About Romans").assertExists()
    }

    @Test
    fun searchLeavesOutMinusWordsAndGroupsByBook() {
        val all = vm.text("KJV").search("loved", SearchScope.ALL, 43)
        val without = vm.text("KJV").search("loved -world", SearchScope.ALL, 43)
        assertTrue(without.isNotEmpty() && without.size < all.size)
        assertTrue(without.none { it.text.contains("world", ignoreCase = true) })

        compose.onNodeWithContentDescription("Search").performScrollTo().performClick()
        compose.onNodeWithText("Words", substring = true).performTextInput("loved -world")
        compose.onNodeWithText("Words", substring = true).performImeAction()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("verses in", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("John 3:16").assertDoesNotExist() // "the world" is left out
        snap("45-search-grouped")
        compose.onNodeWithText("Genesis \u2014 8").assertExists()
        // A book chip shows just that book.
        compose.onNodeWithText("Deuteronomy 4").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Deuteronomy \u2014 4").assertExists()
        compose.onNodeWithText("Genesis \u2014 8").assertDoesNotExist()
    }

    @Test
    fun aNoteCanCoverSeveralVerses() {
        compose.runOnUiThread { vm.setNote(VerseTarget(43, 3, 16), "") }
        compose.runOnUiThread { vm.openVerse(43, 3, 16) }
        compose.onNodeWithText("Typed note", substring = true).performTextInput("God's love for the world")
        compose.onNodeWithContentDescription("Note on one more verse").performClick()
        compose.onNodeWithContentDescription("Note on one more verse").performClick()
        compose.onNodeWithText("Note on John 3:16\u201318").assertExists()
        snap("46-range-note")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { vm.user.noteCovering(43, 3, 17) != null }
        val n = vm.user.noteCovering(43, 3, 18)!!
        assertEquals(16, n.verse)
        assertEquals(18, n.endVerse)
        assertEquals(18, vm.notesFor(43, 3)[16]!!.endVerse)

        // Tapping a verse inside the range opens the same note.
        compose.runOnUiThread { vm.openVerse(43, 3, 17) }
        compose.onNodeWithText("God's love for the world").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnUiThread { vm.setNote(VerseTarget(43, 3, 16), "") }
        compose.waitForIdle()
    }

    @Test
    fun bookmarksCanBePutInFolders() {
        compose.runOnUiThread {
            vm.bookmarks.toList().forEach { vm.deleteBookmark(it) }
            vm.bookmarkFolders.toList().forEach { vm.deleteBookmarkFolder(it) }
            vm.toggleBookmark(VerseTarget(43, 3, 16))
            vm.toggleBookmark(VerseTarget(19, 23, 1))
        }
        compose.onNodeWithContentDescription("Bookmarks").performScrollTo().performClick()
        compose.onAllNodesWithContentDescription("Move to folder")[0].performClick()
        compose.onNodeWithText("New folder\u2026").performClick()
        compose.onNodeWithText("e.g. Sermon series, Promises").performTextInput("Psalms of trust")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals("Psalms of trust", vm.bookmarks.first { it.book == 19 }.folder)
        compose.onNodeWithText("Psalms of trust (1)").performClick()
        compose.onNodeWithText("Psalms 23:1", substring = true).assertExists()
        compose.onNodeWithText("John 3:16", substring = true).assertDoesNotExist()
        snap("47-bookmark-folders")
        // Deleting the folder keeps its bookmarks.
        compose.onNodeWithText("Delete folder").performClick()
        compose.waitForIdle()
        assertEquals(2, vm.bookmarks.size)
        assertTrue(vm.bookmarks.all { it.folder.isEmpty() })
        compose.runOnUiThread { vm.bookmarks.toList().forEach { vm.deleteBookmark(it) } }
    }

    @Test
    fun threeBiblePanelsFitOnALargeLandscapeScreen() {
        assertEquals(3, vm.maxPanels)
        compose.onNodeWithContentDescription("Panels").performScrollTo().performClick()
        compose.onNodeWithText("Add a Bible panel").performClick()
        compose.onNodeWithContentDescription("Panels").performClick()
        compose.onNodeWithText("Add a Bible panel").performClick()
        waitForLoaded()
        assertEquals(3, vm.panels.size)
        for (i in 0..2) compose.onNodeWithTag("reader$i").assertExists()
        snap("48-three-panels")
        compose.onNodeWithContentDescription("Panels").performClick()
        compose.onNodeWithText("Add a Bible panel").assertIsNotEnabled()
        compose.onNodeWithText("Close other panels").performClick()
        waitForLoaded()
        assertEquals(1, vm.panels.size)
    }

    @Test
    fun theStudyPaneShowsCrossReferencesNotesAndSearch() {
        compose.runOnUiThread { vm.sidePane = PaneKind.CROSSREFS; vm.paneVerse = VerseTarget(43, 3, 16) }
        waitForLoaded()
        compose.onNodeWithTag("reader0").assertExists()
        compose.onNodeWithTag("pane").assertExists()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Romans 5:8").fetchSemanticsNodes().isNotEmpty() }
        snap("49-pane-crossrefs")
        // Opening a cross-reference moves the Bible panel; the pane stays.
        compose.onNodeWithText("Romans 5:8").performClick()
        waitForLoaded()
        assertEquals(45, vm.panels[0].book)
        compose.onNodeWithTag("pane").assertExists()

        // Notes in the chapter being read.
        compose.runOnUiThread {
            vm.goTo(0, 43, 3, remember = false)
            vm.setNote(VerseTarget(43, 3, 16), "The gospel in one verse")
            vm.sidePane = PaneKind.NOTES
        }
        waitForLoaded()
        compose.onNodeWithText("The gospel in one verse").assertExists()
        snap("50-pane-notes")
        compose.runOnUiThread { vm.setNote(VerseTarget(43, 3, 16), "") }

        // Search results kept beside the text.
        compose.runOnUiThread { vm.paneSearch = "\"only begotten\""; vm.sidePane = PaneKind.SEARCH }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("verses in", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("John 1:14").performClick()
        waitForLoaded()
        assertEquals(1, vm.panels[0].chapter)
        compose.onNodeWithText("John 1:18").assertExists() // results are still there
        snap("51-pane-search")
        compose.onNodeWithContentDescription("Close side pane").performClick()
        assertEquals(null, vm.sidePane)
    }

    @Test
    @Config(qualifiers = "w420dp-h800dp-port-xhdpi")
    fun marginsSlideInAsDrawersOnANarrowPortraitScreen() {
        waitForLoaded()
        compose.runOnUiThread { vm.marginRight = true; vm.marginLeft = false }
        waitForLoaded()
        // The text fills the width; the margin is tucked away behind a tab.
        compose.onNodeWithContentDescription("Show right margin").assertExists()
        val z = vm.panels[0].zoom
        assertEquals(readerSize().width / Page.COL_W, z, 0.01f)
        snap("52-drawer-closed")
        compose.onNodeWithContentDescription("Show right margin").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hide right margin").assertExists()
        snap("53-drawer-open")
        compose.onNodeWithContentDescription("Hide right margin").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Show right margin").assertExists()
    }

    @Test
    fun imagesCanComeFromCameraFilesOrClipboard() {
        compose.onNodeWithContentDescription("Insert").performScrollTo().performClick()
        for (label in listOf("from the gallery", "take a photo", "from files", "paste from clipboard")) {
            compose.onNodeWithText("Picture: $label").assertExists()
        }
        snap("54-image-sources")
        // Nothing on the clipboard: a friendly message rather than an error.
        compose.onNodeWithText("Picture: paste from clipboard").performClick()
        assertTrue(vm.message.orEmpty().contains("no picture on the clipboard"))
        // The camera gets a file it can write to through the app's FileProvider.
        val uri = vm.newCameraUri()
        assertEquals("content", uri.scheme)
        assertEquals(compose.activity.packageName + ".files", uri.authority)
    }

    @Test
    fun changingTheFontKeepsInkOnTheWords() {
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.PEN }
        val z = zoom()
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset((Page.COL_PAD + 200f) * z, 620f))
            repeat(6) { moveBy(Offset(15f, 0f)) }
            up()
        }
        compose.waitForIdle()
        val before = vm.textStrokesFor("KJV", 43, 3).single()
        assertEquals("BOOK", before.font)
        compose.runOnUiThread { vm.fingerDraw = false; vm.changeTextFont(com.biblestudy.app.model.TextFont.SANS) }
        waitForLoaded()
        compose.waitUntil(5_000) { vm.textStrokesFor("KJV", 43, 3).isNotEmpty() }
        val after = vm.textStrokesFor("KJV", 43, 3).single()
        assertEquals(before.id, after.id)
        assertEquals("SANS", after.font)
        snap("55-sans-font")
        // Back to the book font: the stroke comes back to (about) where it was drawn.
        compose.runOnUiThread { vm.changeTextFont(com.biblestudy.app.model.TextFont.BOOK) }
        waitForLoaded()
        compose.waitUntil(5_000) { vm.textStrokesFor("KJV", 43, 3).isNotEmpty() }
        val back = vm.textStrokesFor("KJV", 43, 3).single()
        assertEquals(before.points[0], back.points[0], 6f)
        assertEquals(before.points[1], back.points[1], 0.05f)
    }

    // ---------- screen sizes (ADP-6): small 8" and large 14.6" tablets, both ways round ----------

    private fun checkScreen(name: String, widthClass: com.biblestudy.app.ui.WidthClass, maxPanels: Int) {
        waitForLoaded()
        assertEquals(widthClass, vm.widthClass)
        assertEquals(maxPanels, vm.maxPanels)
        compose.onNodeWithTag("reader0").assertExists()
        compose.onNodeWithText("John 3").assertExists()
        compose.onNodeWithContentDescription("Next chapter").assertExists()
        snap("60-$name")
        compose.runOnUiThread { vm.addPanel(); vm.sidePane = PaneKind.CROSSREFS }
        waitForLoaded()
        compose.onNodeWithTag("reader1").assertExists()
        compose.onNodeWithTag("pane").assertExists()
        snap("61-$name-split")
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp-port-hdpi")
    fun small8InchTabletPortrait() = checkScreen("8in-portrait", com.biblestudy.app.ui.WidthClass.MEDIUM, 2)

    @Test
    @Config(qualifiers = "w960dp-h600dp-land-hdpi")
    fun small8InchTabletLandscape() = checkScreen("8in-landscape", com.biblestudy.app.ui.WidthClass.EXPANDED, 2)

    @Test
    @Config(qualifiers = "w1232dp-h1848dp-port-xhdpi")
    fun large14InchTabletPortrait() = checkScreen("14in-portrait", com.biblestudy.app.ui.WidthClass.EXPANDED, 2)

    @Test
    @Config(qualifiers = "w1848dp-h1232dp-land-xhdpi")
    fun large14InchTabletLandscape() = checkScreen("14in-landscape", com.biblestudy.app.ui.WidthClass.EXPANDED, 3)

    @Test
    fun toolbarFitsAndSettingsHoldTheRest() {
        // The toolbar fits without scrolling on the Tab S9 (UI-3): its last button is on screen.
        val more = compose.onNodeWithContentDescription("More").fetchSemanticsNode()
        val width = compose.activity.window.decorView.width
        assertTrue("More button at ${more.boundsInRoot.right} of $width", more.boundsInRoot.right <= width)
        snap("70-toolbar")

        // Colours and sizes open from one button.
        compose.onNodeWithContentDescription("Pen colour and size").performClick()
        compose.onNodeWithText("L").performClick()
        assertEquals(2, vm.penSize)
        compose.waitForIdle()

        // Settings: grouped options, applied at once.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Reading").assertExists()
        snap("71-settings")
        compose.onNodeWithText("Wide").performClick()
        assertEquals(LineSpacing.WIDE, vm.lineSpacing)
        compose.onNodeWithText("Draw with finger").performScrollTo().performClick()
        assertTrue(vm.fingerDraw)

        // Reset puts them back, without touching notes.
        compose.onNodeWithText("Reset settings to defaults").performScrollTo().performClick()
        compose.onNodeWithText("Reset").performClick()
        compose.waitForIdle()
        assertEquals(LineSpacing.NORMAL, vm.lineSpacing)
        assertEquals(false, vm.fingerDraw)
        assertEquals(1, vm.penSize)
    }

    @Test
    fun marginTextBoxesTurnReferencesIntoLinks() {
        compose.onNodeWithContentDescription("Insert").performClick()
        compose.onNodeWithText("Text box").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("textBoxEditor").performTextInput("Compare Rom 8:28 and Ps 23")
        snap("72-text-box-typing")
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        val box = vm.textsFor(43, 3).single()
        assertEquals("Compare Rom 8:28 and Ps 23", box.text)
        assertEquals(2, com.biblestudy.app.data.RefLinks.find(box.text, vm.bible.books).size)
        compose.waitUntil(5_000) { vm.user.searchNotes("Compare", 1, 66).isNotEmpty() } // in note search too

        // A finger tap on the box selects it; the bar offers its options.
        val z = zoom()
        val x = (Page.COL_W + 60f) * z
        var y = 40f
        while (compose.onAllNodesWithText("Edit").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("reader0").performTouchInput { click(Offset(x, y)) }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(600)
            y += 20f
            assertTrue("text box not found", y < 900f)
        }
        snap("73-text-box-selected")
        compose.onNodeWithText("A+").performClick()
        assertTrue(vm.textsFor(43, 3).single().size > box.size)

        // Undo works on it like other notes; Delete removes it.
        compose.runOnUiThread { vm.undo() }
        assertEquals(box.size, vm.textsFor(43, 3).single().size)
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertTrue(vm.textsFor(43, 3).isEmpty())
    }

    @Test
    fun lassoSelectionResizesFromItsCorner() {
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.PEN }
        val z = zoom()
        val mx = (Page.COL_W + 80f) * z // in the right margin
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset(mx, 500f)); repeat(10) { moveBy(Offset(10f, 6f)) }; up()
        }
        compose.waitForIdle()
        val before = vm.marginStrokesFor(43, 3).single()
        compose.runOnUiThread { vm.tool = Tool.LASSO }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset(mx - 40f, 460f)); moveBy(Offset(190f, 0f)); moveBy(Offset(0f, 140f)); moveBy(Offset(-190f, 0f)); moveBy(Offset(0f, -140f)); up()
        }
        compose.waitForIdle()
        assertEquals(setOf(before.id), vm.selection?.ids)
        snap("74-lasso-handle")
        // Drag the corner handle (just past the stroke's bottom-right) outwards: the ink doubles in size.
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset(mx + 100f + 24f, 560f + 24f)); repeat(10) { moveBy(Offset(12f, 7f)) }; up()
        }
        compose.waitForIdle()
        val after = vm.marginStrokesFor(43, 3).single()
        val w0 = before.points[27] - before.points[0]
        val w1 = after.points[27] - after.points[0]
        assertTrue("width $w0 -> $w1", w1 > w0 * 1.3f)
        assertTrue(after.width > before.width)
        compose.runOnUiThread { vm.undo() }
        assertEquals(before.points[27], vm.marginStrokesFor(43, 3).single().points[27], 0.01f)
    }

    @Test
    fun readModeStopsThePenMarkingThePage() {
        compose.onNodeWithContentDescription("Read mode off").performClick()
        assertTrue(vm.readMode)
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.PEN }
        val z = zoom()
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset((Page.COL_PAD + 100f) * z, 520f)); repeat(10) { moveBy(Offset(20f, 0f)) }; up()
        }
        compose.waitForIdle()
        assertTrue(vm.textStrokesFor("KJV", 43, 3).isEmpty())
        snap("75-read-mode")
        compose.onNodeWithContentDescription("Read mode on").performClick()
        assertEquals(false, vm.readMode)
    }

    @Test
    fun theHighlighterCanSnapAnUnderline() {
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.HIGHLIGHTER; vm.snapHighlights = true }
        compose.onNodeWithContentDescription("Highlighter colour and size").performClick()
        compose.onNodeWithText("Underline").performClick()
        compose.waitForIdle()
        assertTrue(vm.underlineMode)
        compose.mainClock.advanceTimeBy(600)
        val z = zoom()
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset((Page.COL_PAD + 60f) * z, 600f)); repeat(10) { moveBy(Offset(30f, 0f)) }; up()
        }
        compose.waitForIdle()
        val h = vm.highlightsFor("KJV", 43, 3).single()
        assertTrue(h.underline)
        snap("76-underline")
    }

    @Test
    fun holdingThePenSnapsAnArrowFromTheMarginAcrossTheText() {
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.PEN }
        val z = zoom()
        val start = Offset((Page.COL_W + 120f) * z, 560f) // in the right margin
        compose.onNodeWithTag("reader0").performTouchInput {
            down(start)
            repeat(20) { moveBy(Offset(-25f, 1.5f)) } // a slightly wobbly line into the text
            repeat(3) { moveBy(Offset(16f, -16f)) } // a flick back at the tip
            advanceEventTime(800) // hold still
            moveBy(Offset(1f, 0f))
            up()
        }
        compose.waitForIdle()
        val s = vm.marginStrokesFor(43, 3).single()
        assertEquals(Region.RIGHT, s.region)
        assertEquals(15, s.points.size) // a clean arrow: line plus two head strokes
        assertTrue("reaches across the text", s.points[3] < -50f) // tip well left of the margin (MRG-11)
        snap("77-arrow")
    }
}
