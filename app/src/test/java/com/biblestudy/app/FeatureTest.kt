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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTextClearance
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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.filter
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.graphics.asImageBitmap
import com.biblestudy.app.ui.HIGHLIGHT_COLORS
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
            // Where every thread is, to find a load that never finishes.
            for ((t, stack) in Thread.getAllStackTraces()) {
                if (stack.none { it.className.startsWith("com.biblestudy") || it.className.contains("sqlite", true) }) continue
                out.append("  THREAD ${t.name} ${t.state}\n")
                stack.take(25).forEach { out.append("      at $it\n") }
            }
            File("build/state-writes.txt").appendText(out.toString())
        }
        override fun finished(d: org.junit.runner.Description) { handle?.dispose(); flusher?.dispose() }
    }

    private val vm: StudyViewModel get() = ViewModelProvider(compose.activity)[StudyViewModel::class.java]

    /**
     * Chapters and saved ink load on background threads, which waitForIdle doesn't track.
     * Wait until the page is laid out and its annotations are read from the database.
     */
    private fun waitForLoaded(timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithTag("loading").fetchSemanticsNodes().isEmpty() && vm.pendingLoads == 0
        }
        compose.waitForIdle()
    }

    /** Settings and notes are saved between tests in the same run, so start each test from the defaults. */
    @Before
    fun resetSettings() {
        // The first test in a fresh sandbox copies every bundled database (about 65 MB) first.
        waitForLoaded(30_000)
        compose.runOnUiThread {
            vm.changeWritingSounds(false) // no audio thread in tests
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
            vm.imagesFor(43, 3).toList().forEach { vm.removeItem(it) }
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
        // The other panel follows once its chapter is laid out.
        runCatching { compose.waitUntil(5_000) { kotlin.math.abs(vm.panels[1].topVerse - 50) <= 1 } }
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
        compose.onNodeWithContentDescription("My notes").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Highlights").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("John 3:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // The whole verse is listed, not just the highlighted word.
        val shown = compose.onNodeWithTag("highlightText", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        val verse = vm.text(h.version).verseText(com.biblestudy.app.model.VerseId.of(43, 3, vm.highlightVerses(h).first))!!
        assertEquals(verse, shown)
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
    fun oldBookmarksBecomeWholeVerseHighlights() {
        // A bookmark saved by 0.8, in a folder.
        vm.user.addBookmark(com.biblestudy.app.model.Bookmark(77L, 43, 3, 16, 1L, "Gospel"))
        compose.runOnUiThread { vm.convertBookmarks(); vm.dataGeneration++ }
        assertTrue(vm.user.bookmarks().isEmpty())
        val h = vm.user.allHighlights().single { it.book == 43 && it.chapter == 3 }
        val verse = vm.text("KJV").verseText(43003016)!!
        assertEquals(verse.length, h.end - h.start) // the whole verse, without its number
        assertEquals(setOf("bookmark", "Gospel"), vm.user.tags()["h:${h.id}"])
        compose.runOnUiThread { vm.removeHighlight(h) }
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
        // A cross-reference opens its passage pop-over (LINK-5); Go to moves the Bible panel.
        compose.onNodeWithText("Romans 5:8").performClick()
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodesWithText("commendeth", substring = true).fetchSemanticsNodes().size) // list + pop-over
        snap("79-xref-popover")
        compose.onNodeWithText("Go to").performClick()
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
        val panX = vm.panels[0].panX
        compose.onNodeWithContentDescription("Show right margin").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hide right margin").assertExists()
        // The drawer slides over the text; the text itself stays where it is.
        assertEquals(panX, vm.panels[0].panX, 0.5f)
        // Writing on the open drawer goes into the margin.
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.PEN }
        val w = readerSize().width.toFloat()
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset(w - 150f, 500f)); repeat(5) { moveBy(Offset(15f, 4f)) }; up()
        }
        compose.waitForIdle()
        val s = vm.marginStrokesFor(43, 3).single()
        assertEquals(Region.RIGHT, s.region)
        snap("53-drawer-open")
        compose.runOnUiThread { vm.undo(); vm.fingerDraw = false }
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
        snap("71b-settings-after")
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

        // Drag the handle above the outline a quarter turn clockwise: the ink turns with it.
        compose.mainClock.advanceTimeBy(600)
        if (vm.selection == null) {
            compose.onNodeWithTag("reader0").performTouchInput {
                down(Offset(mx - 40f, 460f)); moveBy(Offset(190f, 0f)); moveBy(Offset(0f, 140f)); moveBy(Offset(-190f, 0f)); moveBy(Offset(0f, -140f)); up()
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(600)
        }
        assertEquals(setOf(before.id), vm.selection?.ids)
        val cx = mx + 50f; val cy = 530f
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset(cx, 452f))
            for (i in 1..12) {
                val a = Math.toRadians(-90.0 + 90.0 * i / 12)
                moveTo(Offset(cx + 78f * Math.cos(a).toFloat(), cy + 78f * Math.sin(a).toFloat()))
            }
            up()
        }
        compose.waitForIdle()
        val turned = vm.marginStrokesFor(43, 3).single()
        val dx = turned.points[27] - turned.points[0]
        val dy = turned.points[28] - turned.points[1]
        // A quarter turn (snapped): (dx, dy) becomes (-dy, dx).
        assertEquals(-(before.points[28] - before.points[1]), dx, 1f)
        assertEquals(before.points[27] - before.points[0], dy, 1f)
        snap("74b-lasso-turned")
        compose.runOnUiThread { vm.undo() }
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

    @Test
    fun notesBrowserWithTagsAndColourMeanings() {
        compose.runOnUiThread {
            vm.tags.keys.toList().forEach { vm.setTags(it, emptySet()) }
            vm.setNote(VerseTarget(43, 3, 16), "God so loved")
            vm.setNote(VerseTarget(19, 23, 1), "The Lord is my shepherd")
            vm.setMeaning(HIGHLIGHT_COLORS[0], "Promises")
        }
        compose.onNodeWithContentDescription("My notes").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("God so loved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The Lord is my shepherd").assertExists()

        // Tag one note, then filter by the tag.
        compose.onAllNodesWithContentDescription("Add tags")[0].performClick()
        compose.onNodeWithText("New tag, e.g. grace").performTextInput("love")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(setOf("love"), vm.tags[vm.noteKey(19, 23, 1)] ?: vm.tags[vm.noteKey(43, 3, 16)])
        compose.onAllNodesWithText("#love")[0].performClick()
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("1 note").fetchSemanticsNodes().size)
        snap("78-notes-browser")

        // Colour meanings show in the highlighter's menu.
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnUiThread { vm.tool = Tool.HIGHLIGHTER; vm.highlightColor = HIGHLIGHT_COLORS[0] }
        compose.onNodeWithContentDescription("Highlighter colour and size").performClick()
        compose.onNodeWithText("This colour means: Promises").assertExists()

        compose.runOnUiThread {
            vm.setNote(VerseTarget(43, 3, 16), ""); vm.setNote(VerseTarget(19, 23, 1), "")
            vm.setMeaning(HIGHLIGHT_COLORS[0], "")
            vm.tags.keys.toList().forEach { vm.setTags(it, emptySet()) }
        }
    }

    @Test
    fun marginPicturesTurnAndCrop() {
        // A 200 x 100 picture beside verse 1.
        val file = "test-picture.png"
        val bmp = android.graphics.Bitmap.createBitmap(200, 100, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(60, 120, 200))
        File(compose.activity.filesDir, "images").apply { mkdirs() }.resolve(file).outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val img = com.biblestudy.app.model.MarginImage(vm.newId(), vm.activeLayerId, 43, 3, Region.RIGHT, 1, 24f, 8f, 300f, 150f, file)
        compose.runOnUiThread {
            vm.bitmaps[file] = bmp.asImageBitmap() // as if already read from storage
            vm.addItem(img); vm.tool = Tool.SELECT; vm.fingerDraw = true
        }
        compose.waitForIdle()

        // Select it with the Select tool: its bar offers Turn, Crop and Delete.
        val z = zoom()
        var y = 40f
        while (compose.onAllNodesWithText("Turn").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("reader0").performTouchInput { down(Offset((Page.COL_W + 150f) * z, y)); up() }
            compose.waitForIdle()
            y += 25f
            assertTrue("picture not found", y < 900f)
        }
        compose.onNodeWithText("Turn").performClick()
        compose.waitForIdle()
        val turned = vm.imagesFor(43, 3).single()
        assertEquals(1, turned.rotation)
        assertEquals(150f, turned.w, 0.1f)
        assertEquals(300f, turned.h, 0.1f)
        snap("80-picture-turned")

        // Crop: drag the bottom-right corner in; the picture keeps part of itself.
        compose.onNodeWithText("Crop").performClick()
        compose.onNodeWithTag("cropArea").performTouchInput {
            down(Offset(width - 2f, height - 2f)); moveBy(Offset(-width * 0.25f, -height * 0.25f)); moveBy(Offset(-width * 0.25f, -height * 0.25f)); up()
        }
        snap("81-crop")
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        val cropped = vm.imagesFor(43, 3).single()
        // The frame was drawn on the turned picture; it's kept on the unturned one.
        assertTrue("crop $cropped", cropped.cropR - cropped.cropL < 0.8f && cropped.cropB - cropped.cropT < 0.8f)
        assertEquals(1f, cropped.cropB, 0.01f) // the turned picture's bottom-right is the original's bottom-left
        compose.runOnUiThread { vm.undo(); vm.undo() }
        assertEquals(0, vm.imagesFor(43, 3).single().rotation)
    }

    @Test
    fun panelLayoutsCanBeSavedAndOpenedAgain() {
        compose.runOnUiThread {
            vm.workspaces.toList().forEach { vm.deleteWorkspace(it) }
            vm.goTo(0, 40, 3, remember = false)
            vm.addPanel(); vm.goTo(1, 41, 1, remember = false); vm.setVersion(1, "BSB")
            vm.addPanel(); vm.goTo(2, 42, 3, remember = false); vm.setVersion(2, "WEB")
            vm.sidePane = PaneKind.CROSSREFS
        }
        waitForLoaded()
        compose.onNodeWithContentDescription("Panels").performClick()
        compose.onNodeWithText("Save this layout\u2026").performClick()
        compose.onNodeWithText("e.g. Gospels side by side").performTextInput("Baptism of Jesus")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Baptism of Jesus"), vm.workspaces.map { it.name })

        // Change everything, then bring the layout back from the panels menu.
        compose.runOnUiThread {
            while (vm.panels.size > 1) vm.closePanel(vm.panels.lastIndex)
            vm.goTo(0, 1, 1, remember = false)
            vm.sidePane = null
        }
        waitForLoaded()
        compose.onNodeWithContentDescription("Panels").performClick()
        compose.onNodeWithText("Layout: Baptism of Jesus").performClick()
        waitForLoaded()
        assertEquals(listOf(40 to 3, 41 to 1, 42 to 3), vm.panels.map { it.book to it.chapter })
        assertEquals(listOf("KJV", "BSB", "WEB"), vm.panels.map { it.version })
        assertEquals(PaneKind.CROSSREFS, vm.sidePane)
        snap("82-workspace")

        // Saved layouts survive a restart (they're in the notes database).
        assertEquals(listOf("Baptism of Jesus"), vm.user.workspaces().map { it.first })
        compose.runOnUiThread { vm.deleteWorkspace(vm.workspaces.single()); vm.sidePane = null }
    }

    @Test
    fun automaticBackupsKeepTheNewestFive() {
        compose.runOnUiThread { vm.backupFolder = null }
        vm.appBackups().forEach { it.delete() }
        // Turned on in Settings, under Backup.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Automatic backup").performScrollTo()
        compose.onNodeWithText("Weekly").performClick()
        compose.waitForIdle()
        assertEquals(com.biblestudy.app.ui.AutoBackup.WEEKLY, vm.autoBackup)
        compose.onNodeWithText("Backup folder").assertExists()
        snap("83-auto-backup")
        compose.onNodeWithContentDescription("Close").performClick()

        val day = 24L * 3600_000L
        val start = 1_800_000_000_000L
        for (i in 0 until 7) {
            var job: kotlinx.coroutines.Job? = null
            compose.runOnUiThread { job = vm.autoBackupIfDue(start + i * 8 * day) }
            assertNotNull("backup $i was due", job)
            kotlinx.coroutines.runBlocking { job!!.join() }
        }
        // Not due again a day later.
        compose.runOnUiThread { assertNull(vm.autoBackupIfDue(start + 49 * day)) }
        val kept = vm.appBackups()
        assertEquals(5, kept.size)
        assertTrue(kept.all { it.length() > 0 })
        compose.runOnUiThread { vm.autoBackup = com.biblestudy.app.ui.AutoBackup.OFF }
        vm.appBackups().forEach { it.delete() }
    }

    @Test
    fun aChapterExportsAsAPictureAndAPdf() {
        compose.runOnUiThread { vm.goTo(0, 43, 3, remember = false) }
        waitForLoaded()
        val dir = File(compose.activity.cacheDir, "export").apply { mkdirs() }
        for (pdf in listOf(false, true)) {
            val out = File(dir, if (pdf) "john3.pdf" else "john3.png").apply { delete() }
            compose.runOnUiThread { vm.exportRequest = com.biblestudy.app.ui.ExportRequest(android.net.Uri.fromFile(out), pdf) }
            compose.waitUntil(10_000) { vm.exportRequest == null }
            compose.waitForIdle()
            // Robolectric's PdfDocument writes nothing, so only the picture's bytes can be checked here.
            if (!pdf) {
                assertTrue("$out: ${out.length()} bytes", out.length() > 0)
                val bmp = android.graphics.BitmapFactory.decodeFile(out.path)
                assertTrue("picture ${bmp.width} x ${bmp.height}", bmp.height > bmp.width)
            }
        }
        assertEquals("John 3 (KJV)", vm.exportName())
    }

    @Test
    fun aTappedWordOpensItsWordStudyAndConcordance() {
        compose.runOnUiThread { vm.goTo(0, 43, 3, remember = false) }
        waitForLoaded()
        // As if "loved" in John 3:16 was tapped ("For God so loved"): word 3.
        compose.runOnUiThread { vm.openVerse(43, 3, 16, word = 3) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Word study: \u201cloved\u201d").fetchSemanticsNodes().isNotEmpty() }
        snap("84-verse-word")
        compose.onNodeWithText("Word study: \u201cloved\u201d").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Used in", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("\u1f00\u03b3\u03b1\u03c0\u03ac\u03c9").assertExists() // agapaō
        compose.onNodeWithText("Greek \u00b7 Strong's G25", substring = true).assertExists()
        val count = compose.onNodeWithTag("useCount").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        val n = Regex("Used in (\\d+) verses").find(count)!!.groupValues[1].toInt()
        assertTrue(count, n > 100)
        snap("85-word-study")

        // Only the uses in one book.
        val in1John = vm.study.occurrences("KJV", "G25", { vm.text("KJV").verseText(it) }).filter { it.id / 1_000_000 == 62 }
        compose.onNodeWithText("1 John ${in1John.size}").performScrollTo().performClick()
        val first = vm.refLabel(in1John.first().id)
        compose.onNodeWithText(first).assertExists()
        // Tapping a verse goes there and closes the windows.
        compose.onNodeWithText(first).performClick()
        waitForLoaded()
        assertEquals(62, vm.panels[0].book)
        assertNull(vm.wordStudy)
        assertNull(vm.verseSheet)
    }

    @Test
    fun searchingAStrongsNumberFindsEveryUseOfTheWord() {
        compose.onNodeWithContentDescription("Search").performScrollTo().performClick()
        compose.onNodeWithText("Words, \"exact phrase\", or a reference like John 3:16").performTextInput("G26")
        compose.onAllNodesWithContentDescription("Search").onLast().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("verses in", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // The header with the Greek word loads just after the results.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("\u1f00\u03b3\u03ac\u03c0\u03b7", substring = true).fetchSemanticsNodes().isNotEmpty() } // agapē
        val inJohn = vm.study.occurrences("KJV", "G26", { vm.text("KJV").verseText(it) }).count { it.id / 1_000_000 == 43 }
        compose.onNodeWithText("John $inJohn").performScrollTo().performClick()
        compose.onNodeWithText("John 13:35").assertExists()
        snap("86-strongs-search")
        compose.onNodeWithText("Word study").performClick()
        compose.onNodeWithTag("wordStudy").assertExists()
        compose.runOnUiThread { vm.wordStudy = null }
    }

    @Test
    fun dictionaryTopicsCommentaryAndRelatedPassagesInTheStudyPane() {
        compose.runOnUiThread { vm.goTo(0, 43, 3, remember = false); vm.sidePane = PaneKind.NOTES; vm.dictionaryOpen = null }
        waitForLoaded()
        // The pane's one menu picks what it shows.
        compose.onNodeWithContentDescription("Choose what the pane shows").performClick()
        snap("86b-pane-menu")
        compose.onNodeWithText("Dictionary").performClick()
        assertEquals(PaneKind.DICTIONARY, vm.sidePane)
        // Easton's: names in the chapter are suggested.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Nicodemus").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Nicodemus").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Pharisee", substring = true).fetchSemanticsNodes().isNotEmpty() }
        snap("87-dictionary")
        compose.onNodeWithContentDescription("Back to the list").performClick()

        // Nave's: search for a topic and open it.
        compose.runOnUiThread { vm.sidePane = PaneKind.TOPICS; vm.topicOpen = null }
        compose.onNodeWithText("Find a topic, e.g. Prayer or Faith").performTextInput("Prayer")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("entry").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("entry").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Daily, in the morning", substring = true).fetchSemanticsNodes().isNotEmpty() }
        snap("88-topic")
        compose.runOnUiThread { vm.topicOpen = null }

        // Matthew Henry on the chapter.
        compose.runOnUiThread { vm.sidePane = PaneKind.COMMENTARY }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Nicodemus was afraid", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Matthew Henry \u00b7 John 3").assertExists()
        snap("89-commentary")

        // Cross-references end with topics, parallel accounts and related passages.
        compose.runOnUiThread { vm.goTo(0, 40, 3, remember = false); vm.paneVerse = VerseTarget(40, 3, 13); vm.sidePane = PaneKind.CROSSREFS }
        waitForLoaded()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Parallel accounts").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Parallel accounts").performScrollTo()
        assertTrue(compose.onAllNodesWithText("Mark 1:9", substring = true).fetchSemanticsNodes().isNotEmpty())
        snap("90-related")
        compose.runOnUiThread { vm.sidePane = null }
    }

    @Test
    fun aLayerCanBeFadedAndRecolouredFromItsMenu() {
        compose.onNodeWithText("My Notes").performClick()
        compose.onNodeWithText("Layers").assertExists()
        compose.onNodeWithContentDescription("More for My Notes").performClick()
        snap("91-layer-menu")
        compose.onNodeWithText("50%").performClick()
        compose.waitForIdle()
        assertEquals(0.5f, vm.layers.first().opacity, 0.001f)
        assertEquals(0.5f, vm.user.layers().first().opacity, 0.001f) // saved
        compose.runOnUiThread { vm.setLayerColor(vm.layers.first().id, com.biblestudy.app.ui.LAYER_COLORS[2]) }
        assertEquals(com.biblestudy.app.ui.LAYER_COLORS[2], vm.user.layers().first().color)
        compose.runOnUiThread { vm.setLayerOpacity(vm.layers.first().id, 1f); vm.setLayerColor(vm.layers.first().id, com.biblestudy.app.ui.LAYER_COLORS[0]) }
    }

    @Test
    fun readingIsCountedAndShownInReadingStats() {
        compose.runOnUiThread { vm.clearReadingStats(); vm.trackReading = true; vm.foreground = true }
        waitForLoaded()
        // Scroll through John 3.
        // Down to the end of John 3.
        compose.runOnUiThread { vm.goTo(0, 43, 3, 30, remember = false) }
        waitForLoaded()
        val ch = vm.panels[0].chapter
        assertEquals(3, ch)
        assertTrue("seen to ${vm.panels[0].seenTo}", vm.panels[0].seenTo >= 29)
        // Five quarter-minutes of reading, touching now and then: the chapter counts as read.
        var t = 10_000_000L
        compose.runOnUiThread {
            vm.startReadingClock(t); vm.userActive(t)
            repeat(5) { t += 15_000; vm.userActive(t - 5_000); vm.readingTick(t) }
        }
        // Three idle minutes later nothing more is counted.
        compose.runOnUiThread { vm.readingTick(t + 180_000) }
        runCatching { compose.waitUntil(10_000) { vm.user.readingChapters().any { it.book == 43 && it.chapter == ch && it.timesRead == 1 } } }
            .onFailure { throw AssertionError("${vm.user.readingChapters()} at ${vm.panels[0].chapter} seen ${vm.panels[0].seenTo}", it) }
        val john3 = vm.user.readingChapters().single { it.book == 43 && it.chapter == ch }
        assertEquals(75, john3.seconds)
        assertEquals(1, john3.opens)

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Reading stats").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 of 1189 chapters").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("1 min", substring = true).fetchSemanticsNodes().isNotEmpty())
        snap("92-reading-stats")
        compose.onNodeWithContentDescription("Close").performClick()

        // The book picker tints chapters already read.
        compose.onNodeWithText("John $ch").performClick()
        compose.onNodeWithText("John").performClick()
        assertEquals(
            "read",
            compose.onAllNodes(androidx.compose.ui.test.hasStateDescription("read")).fetchSemanticsNodes().single()
                .config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription],
        )
        snap("93-picker-read")
        compose.runOnUiThread { vm.clearReadingStats() }
    }

    @Test
    fun namesAndPlacesWithFamilyAndAMap() {
        compose.runOnUiThread { vm.goTo(0, 43, 3, remember = false); vm.nameOpen = null }
        waitForLoaded()
        // The verse window lists the people in the verse; one opens in the study pane.
        compose.runOnUiThread { vm.openVerse(43, 3, 1) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Nicodemus").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Nicodemus").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Pharisee who visited Jesus").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(PaneKind.NAMES, vm.sidePane)
        assertNull(vm.verseSheet)
        compose.onNodeWithText("Mentioned in 5 verses").performScrollTo().assertExists()
        snap("94-person")

        // A family: Aaron's brother opens from his entry.
        compose.onNodeWithContentDescription("Back to the list").performClick()
        compose.onNodeWithText("Find a person or place").performTextInput("Aaron")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nameRow").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("nameRow").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Brothers and sisters").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Moses").performScrollTo().performClick()
        // The family member is looked up in the background, then opens.
        compose.waitUntil(10_000) { vm.nameOpen?.let { vm.study.nameById(it)?.name } == "Moses" }

        // A place on the offline map.
        compose.runOnUiThread { vm.nameOpen = vm.study.nameSearch("Bethlehem").first().id }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("placeMap").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Place \u00b7 Tribe of Judah").assertExists()
        snap("95-place-map")
        compose.runOnUiThread { vm.nameOpen = null; vm.sidePane = null }
    }

    @Test
    fun sketchPagesWithVerseAndNameCards() {
        compose.runOnUiThread { vm.sketches.toList().forEach { vm.deleteSketch(it) } }
        // Deleting the big ready-made pages queues a lot of database work ahead of the chapter's load.
        waitForLoaded(30_000)
        // A new sketch page, linked to the passage being read.
        compose.onNodeWithContentDescription("Insert").performScrollTo().performClick()
        compose.onNodeWithText("Sketch page\u2026").performClick()
        compose.onNodeWithText("e.g. Timeline of the kings").performTextInput("Born again")
        compose.onNodeWithText("Grid").performClick()
        compose.onNodeWithText("Create").performClick()
        waitForLoaded()
        val sk = vm.sketches.single()
        assertEquals(sk.book, vm.panels[0].book)
        assertEquals(43 to 3, sk.linkBook to sk.linkChapter)
        compose.onNodeWithText("Born again").assertExists()

        // Draw on it like anywhere else.
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.PEN }
        compose.onNodeWithTag("reader0").performTouchInput { down(Offset(300f, 500f)); repeat(8) { moveBy(Offset(25f, 10f)) }; up() }
        compose.waitForIdle()
        assertEquals(1, vm.marginStrokesFor(sk.book, 1).size)

        // A verse card: type the reference.
        compose.onNodeWithContentDescription("Insert").performScrollTo().performClick()
        compose.onNodeWithText("Verse card\u2026").performClick()
        compose.onNodeWithText("Reference, e.g. John 3:16-18").performTextInput("John 3:16")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("cardPreview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add card").performClick()
        compose.waitForIdle()
        assertTrue(vm.textsFor(sk.book, 1).any { it.text.startsWith("John 3:16 (KJV)\nFor God so loved") })

        // A person card.
        compose.onNodeWithContentDescription("Insert").performScrollTo().performClick()
        compose.onNodeWithText("Person or place card\u2026").performClick()
        compose.onNodeWithText("Find a person or place").performTextInput("Nicodemus")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nameRow").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("nameRow").onFirst().performClick()
        compose.waitForIdle()
        assertTrue(vm.textsFor(sk.book, 1).any { it.text.startsWith("Nicodemus\nPharisee") })
        compose.runOnUiThread { vm.fingerDraw = false }
        snap("96-sketch-page")

        // Back to John 3; the sketch page reopens from My notes.
        compose.onNodeWithContentDescription("Back").performClick()
        waitForLoaded()
        assertEquals(43 to 3, vm.panels[0].book to vm.panels[0].chapter)
        snap("97-sketch-badge")
        compose.onNodeWithContentDescription("My notes").performScrollTo().performClick()
        compose.onNodeWithText("Sketch pages").performClick()
        compose.onNodeWithText("Born again").performClick()
        waitForLoaded()
        assertEquals(sk.book, vm.panels[0].book)
        // Kept in the notes database, and not listed among typed notes.
        assertEquals(listOf("Born again"), vm.user.sketches().map { it.name })
        assertTrue(vm.user.allTexts().none { it.book >= 1000 })

        // Delete it: everything on it goes, and the panel returns to its passage.
        compose.onNodeWithContentDescription("Sketch page menu").performClick()
        compose.onNodeWithText("Delete sketch page\u2026").performClick()
        compose.onNodeWithText("Delete").performClick()
        waitForLoaded()
        assertEquals(43, vm.panels[0].book)
        assertTrue(vm.user.sketches().isEmpty())
    }

    @Test
    fun paragraphsAndHiddenVerseNumbersFromSettings() {
        compose.runOnUiThread { vm.setVersion(0, "BSB"); vm.fingerDraw = true; vm.tool = Tool.PEN }
        waitForLoaded()
        val z = zoom()
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset((Page.COL_PAD + 200f) * z, 700f)); repeat(6) { moveBy(Offset(15f, 0f)) }; up()
        }
        compose.waitForIdle()
        val before = vm.textStrokesFor("BSB", 43, 3).single()
        compose.runOnUiThread { vm.fingerDraw = false }

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Paragraphs").performScrollTo().performClick()
        compose.onNodeWithText("Verse numbers").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        assertTrue(vm.paragraphMode)
        assertTrue(!vm.verseNumbers)
        waitForLoaded()
        compose.waitUntil(5_000) { vm.textStrokesFor("BSB", 43, 3).isNotEmpty() }
        val after = vm.textStrokesFor("BSB", 43, 3).single()
        assertEquals(before.id, after.id)
        assertEquals("BOOK|p|n", after.font) // moved onto the paragraph layout
        snap("98-paragraphs")
        compose.runOnUiThread { vm.changeParagraphs(false); vm.changeVerseNumbers(true); vm.undo() }
    }

    @Test
    fun expandToFitMakesRoomForTallMarginNotes() {
        waitForLoaded()
        // A tall picture beside verse 1 of John 3, which is only two lines long.
        val file = "tall.png"
        val bmp = android.graphics.Bitmap.createBitmap(100, 300, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(120, 160, 200)) }
        val img = com.biblestudy.app.model.MarginImage(vm.newId(), vm.activeLayerId, 43, 3, Region.RIGHT, 1, 24f, 8f, 200f, 600f, file)
        compose.runOnUiThread { vm.bitmaps[file] = bmp.asImageBitmap(); vm.addItem(img); vm.record(com.biblestudy.app.model.Edit(listOf(img), emptyList())) }
        waitForLoaded()
        assertTrue(vm.fitGaps["KJV|43|3"].isNullOrEmpty()) // off by default
        compose.runOnUiThread { vm.expandToFit = true }
        compose.waitUntil(10_000) { (vm.fitGaps["KJV|43|3"]?.get(2) ?: 0f) > 400f }
        snap("99-expand-to-fit")
        // Turned off: the text closes up again.
        compose.runOnUiThread { vm.expandToFit = false }
        compose.waitUntil(10_000) { vm.fitGaps["KJV|43|3"]?.isEmpty() == true }
        compose.runOnUiThread { vm.undo() }

        // Margins only in the first panel.
        compose.runOnUiThread { vm.addPanel(); vm.marginsAllPanels = false }
        waitForLoaded()
        snap("99b-margins-first-panel")
        compose.runOnUiThread { vm.marginsAllPanels = true }
    }


    @Test
    fun importingABibleAddsItToEveryVersionList() {
        val dir = File(compose.activity.cacheDir, "import").apply { mkdirs() }
        val file = File(dir, "tst.usfm")
        file.writeText("\\id JHN\n\\h John\n\\c 3\n\\p\n\\v 16 Imported words about lovingkindness.\n\\v 17 More imported words.\n")
        compose.runOnUiThread {
            vm.importBible(listOf("tst.usfm"), { file.inputStream() }, "tst", "Test Version", "Test copyright.")
        }
        compose.waitUntil(15_000) { com.biblestudy.app.data.BibleRepository.ALL.any { it.code == "TST" } && !vm.importing }
        assertEquals("Imported words about lovingkindness.", vm.text("TST").verseText(43003016))
        assertEquals(1, vm.text("TST").search("lovingkindness", com.biblestudy.app.model.SearchScope.ALL, 43).size)
        // Read it like any version, and it's listed in Settings with its copyright.
        compose.runOnUiThread { vm.setVersion(0, "TST") }
        waitForLoaded()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("TST \u2014 Test Version").performScrollTo().assertExists()
        compose.onNodeWithText("Test copyright.", substring = true).assertExists()
        snap("100-bibles")
        compose.onNodeWithText("Remove").performScrollTo().performClick()
        compose.onAllNodesWithText("Remove").onLast().performClick()
        compose.waitForIdle()
        assertTrue(com.biblestudy.app.data.BibleRepository.ALL.none { it.code == "TST" })
        assertEquals("KJV", vm.panels[0].version)
    }

    @Test
    fun wordsOfJesusInRedAndTheGreekWordByWord() {
        // John 3:16 is all the words of Jesus in each version; John 3:2 (Nicodemus) is not.
        for (v in listOf("KJV", "BSB", "WEB")) {
            val texts = vm.text(v).chapter(43, 3).associate { it.verse to it.text }
            val red = vm.study.redLetters(v, 43, 3, texts)
            assertTrue(v, red[16]!!.first().first <= 4)
            assertNull(v, red[2])
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Words of Jesus in red").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        assertTrue(vm.redLetters)
        compose.runOnUiThread { vm.goTo(0, 43, 3, 14, remember = false) }
        waitForLoaded()
        snap("101-red-letters")

        // The verse window shows the Greek word by word; a word gives its grammar and a word study.
        compose.runOnUiThread { vm.openVerse(43, 3, 16) }
        compose.onNodeWithTag("originalChip").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("originalWord").fetchSemanticsNodes().size > 10 }
        compose.onNodeWithText("\u0113gap\u0113sen").performClick()
        compose.onNodeWithTag("grammar").assertTextEquals("verb, aorist active indicative, 3rd person singular")
        snap("102-greek")
        compose.onNodeWithText("Word study").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("G25", vm.wordStudy?.strong)
        compose.runOnUiThread { vm.wordStudy = null; vm.verseSheet = null }

        // Hebrew reads right to left, with its grammar in plain words.
        compose.runOnUiThread { vm.openVerse(1, 1, 1) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("originalWord").fetchSemanticsNodes().size == 7 }
        compose.onNodeWithText("ba.Ra'").performClick()
        compose.onNodeWithTag("grammar").assertTextEquals("verb, Qal perfect, 3rd person masculine singular")
        snap("103-hebrew")
        compose.runOnUiThread { vm.verseSheet = null; vm.originalView = false; vm.redLetters = false }
    }

    private fun assertTreeNode(name: String) {
        assertEquals(name, 1, compose.onAllNodesWithTag("treeNode").filter(hasText(name)).fetchSemanticsNodes().size)
    }

    @Test
    fun familyTreeRecentresAndCopiesToASketchPage() {
        compose.runOnUiThread { vm.sketches.toList().forEach { vm.deleteSketch(it) } }
        waitForLoaded()
        // From Moses's entry in Names & places.
        compose.runOnUiThread { vm.openName(vm.study.nameSearch("Moses").first().id) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nameView").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Family tree").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Family tree").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Family of Moses").fetchSemanticsNodes().isNotEmpty() }
        // Parents, grandparents, brother and sister, wife and sons.
        for (n in listOf("Amram", "Jochebed", "Kohath", "Aaron", "Miriam", "Zipporah", "Gershom", "Eliezer")) {
            assertTreeNode(n)
        }
        snap("104-family-tree")
        // Tap Aaron to see his family.
        compose.onAllNodesWithTag("treeNode").filter(hasText("Aaron")).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Family of Aaron").fetchSemanticsNodes().isNotEmpty() }
        assertTreeNode("Elisheba")
        assertTreeNode("Nadab")
        // Copied onto a new sketch page as text boxes and lines.
        compose.onNodeWithText("Copy to sketch page").performClick()
        compose.waitForIdle()
        val sketch = vm.sketches.single()
        assertEquals("Family of Aaron", sketch.name)
        assertEquals(sketch.book, vm.panels[0].book)
        val texts = vm.textsFor(sketch.book, 1).map { it.text }
        assertTrue(texts.containsAll(listOf("Aaron", "Elisheba", "Nadab", "Moses")))
        assertTrue(vm.marginStrokesFor(sketch.book, 1).size > 5)
        waitForLoaded()
        snap("105-tree-on-sketch")
        // One undo removes it all.
        compose.runOnUiThread { vm.undo() }
        assertTrue(vm.textsFor(sketch.book, 1).isEmpty())
        compose.runOnUiThread { vm.deleteSketch(sketch); vm.sidePane = null; vm.nameOpen = null }
    }

    @Test
    fun readyMadeSketchPagesAreThereFromTheStart() {
        // The ready-made pages exist from the first start, on their own (not linked to a verse).
        compose.runOnUiThread { vm.sketches.filter { !it.readyMade }.forEach { vm.deleteSketch(it) }; vm.addReadyMadePages() }
        val ready = vm.sketches.filter { it.readyMade }.sortedBy { it.created }
        assertEquals(com.biblestudy.app.ui.SketchTemplates.all.map { it.name }, ready.map { it.name })
        assertTrue(ready.none { it.linked })
        assertTrue(vm.textsFor(ready[0].book, 1).any { it.text.startsWith("1. Passover") })
        assertTrue(vm.textsFor(ready[1].book, 1).any { it.text.startsWith("Hebrews 9:11\u201312 (KJV)") })
        assertTrue(vm.textsFor(ready[2].book, 1).any { it.text.startsWith("Josiah  641\u2013609") })
        assertTrue(vm.textsFor(ready[3].book, 1).any { it.text == "JESUS" })
        // They aren't badges in any chapter's margin.
        assertTrue(vm.sketchesIn(43, 3).none { it.readyMade })

        // Open one from My notes \u2192 Sketch pages.
        compose.onNodeWithContentDescription("My notes").performClick()
        compose.onNodeWithText("Sketch pages").performClick()
        compose.onNodeWithText("Ready-made pages").assertExists()
        snap("116-sketch-list")
        compose.onNodeWithText("The feasts of Israel").performClick()
        waitForLoaded()
        assertEquals(ready[0].book, vm.panels[0].book)
        snap("117-feasts")
        for ((t, shot) in ready.drop(1).zip(listOf("118-tabernacle", "119-kings", "120-adam-to-jesus"))) {
            compose.runOnUiThread { vm.openSketch(t, 0) }
            waitForLoaded()
            snap(shot)
        }

        // Deleted ones can be put back.
        compose.runOnUiThread { vm.deleteSketch(ready[2]) }
        assertTrue(vm.sketches.none { it.name == "The kings of Israel and Judah" })
        compose.runOnUiThread { vm.addReadyMadePages(announce = true) }
        assertTrue(vm.sketches.any { it.readyMade && it.name == "The kings of Israel and Judah" })
        assertEquals("1 ready-made page put back.", vm.message)

        // A new page can stand on its own, or be linked to a verse and later unlinked.
        compose.runOnUiThread { vm.goTo(0, 43, 3, remember = false) }
        waitForLoaded()
        compose.onNodeWithContentDescription("Insert").performScrollTo().performClick()
        compose.onNodeWithText("Sketch page\u2026").performClick()
        compose.onNodeWithText("e.g. Timeline of the kings").performTextInput("Free page")
        compose.onNodeWithText("Link to John 3:1").performClick()
        compose.onNodeWithText("Create").performClick()
        val free = vm.sketches.first { it.name == "Free page" }
        assertTrue(!free.linked)
        waitForLoaded()
        compose.onNodeWithText("on John", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Sketch page menu").performClick()
        compose.onNodeWithText("Link to a passage\u2026").performClick()
        compose.onNodeWithText("Reference, e.g. Exodus 25:8").performTextInput("Psalm 23:1")
        compose.onNodeWithText("Link").performClick()
        assertEquals(Triple(19, 23, 1), vm.sketches.first { it.id == free.id }.let { Triple(it.linkBook, it.linkChapter, it.linkVerse) })
        compose.onNodeWithContentDescription("Sketch page menu").performClick()
        compose.onNodeWithText("Unlink from", substring = true).performClick()
        assertTrue(!vm.sketches.first { it.id == free.id }.linked)
        // Deleting a page on its own goes back to the Bible.
        compose.runOnUiThread { vm.deleteSketch(vm.sketches.first { it.id == free.id }) }
        assertEquals(43, vm.panels[0].book)
    }

    @Test
    fun wordDifferencesViewsAndOneLayerExport() {
        // Compare versions marks words that differ from the version being read.
        compose.runOnUiThread { vm.compareVersions = true; vm.openVerse(43, 3, 16) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("compare_WEB", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val web = compose.onNodeWithTag("compare_WEB", useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].first()
        assertTrue(web.spanStyles.map { web.text.substring(it.start, it.end) }.toString(), web.spanStyles.any { web.text.substring(it.start, it.end) == "born" })
        snap("110-compare-differences")
        compose.runOnUiThread { vm.verseSheet = null; vm.compareVersions = false }

        // Side by side: the WEB beside the KJV is marked where its wording differs.
        compose.runOnUiThread { vm.markDifferences = true; vm.addPanel(); vm.setVersion(1, "WEB"); vm.goTo(1, 43, 3, remember = false) }
        waitForLoaded()
        assertEquals("WEB", vm.diffVersionFor(vm.panels[0]))
        assertEquals("KJV", vm.diffVersionFor(vm.panels[1]))
        snap("111-side-by-side-differences")
        compose.runOnUiThread { vm.closePanel(1); vm.markDifferences = false }

        // A saved view of layers comes back with one tap.
        val second = compose.runOnIdle { vm.addLayer("Sermon"); vm.layers.last().id }
        compose.runOnUiThread { vm.showOnlyLayer(second); vm.saveLayerPreset("Sermon prep"); vm.setAllLayersVisible(true) }
        compose.onNodeWithContentDescription("Layers").performClick()
        compose.onNodeWithText("Sermon prep").performClick()
        assertEquals(listOf(second), vm.layers.filter { it.visible }.map { it.id })
        snap("112-layer-views")
        compose.onNodeWithContentDescription("Close").performClick()

        // Exporting one layer draws only that layer's notes.
        compose.runOnUiThread { vm.setAllLayersVisible(true); vm.activeLayerId = second; vm.fingerDraw = true; vm.tool = Tool.PEN }
        waitForLoaded()
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset(300f, 600f)); repeat(20) { moveBy(Offset(20f, 6f)) }; up()
        }
        compose.runOnUiThread { vm.fingerDraw = false }
        val dir = File(compose.activity.cacheDir, "export").apply { mkdirs() }
        val sizes = vm.layers.map { l ->
            val out = File(dir, "layer${l.id}.png").apply { delete() }
            compose.runOnUiThread { vm.exportRequest = com.biblestudy.app.ui.ExportRequest(android.net.Uri.fromFile(out), false, l.id) }
            compose.waitUntil(10_000) { vm.exportRequest == null }
            compose.waitForIdle()
            out.readBytes().contentHashCode()
        }
        assertTrue(sizes.toSet().size == 2)
        compose.runOnUiThread { vm.undo(); vm.deleteLayer(second); vm.deleteLayerPreset("Sermon prep") }
    }

    @Test
    fun helpSettingsSearchSketchLinkAndBackedUpBibles() {
        // Help: search, then open a topic.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Help").performClick()
        compose.onNodeWithTag("helpSearch").performTextInput("Greek")
        compose.onNodeWithText("Hebrew and Greek word by word").assertExists()
        snap("113-help-search")
        compose.onNodeWithText("Hebrew and Greek word by word").performClick()
        compose.onNodeWithTag("helpSection").assertExists()
        compose.onNodeWithText("Hebrew reads right to left", substring = true).assertExists()
        snap("114-help-topic")
        compose.onNodeWithContentDescription("Close").performClick()

        // Settings: typing finds the setting and hides the rest.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settingsSearch").performTextInput("red")
        compose.onNodeWithText("Words of Jesus in red").assertExists()
        compose.onNodeWithText("Verse numbers").assertDoesNotExist()
        snap("115-settings-search")
        compose.onNodeWithContentDescription("Close").performClick()

        // A sketch page can be linked to another passage.
        compose.runOnUiThread { vm.createSketch("Tabernacle notes", com.biblestudy.app.model.Paper.BLANK) }
        waitForLoaded()
        compose.onNodeWithContentDescription("Sketch page menu").performClick()
        compose.onNodeWithText("Link to another passage\u2026").performClick()
        compose.onNodeWithText("Reference, e.g. Exodus 25:8").performTextClearance()
        compose.onNodeWithText("Reference, e.g. Exodus 25:8").performTextInput("Exodus 25:8")
        compose.onNodeWithText("Link").performClick()
        val sk = vm.sketches.first { it.name == "Tabernacle notes" }
        assertEquals(Triple(2, 25, 8), Triple(sk.linkBook, sk.linkChapter, sk.linkVerse))
        compose.runOnUiThread { vm.deleteSketch(sk) }

        // Imported Bibles go into backups and come back on restore.
        val dir = File(compose.activity.cacheDir, "import").apply { mkdirs() }
        val file = File(dir, "bak.usfm")
        file.writeText("\\id JHN\n\\c 3\n\\p\n\\v 16 Backed up words.\n")
        compose.runOnUiThread { vm.importBible(listOf("bak.usfm"), { file.inputStream() }, "BAK", "Backup Test", "Test.") }
        compose.waitUntil(15_000) { com.biblestudy.app.data.BibleRepository.ALL.any { it.code == "BAK" } && !vm.importing }
        val zip = File(dir, "backup.zip").apply { delete() }
        compose.runOnUiThread { vm.backup(android.net.Uri.fromFile(zip)) }
        compose.waitUntil(15_000) { zip.length() > 0 && vm.message?.contains("ack") == true }
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.toString(), "bibles/imported.json" in names && names.any { it.startsWith("bibles/") && it.endsWith(".db") })
        compose.runOnUiThread { vm.removeBible("BAK") }
        assertTrue(com.biblestudy.app.data.BibleRepository.ALL.none { it.code == "BAK" })
        compose.runOnUiThread { vm.restore(android.net.Uri.fromFile(zip)) }
        compose.waitUntil(15_000) { com.biblestudy.app.data.BibleRepository.ALL.any { it.code == "BAK" } }
        assertEquals("Backed up words.", vm.text("BAK").verseText(43003016))
        compose.runOnUiThread { vm.removeBible("BAK") }
    }

    @Test
    fun anySketchPageOpensBesideTheText() {
        compose.runOnUiThread { vm.addReadyMadePages() }
        waitForLoaded()
        // Panels \u2192 Beside the text: Sketch pages lists every page.
        compose.onNodeWithContentDescription("Panels").performClick()
        compose.onNodeWithText("Beside the text: Sketch pages").performClick()
        compose.onNodeWithTag("sketchesPane").assertExists()
        snap("121-sketches-pane")
        compose.onNodeWithText("The tabernacle").performClick()
        waitForLoaded()
        val tab = vm.sketches.first { it.name == "The tabernacle" }
        assertEquals(2, vm.panels.size)
        assertEquals(43, vm.panels[0].book) // the Bible stays where it was
        assertEquals(tab.book, vm.panels[1].book)
        snap("122-sketch-beside")
        // Another page replaces it in the same panel.
        compose.runOnUiThread { vm.sidePane = com.biblestudy.app.ui.PaneKind.SKETCHES }
        compose.onNodeWithText("The feasts of Israel").performClick()
        waitForLoaded()
        assertEquals(2, vm.panels.size)
        assertEquals(43, vm.panels[0].book)
        assertEquals(vm.sketches.first { it.name == "The feasts of Israel" }.book, vm.panels[1].book)
        compose.runOnUiThread { vm.closePanel(1) }
    }

    @Test
    fun erasingAHighlightInOneVersionErasesItInEvery() {
        val at = Offset((Page.COL_PAD + 250f) * zoom(), 600f)
        val h = highlightWordAt(at)
        compose.runOnUiThread { vm.setVersion(0, "BSB"); vm.goTo(0, 43, 3, remember = false) }
        waitForLoaded()
        assertEquals(1, vm.crossHighlights("BSB", 43, 3).size)
        // Rub the eraser down the page in the BSB: the KJV highlight goes too.
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.ERASER; vm.partialEraser = false }
        compose.onNodeWithTag("reader0").performTouchInput {
            down(Offset(at.x, 60f)); repeat(64) { moveBy(Offset(0f, 10f)) }; up()
        }
        compose.waitForIdle()
        assertTrue(vm.highlightsFor("KJV", 43, 3).none { it.id == h.id })
        assertTrue(vm.crossHighlights("BSB", 43, 3).isEmpty())
        // One undo brings it back in both.
        compose.runOnUiThread { vm.undo() }
        compose.waitForIdle()
        assertTrue(vm.highlightsFor("KJV", 43, 3).any { it.id == h.id })
        assertEquals(1, vm.crossHighlights("BSB", 43, 3).size)
        compose.runOnUiThread { vm.fingerDraw = false; vm.tool = Tool.PEN }
    }

    /** Where a point of a sketch page (page units) is on screen in panel 0. */
    private fun sketchPoint(x: Float, y: Float): Offset {
        val p = vm.panels[0]
        return Offset(p.panX + x * p.zoom, p.panY + (y + Page.TEXT_TOP) * p.zoom)
    }

    @Test
    fun verseCardsWorkLikeTheBibleText() {
        compose.runOnUiThread {
            for (v in listOf("KJV", "BSB", "WEB")) vm.highlightsFor(v, 43, 3).toList().forEach { vm.removeItem(it) }
            val sk = vm.createSketch("Cards", com.biblestudy.app.model.Paper.BLANK, link = null)
            vm.placeOnSketch(sk, listOf(com.biblestudy.app.model.DrawnVerse(40f, 0f, 700f, "John 3:16"), com.biblestudy.app.model.DrawnBox(40f, 300f, 700f, "My own words about grace and truth")))
            vm.panels[0].panX = 0f; vm.panels[0].panY = 0f
        }
        waitForLoaded()
        val sk = vm.sketches.first { it.name == "Cards" }
        val card = vm.textsFor(sk.book, 1).first { it.text.startsWith("John 3:16 (KJV)") }
        val note = vm.textsFor(sk.book, 1).first { it.text.startsWith("My own words") }
        snap("123-verse-card")

        // A tap on the verse opens the verse window, as on the page.
        compose.onNodeWithTag("reader0").performTouchInput { click(sketchPoint(card.x + 120f, card.y + 50f)) }
        compose.waitUntil(5_000) { vm.verseSheet != null }
        assertEquals(VerseTarget(43, 3, 16, vm.verseSheet!!.word), vm.verseSheet)
        compose.runOnUiThread { vm.verseSheet = null }
        compose.mainClock.advanceTimeBy(600)

        // The highlighter on the card highlights John 3:16 in the Bible itself.
        compose.runOnUiThread { vm.fingerDraw = true; vm.tool = Tool.HIGHLIGHTER; vm.snapHighlights = true }
        compose.onNodeWithTag("reader0").performTouchInput {
            down(sketchPoint(card.x + 20f, card.y + 50f)); repeat(10) { moveBy(Offset(25f * vm.panels[0].zoom, 0f)) }; up()
        }
        compose.waitForIdle()
        val h = vm.highlightsFor("KJV", 43, 3).single()
        assertEquals(16, vm.highlightVerses(h).first)
        // It shows on the card and in John 3 in the other versions.
        // (The page is only drawn here when a picture is taken.)
        snap("124-card-highlighted")
        assertTrue(vm.cardTexts[card.id]!!.text.spanStyles.any { it.item.background != androidx.compose.ui.graphics.Color.Unspecified })
        assertEquals(1, vm.crossHighlights("BSB", 43, 3).size)

        // A plain text box keeps highlights on its own words.
        compose.onNodeWithTag("reader0").performTouchInput {
            down(sketchPoint(note.x + 20f, note.y + 20f)); repeat(8) { moveBy(Offset(25f * vm.panels[0].zoom, 0f)) }; up()
        }
        compose.waitForIdle()
        assertEquals(1, vm.textsFor(sk.book, 1).first { it.id == note.id }.markList().size)

        // The eraser on the card takes the highlight out of the Bible too.
        compose.runOnUiThread { vm.tool = Tool.ERASER; vm.partialEraser = false }
        compose.onNodeWithTag("reader0").performTouchInput {
            down(sketchPoint(card.x + 20f, card.y + 50f)); repeat(20) { moveBy(Offset(15f * vm.panels[0].zoom, 0f)) }; up()
        }
        compose.waitForIdle()
        assertTrue(vm.highlightsFor("KJV", 43, 3).isEmpty())

        // A long press selects the card: its bar switches it to the BSB.
        compose.runOnUiThread { vm.fingerDraw = false; vm.tool = Tool.PEN }
        // Past palm rejection after writing (it uses the system clock).
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        compose.onNodeWithTag("reader0").performTouchInput { longClick(sketchPoint(card.x + 120f, card.y + 50f)) }
        compose.waitForIdle()
        compose.onNodeWithText("BSB").performClick()
        compose.waitForIdle()
        assertTrue(vm.textsFor(sk.book, 1).first { it.id == card.id }.text.startsWith("John 3:16 (BSB)\nFor God so loved the world that He gave His one and only Son"))
        snap("125-card-bsb")
        compose.runOnUiThread { vm.deleteSketch(vm.sketches.first { it.id == sk.id }) }
    }
}
