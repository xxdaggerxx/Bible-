package com.biblestudy.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.viewinterop.AndroidView
import com.biblestudy.app.model.Region
import com.biblestudy.app.model.TextFont
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.biblestudy.app.R
import com.biblestudy.app.data.BibleRepository
import com.biblestudy.app.data.RefLinks
import com.biblestudy.app.model.ChapterData
import com.biblestudy.app.model.InkStroke
import com.biblestudy.app.model.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val bibleFont = FontFamily(
    Font(R.font.gentium_book_plus_regular),
    Font(R.font.gentium_book_plus_bold, FontWeight.Bold),
)

/** The typeface for each text font choice (READ-3). */
fun TextFont.family(): FontFamily = when (this) {
    TextFont.BOOK -> bibleFont
    TextFont.SERIF -> FontFamily.Serif
    TextFont.SANS -> FontFamily.SansSerif
}

/** Laid-out chapters kept per panel: the current one, its neighbours and a few recent ones. */
private const val MAX_LAYOUTS = 7

@Composable
fun ReaderPanel(vm: StudyViewModel, index: Int, onOpenPicker: () -> Unit, modifier: Modifier = Modifier) {
    val panel = vm.panels[index]
    val ctl = remember(panel) { ReaderController(vm, panel) }
    val resolver = LocalFontFamilyResolver.current
    val measurer = remember(resolver) { TextMeasurer(resolver, Density(1f, 1f), LayoutDirection.Ltr) }
    val density = LocalDensity.current.density
    val haptics = LocalHapticFeedback.current
    val theme = vm.theme

    // Lays out one chapter with the current reading settings and any expand-to-fit gaps (MRG-10).
    val buildChapter: suspend (String, Int, Int, Map<Int, Float>) -> ChapterLayout = { v, b, c, spacers ->
        val style = vm.styleKey()
        val headingsOn = vm.showHeadings
        val (data, paras) = withContext(Dispatchers.IO) {
            ChapterData(v, b, c, vm.text(v).chapter(b, c), if (headingsOn) vm.headings(b, c) else emptyList()) to
                (if (style.paragraphs) vm.study.paragraphStarts(v, b, c) else null)
        }
        buildChapterLayout(
            measurer, style.font.family(), vm.bible.book(b).name, data, vm.lineSpacing,
            paragraphs = paras, numbers = style.numbers, spacers = spacers,
        ) { RefLinks.parseList(it, vm.bible.books) }
    }

    // Load the current chapter first, then its neighbours so scrolling past either end is seamless.
    LaunchedEffect(panel.version, panel.book, panel.chapter, vm.dataGeneration, vm.showHeadings, vm.lineSpacing, vm.textFont, vm.paragraphMode, vm.verseNumbers, vm.sketchOf(panel.book)?.name) {
        val v = panel.version
        val spacing = vm.lineSpacing
        val headingsOn = vm.showHeadings
        val font = vm.textFont
        val style = vm.styleKey()
        val spec = "$headingsOn|$spacing|${style.encode()}"
        if (ctl.layoutSpec != spec) {
            // Headings, spacing or font changed: re-lay out every chapter, staying on the same verse.
            if (ctl.layoutSpec != null) panel.pendingVerse = panel.topVerse
            ctl.layoutSpec = spec
            ctl.layouts.clear()
            ctl.dropUnused()
        }
        val wanted = listOfNotNull(
            panel.book to panel.chapter,
            vm.neighbor(panel.book, panel.chapter, 1),
            vm.neighbor(panel.book, panel.chapter, -1),
        )
        val sketch = vm.sketchOf(panel.book)
        if (sketch != null) {
            // A sketch page: an empty "chapter" whose title is the sketch's name (SKT-1).
            val key = ctl.layoutKey(v, sketch.book, 1)
            if (ctl.layouts[key]?.title?.layoutInput?.text?.text != sketch.name) {
                ctl.layouts[key] = buildChapterLayout(
                    measurer, font.family(), sketch.name, ChapterData(v, sketch.book, 1, listOf(com.biblestudy.app.model.Verse(1, ""))), title = sketch.name,
                )
            }
            vm.ensureLoaded(v, sketch.book, 1) { ctl.layouts[key]!! }
            return@LaunchedEffect
        }
        for ((b, c) in wanted) {
            val key = ctl.layoutKey(v, b, c)
            val name = vm.bible.book(b).name
            if (ctl.layouts[key] == null) {
                ctl.layouts[key] = buildChapter(v, b, c, ctl.spacers[key] ?: emptyMap())
            }
            vm.ensureLoaded(v, b, c) { k ->
                // For converting ink saved before 0.4 (y measured at normal spacing, without
                // headings) or drawn in another font or layout.
                buildChapterLayout(
                    measurer, k.font.family(), name, ChapterData(v, b, c, vm.text(v).chapter(b, c)),
                    paragraphs = if (k.paragraphs) vm.study.paragraphStarts(v, b, c) else null, numbers = k.numbers,
                )
            }
        }
        if (ctl.layouts.size > MAX_LAYOUTS) {
            val keep = wanted.mapTo(HashSet()) { ctl.layoutKey(v, it.first, it.second) }
            ctl.layouts.keys.filter { it !in keep }.take(ctl.layouts.size - MAX_LAYOUTS).forEach { ctl.layouts.remove(it) }
            ctl.dropUnused()
        }
    }

    // Expand to fit (MRG-10): when margin notes outgrow their verse, open space before the next.
    val loadedKeys = ctl.layouts.keys.toSet()
    LaunchedEffect(vm.expandToFit, vm.editCount, vm.pendingLoads, vm.marginLeft, vm.marginRight, vm.layers.toList(), loadedKeys) {
        if (vm.pendingLoads > 0) return@LaunchedEffect
        for (key in loadedKeys) {
            val layout = ctl.layouts[key] ?: continue
            if (vm.sketchOf(layout.book) != null) continue
            val want = vm.fitSpacers(layout)
            if (!sameSpacers(want, layout.spacers)) {
                ctl.spacers[key] = want
                ctl.layouts[key] = buildChapter(layout.version, layout.book, layout.chapter, want)
                vm.fitGaps[key] = want
            }
        }
    }

    val geo = ctl.geo
    SideEffect {
        ctl.panelIndex = index
        ctl.density = density
    }

    // Fit to width when the page or panel size changes; handle jumps (picker, search, arrows).
    LaunchedEffect(geo, panel.viewW, panel.viewH, panel.pendingVerse, panel.navGen) {
        val g = ctl.geo ?: return@LaunchedEffect
        if (panel.viewW <= 0f) return@LaunchedEffect
        if (ctl.resizing == null && (ctl.lastPageW != g.width || ctl.lastViewW != panel.viewW)) {
            ctl.applyRememberedZoom()
            ctl.lastPageW = g.width
            ctl.lastViewW = panel.viewW
        }
        var jumped = false
        if (panel.navGen != ctl.lastNavGen) {
            panel.panY = 0f
            ctl.lastNavGen = panel.navGen
            jumped = true
        }
        panel.pendingVerse?.let { v ->
            ctl.scrollToVerse(v)
            panel.pendingVerse = null
            jumped = true
        }
        ctl.clamp()
        ctl.pendingFollow?.let { ctl.follow(it) }
        if (jumped) ctl.announceScroll() // a linked panel jumps along
    }

    // Linked split view: follow the other panel, and bring it along when linking is switched on.
    val linkPos = vm.linkPos
    LaunchedEffect(linkPos) {
        if (linkPos != null && vm.linked) ctl.follow(linkPos)
    }
    LaunchedEffect(vm.linkPanels, vm.panels.size) {
        if (vm.linked && vm.activePanel == index) ctl.announceScroll()
    }

    // Export the chapter shown here as a PDF or picture (DATA-5); the active panel handles it.
    val exportReq = vm.exportRequest
    LaunchedEffect(exportReq) {
        val req = exportReq ?: return@LaunchedEffect
        if (vm.activePanel.coerceIn(0, vm.panels.lastIndex) != index) return@LaunchedEffect
        vm.exportRequest = null
        val page = ctl.pages().firstOrNull {
            it.layout.version == panel.version && it.layout.book == panel.book && it.layout.chapter == panel.chapter
        }
        if (page == null) {
            vm.message = "The chapter is still loading. Try again in a moment."
            return@LaunchedEffect
        }
        val g = page.geo
        val area = Rect(0f, 0f, g.width, g.height)
        val draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = { drawPage(vm, ctl, page, PageTheme.LIGHT, area, measurer) }
        // Drawn on the main thread, like the screen, since drawing fills the view model's caches.
        val ok = runCatching {
            vm.getApplication<android.app.Application>().contentResolver.openOutputStream(req.uri)?.use {
                val v = com.biblestudy.app.data.BibleRepository.ALL.firstOrNull { b -> b.code == panel.version } ?: vm.bible.version
                val footer = ChapterExport.footerFor(v, "${vm.bible.book(panel.book).name} ${panel.chapter}")
                if (req.pdf) ChapterExport.pdf(it, g.width, g.height, footer, draw) else ChapterExport.png(it, g.width, g.height, footer, draw)
            } ?: error("Couldn't write the file")
        }.isSuccess
        vm.message = if (ok) "Chapter exported." else "Export failed."
    }

    // The tablet's Back gesture steps back through this panel's history when it is the active one.
    BackHandler(enabled = vm.activePanel == index && panel.back.isNotEmpty()) { vm.goBack(index) }

    val active = vm.activePanel == index && vm.panels.size > 1
    Column(
        modifier.then(
            if (active) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier
        )
    ) {
        PanelHeader(vm, index, ctl, onOpenPicker)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("reader$index")
                .clipToBounds() // pages above and below the view must not paint over the header
                .onSizeChanged {
                    panel.viewW = it.width.toFloat()
                    panel.viewH = it.height.toFloat()
                }
        ) {
            // The page: text, highlights, saved ink and images. Redrawn when any of those change.
            // It also takes pen and finger input; the bars and pop-overs drawn above it get their own
            // taps first, so tapping a button never also taps the verse underneath.
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(ctl) {
                        readerGestures(ctl, fingerDraw = { vm.fingerDraw }, readMode = { vm.readMode }, onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        })
                    }
            ) {
                drawRect(theme.surround)
                val pages = ctl.pages()
                if (pages.isEmpty()) return@Canvas
                val view = ctl.visibleRect()
                withTransform({
                    translate(panel.panX, panel.panY)
                    scale(panel.zoom, panel.zoom, pivot = Offset.Zero)
                }) {
                    for (page in pages) {
                        if (page.bottom < view.top || page.top > view.bottom) continue
                        translate(0f, page.top) {
                            drawPage(vm, ctl, page, theme, view.translate(0f, -page.top), measurer)
                        }
                    }
                    // An open margin drawer is drawn again over the text (MRG-14).
                    for (region in listOf(Region.LEFT, Region.RIGHT)) {
                        val shift = ctl.drawerShift(region)
                        if (shift == 0f) continue
                        for (page in pages) {
                            if (page.bottom < view.top || page.top > view.bottom) continue
                            val g = page.geo
                            val (l, r) = if (region == Region.LEFT) 0f to g.leftW else g.colRight to g.width
                            translate(shift, page.top) {
                                val edge = if (region == Region.LEFT) r else l
                                drawRect(Color.Black.copy(alpha = 0.18f), Offset(if (region == Region.LEFT) r else l - 10f, 0f), Size(10f, g.height))
                                clipRect(l, 0f, r, g.height) {
                                    drawPage(vm, ctl, page, theme, view.translate(-shift, -page.top), measurer)
                                }
                                drawLine(theme.rule, Offset(edge, 0f), Offset(edge, g.height), strokeWidth = 1.5f)
                            }
                        }
                    }
                }
            }
            // Fast pen ink (INK-4): a front-buffered surface drawn above the page. It is placed here,
            // under the live layer, so it never takes touches; its surface still shows on top.
            if (vm.fastInk && FastInkView.supported) {
                AndroidView(
                    factory = { ctx -> FastInkView(ctx) },
                    modifier = Modifier.fillMaxSize(),
                    update = { v -> ctl.fastInk = v },
                    onRelease = { if (ctl.fastInk === it) ctl.fastInk = null },
                )
            }
            // Live layer: the stroke or lasso being drawn, selection outlines and margin grips.
            // Kept separate so each new pen point redraws only this, not the whole chapter.
            Canvas(Modifier.fillMaxSize()) {
                drawLiveLayer(vm, ctl, theme)
                // Again over an open margin drawer, where the pen is writing in the margin (MRG-14).
                for (region in listOf(Region.LEFT, Region.RIGHT)) {
                    val shift = ctl.drawerShift(region)
                    val g = ctl.geo ?: continue
                    if (shift == 0f) continue
                    val (l, r) = if (region == Region.LEFT) 0f to g.leftW else g.colRight to g.width
                    val z = ctl.panel.zoom
                    clipRect((l + shift) * z + ctl.panel.panX, 0f, (r + shift) * z + ctl.panel.panX, size.height) {
                        translate(shift * z, 0f) { drawLiveLayer(vm, ctl, theme) }
                    }
                }
            }

            if (geo == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("loading"))
            }
            // Narrow portrait: tabs at the edges slide a margin in over the page (MRG-14).
            if (geo != null && ctl.drawerMode) {
                val scope = rememberCoroutineScope()
                val open = ctl.openDrawer
                for (region in listOf(Region.LEFT, Region.RIGHT)) {
                    if (!geo.visible(region)) continue
                    val isOpen = open == region
                    val label = (if (isOpen) "Hide " else "Show ") + (if (region == Region.LEFT) "left" else "right") + " margin"
                    val pointsRight = (region == Region.RIGHT) == isOpen
                    FilledTonalIconButton(
                        onClick = { scope.launch { ctl.toggleDrawer(region) } },
                        modifier = Modifier.align(if (region == Region.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(horizontal = 2.dp).size(width = 28.dp, height = 64.dp),
                    ) {
                        Icon(
                            if (pointsRight) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = label,
                        )
                    }
                }
            }
            val selected = ctl.selectedImage()
            if (selected != null && vm.tool == Tool.SELECT) {
                ImageBar(vm, ctl, selected.second, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
            vm.passagePop?.takeIf { it.panel == index }?.let { pop ->
                PassagePopover(vm, pop, onDismiss = { vm.passagePop = null })
            }
            // Margin text box: typing in place, and its bar while selected (MRG-12).
            ctl.selectedText()?.let { (page, t) ->
                if (vm.editingText == t.id && (vm.activePanel == index || vm.panels.size == 1)) TextBoxEditor(vm, ctl, page, t)
                TextBoxBar(vm, ctl, t, Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 8.dp, end = 8.dp))
            }
            ctl.textSel?.let { ts ->
                TextSelectionBar(vm, ctl, ts, Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 8.dp, end = 8.dp))
            }
            val sel = vm.selection
            if (sel != null && ctl.pages().any { sel.isOn(it.layout) } && (vm.activePanel == index || vm.panels.size == 1)) {
                SelectionBar(vm, ctl, Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 8.dp, end = 8.dp))
            }
        }
    }
}

@Composable
private fun PanelHeader(vm: StudyViewModel, index: Int, ctl: ReaderController, onOpenPicker: () -> Unit) {
    val panel = vm.panels[index]
    vm.sketchOf(panel.book)?.let { SketchHeader(vm, index, ctl, it); return }
    val book = vm.bible.book(panel.book)
    BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        // Narrow panels (e.g. three side by side) move the less-used buttons into a menu.
        val compact = maxWidth < 620.dp
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!compact) {
                IconButton(onClick = { vm.activePanel = index; vm.goBack(index) }, enabled = panel.back.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                IconButton(onClick = { vm.activePanel = index; vm.goForward(index) }, enabled = panel.forward.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                }
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            }
            IconButton(onClick = { vm.activePanel = index; vm.prevChapter(index) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous chapter")
            }
            TextButton(onClick = { vm.activePanel = index; onOpenPicker() }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("${book.name} ${panel.chapter}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.activePanel = index; vm.nextChapter(index) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next chapter")
            }
            if (!compact) {
                IconButton(onClick = { vm.activePanel = index; vm.introBook = panel.book }) {
                    Icon(Icons.Outlined.Info, contentDescription = "About this book")
                }
            }
            VersionPicker(vm, index)
            Spacer(Modifier.weight(1f))
            if (!compact) TextButton(onClick = { ctl.fitWidth() }) { Text("Fit width") }
            if (vm.panels.size > 1 && !compact) {
                IconButton(onClick = { vm.activePanel = index; vm.linkPanels = !vm.linkPanels }) {
                    Icon(
                        if (vm.linkPanels) Icons.Filled.Link else Icons.Filled.LinkOff,
                        contentDescription = if (vm.linkPanels) "Unlink panels" else "Link panels",
                        tint = if (vm.linkPanels) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (compact) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { vm.activePanel = index; menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Panel menu")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Back") }, enabled = panel.back.isNotEmpty(), onClick = { menu = false; vm.goBack(index) })
                        DropdownMenuItem(text = { Text("Forward") }, enabled = panel.forward.isNotEmpty(), onClick = { menu = false; vm.goForward(index) })
                        DropdownMenuItem(text = { Text("About this book") }, onClick = { menu = false; vm.introBook = panel.book })
                        DropdownMenuItem(text = { Text("Fit width") }, onClick = { menu = false; ctl.fitWidth() })
                        if (vm.panels.size > 1) {
                            DropdownMenuItem(
                                text = { Text(if (vm.linkPanels) "Unlink panels" else "Link panels") },
                                onClick = { menu = false; vm.activePanel = index; vm.linkPanels = !vm.linkPanels },
                            )
                        }
                        if (vm.panels.size > 1) {
                            DropdownMenuItem(text = { Text("Close panel") }, onClick = { menu = false; vm.closePanel(index) })
                        }
                    }
                }
            } else if (vm.panels.size > 1) {
                IconButton(onClick = { vm.closePanel(index) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close panel")
                }
            }
        }
    }
}

/**
 * A sketch page's header (SKT-1, SKT-2): Back to where you were, the page's name, its passage,
 * and one menu for paper, more space, renaming and deleting.
 */
@Composable
private fun SketchHeader(vm: StudyViewModel, index: Int, ctl: ReaderController, sk: com.biblestudy.app.model.Sketch) {
    val panel = vm.panels[index]
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.activePanel = index; vm.goBack(index) }, enabled = panel.back.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(sk.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        TextButton(onClick = { vm.goTo(index, sk.linkBook, sk.linkChapter, sk.linkVerse) }) {
            Text("on " + vm.refLabel(com.biblestudy.app.model.VerseId.of(sk.linkBook, sk.linkChapter, sk.linkVerse)), maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { ctl.fitWidth() }) { Text("Fit width") }
        Box {
            IconButton(onClick = { vm.activePanel = index; menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Sketch page menu") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text("Paper", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                for (p in com.biblestudy.app.model.Paper.entries) {
                    DropdownMenuItem(
                        text = { Text(p.label + if (p == sk.paper) "  \u2713" else "") },
                        onClick = { vm.updateSketch(sk.copy(paper = p)); menu = false },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("More space below") }, onClick = { vm.updateSketch(sk.copy(height = sk.height + com.biblestudy.app.model.Sketch.START_HEIGHT / 2)); menu = false })
                DropdownMenuItem(text = { Text("Rename\u2026") }, onClick = { renaming = true; menu = false })
                DropdownMenuItem(text = { Text("Delete sketch page\u2026") }, onClick = { deleting = true; menu = false })
                if (vm.panels.size > 1) DropdownMenuItem(text = { Text("Close panel") }, onClick = { menu = false; vm.closePanel(index) })
            }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(sk.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename sketch page") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) vm.updateSketch(sk.copy(name = name.trim())); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \u201c${sk.name}\u201d?") },
            text = { Text("Everything drawn and written on this sketch page is deleted. This can't be undone.") },
            confirmButton = { TextButton(onClick = { deleting = false; vm.deleteSketch(sk) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** One-tap version switch (BIB-3). Ink on the words stays with its version; margin notes are shared. */
@Composable
private fun VersionPicker(vm: StudyViewModel, index: Int) {
    val panel = vm.panels[index]
    var open by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { vm.activePanel = index; open = true }) {
            Text(panel.version, style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change Bible version")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (v in BibleRepository.ALL) {
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                "${v.code} \u2014 ${v.name}" + if (v.code == panel.version) "  \u2713" else "",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(v.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    },
                    onClick = { open = false; vm.setVersion(index, v.code) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("About these versions\u2026") }, onClick = { open = false; about = true })
        }
    }
    if (about) VersionsDialog { about = false }
}

/** Actions for text selected with a long press (PEN-3, NOTE-2). */
@Composable
private fun TextSelectionBar(vm: StudyViewModel, ctl: ReaderController, ts: TextSel, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(modifier, shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(ctl.selectionLabel(ts), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { clipboard.setText(AnnotatedString(ctl.selectionText(ts))); ctl.clearTextSelect() }) { Text("Copy") }
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ctl.selectionText(ts))
                context.startActivity(Intent.createChooser(send, null))
                ctl.clearTextSelect()
            }) { Text("Share") }
            val h = ts.highlight
            if (h == null) {
                TextButton(onClick = { vm.addHighlight(ts.layout, ts.start, ts.end); ctl.clearTextSelect() }) { Text("Highlight") }
            } else {
                // A highlight was long-pressed: recolour or remove it.
                for (c in HIGHLIGHT_COLORS) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(
                                if (c == h.color) 3.dp else 1.dp,
                                if (c == h.color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            )
                            .semantics { contentDescription = "Highlight colour" + if (c == h.color) " (current)" else "" }
                            .clickable { vm.recolorHighlight(h, c)?.let { ctl.updateSelectedHighlight(it) } }
                    )
                }
                TextButton(onClick = { vm.removeHighlight(h); ctl.clearTextSelect() }) { Text("Remove highlight") }
            }
            TextButton(onClick = {
                val l = ts.layout
                vm.openVerse(l.book, l.chapter, l.verseAtOffset(ts.start))
                ctl.clearTextSelect()
            }) { Text("Note") }
            IconButton(onClick = ctl::clearTextSelect) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
        }
    }
}

/** Actions for the lasso selection: recolour, copy, move to a layer, delete. */
@Composable
private fun SelectionBar(vm: StudyViewModel, ctl: ReaderController, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val count = vm.selectedItems().size
            Text("$count selected", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.size(4.dp))
            // One colour button instead of a row of swatches (UI-1).
            var colours by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { colours = true }) { Text("Colour") }
                DropdownMenu(expanded = colours, onDismissRequest = { colours = false }) {
                    for (row in listOf(PEN_COLORS, HIGHLIGHT_COLORS)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in row) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(c))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                        .clickable { vm.recolorSelection(c); colours = false }
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = {
                val sel = vm.selection
                ctl.pages().firstOrNull { sel != null && sel.isOn(it.layout) }?.let { vm.copySelection(it.layout) }
            }) { Text("Copy") }
            var layerMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { layerMenu = true }) { Text("Move to layer") }
                DropdownMenu(expanded = layerMenu, onDismissRequest = { layerMenu = false }) {
                    for (l in vm.layers) {
                        DropdownMenuItem(
                            text = { Text(l.name + if (l.locked) " (locked)" else "") },
                            enabled = !l.locked,
                            onClick = { layerMenu = false; vm.moveSelectionToLayer(l.id) },
                        )
                    }
                }
            }
            TextButton(onClick = vm::deleteSelection) { Text("Delete") }
            TextButton(onClick = vm::clearSelection) { Text("Done") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------------------------

/** Draws one chapter page in its own page coordinates. [view] is the visible area in page units. */
/** A snapped underline (HL-4) under a character range, in text coordinates. */
private fun DrawScope.drawUnderline(layout: ChapterLayout, start: Int, end: Int, color: Color) {
    for ((x0, x1, y) in layout.underlines(start, end)) {
        drawLine(color, Offset(x0, y), Offset(x1, y), strokeWidth = 3.5f, cap = StrokeCap.Round)
    }
}

/** Draws [block] see-through at [alpha] (a faded layer, LAY-8), or as it is when [alpha] is null. */
private inline fun DrawScope.withOpacity(alpha: Float?, bounds: Rect, block: DrawScope.() -> Unit) {
    if (alpha == null) { block(); return }
    drawContext.canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
    block()
    drawContext.canvas.restore()
}

/** The colour of a sketch page's badge in the margin. */
private val SKETCH_BADGE = Color(0xFF6A8CAF)

/** Whether two sets of expand-to-fit gaps are the same, give or take a few units. */
private fun sameSpacers(a: Map<Int, Float>, b: Map<Int, Float>): Boolean =
    (a.keys + b.keys).all { kotlin.math.abs((a[it] ?: 0f) - (b[it] ?: 0f)) < 8f }

/** Lines, a grid or dots on a sketch page (SKT-1). */
private fun DrawScope.drawPaper(paper: com.biblestudy.app.model.Paper, g: PageGeometry, theme: PageTheme) {
    val c = theme.rule.copy(alpha = 0.55f)
    val step = 48f
    val top = Page.TEXT_TOP
    when (paper) {
        com.biblestudy.app.model.Paper.BLANK -> {}
        com.biblestudy.app.model.Paper.LINED -> {
            var y = top + step
            while (y < g.height) { drawLine(c, Offset(40f, y), Offset(g.width - 40f, y), strokeWidth = 1.2f); y += step }
        }
        com.biblestudy.app.model.Paper.GRID -> {
            var y = top
            while (y < g.height) { drawLine(c, Offset(0f, y), Offset(g.width, y), strokeWidth = 1f); y += step }
            var x = 0f
            while (x < g.width) { drawLine(c, Offset(x, top), Offset(x, g.height), strokeWidth = 1f); x += step }
        }
        com.biblestudy.app.model.Paper.DOTTED -> {
            var y = top
            while (y < g.height) {
                var x = step / 2
                while (x < g.width) { drawCircle(c, 2.2f, Offset(x, y)); x += step }
                y += step
            }
        }
    }
}

private fun DrawScope.drawPage(vm: StudyViewModel, ctl: ReaderController, page: PlacedPage, theme: PageTheme, view: Rect, measurer: TextMeasurer) {
    val g = page.geo
    val layout = g.layout

    // Layer order: later layers draw on top. Hidden layers are skipped entirely.
    val order = vm.layers.filter { it.visible }.map { it.id }
    // A layer can be faded (LAY-8): its ink, highlights and pictures are drawn see-through.
    val fade = vm.layers.filter { it.visible && it.opacity < 0.999f }.associate { it.id to it.opacity }
    val pageRect = Rect(0f, 0f, g.width, g.height)

    val textStrokes = vm.textStrokesFor(layout.version, layout.book, layout.chapter)
    val marginStrokes = vm.marginStrokesFor(layout.book, layout.chapter)
    val highlights = vm.highlightsFor(layout.version, layout.book, layout.chapter)
    val crossHighlights = vm.crossHighlights(layout.version, layout.book, layout.chapter)
    val images = vm.imagesFor(layout.book, layout.chapter)
    val notes = vm.notesFor(layout.book, layout.chapter)

    // Items being dragged with the lasso are drawn shifted by the drag.
    val sel = vm.selection
    val moving = if (sel != null && sel.isOn(layout) && ctl.moveOffset != Offset.Zero) sel.ids else emptySet()
    val shift = ctl.moveOffset

    // Paper and margins
    drawRect(theme.page, size = Size(g.width, g.height))
    val sketch = vm.sketchOf(layout.book)
    if (sketch != null) drawPaper(sketch.paper, g, theme)
    if (g.left && sketch == null) {
        drawRect(theme.margin, topLeft = Offset.Zero, size = Size(g.leftW, g.height))
        drawLine(theme.rule, Offset(g.leftW, 0f), Offset(g.leftW, g.height), strokeWidth = 1.5f)
    }
    if (g.right && sketch == null) {
        drawRect(theme.margin, topLeft = Offset(g.colRight, 0f), size = Size(g.rightW, g.height))
        drawLine(theme.rule, Offset(g.colRight, 0f), Offset(g.colRight, g.height), strokeWidth = 1.5f)
    }
    // Faint line between chapters in the continuous strip
    drawLine(theme.rule, Offset(0f, g.height), Offset(g.width, g.height), strokeWidth = 3f)

    // Highlights sit beneath the text.
    for (layerId in order) withOpacity(fade[layerId], pageRect) {
        translate(g.textLeft, Page.TEXT_TOP) {
            // Highlights made in other translations cover whole verses, a shade lighter (HL-10).
            for (x in crossHighlights) {
                if (x.source.layerId != layerId) continue
                val r = layout.versesRange(x.fromVerse, x.toVerse) ?: continue
                if (x.source.underline) {
                    drawUnderline(layout, r.first, r.last + 1, Color(x.source.color).copy(alpha = 0.5f))
                } else {
                    drawPath(layout.highlightPath(-x.source.id, r.first, r.last + 1), Color(x.source.color).copy(alpha = CROSS_HIGHLIGHT_ALPHA))
                }
            }
            for (h in highlights) {
                if (h.layerId != layerId) continue
                if (h.underline) drawUnderline(layout, h.start, h.end, Color(h.color))
                else drawPath(ctl.highlightPath(h, layout), Color(h.color).copy(alpha = HIGHLIGHT_ALPHA))
            }
        }
        drawStrokes(vm, g, view, textStrokes, marginStrokes, layerId, highlighter = true, moving, shift)
    }

    // Scripture text, drawn in runs between section headings
    drawText(layout.title, color = theme.text, topLeft = Offset(g.textLeft, Page.TITLE_TOP))
    // Sketch pages linked to this chapter: a badge beside the verse (SKT-2).
    if (sketch == null) {
        for (sk in vm.sketchesIn(layout.book, layout.chapter)) {
            val c = ReaderController.sketchBadgeCenter(g, layout, sk)
            drawRoundRect(SKETCH_BADGE, topLeft = c - Offset(16f, 13f), size = Size(32f, 26f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f))
            drawLine(Color.White, c + Offset(-8f, 6f), c + Offset(8f, -6f), strokeWidth = 3f)
            drawCircle(Color.White, 3f, c + Offset(-8f, 6f))
        }
    }
    if (sketch == null) for ((first, last, dy) in layout.segments) {
        val (clipTop, clipBottom) = layout.segmentClip(first, last)
        translate(g.textLeft, Page.TEXT_TOP + dy) {
            clipRect(left = -Page.COL_PAD, top = clipTop, right = Page.TEXT_W + Page.COL_PAD, bottom = clipBottom) {
                drawText(layout.text, color = theme.text)
            }
        }
    }
    for (block in layout.headings) {
        layout.headingLineTops(block).forEachIndexed { i, y ->
            val line = block.lines[i]
            // Heading text takes the page's text colour; the reference line keeps its own muted colour.
            if (i in block.links || line.layoutInput.style.fontSize.value < 16f) {
                drawText(line, topLeft = Offset(g.textLeft, Page.TEXT_TOP + y))
            } else {
                drawText(line, color = theme.text, topLeft = Offset(g.textLeft, Page.TEXT_TOP + y))
            }
        }
    }

    // Verse markers: a dot for a typed note
    for (n in notes.values) {
        val top = layout.verseTop(n.verse) + 22f
        drawCircle(NOTE_COLOR, radius = 6f, center = Offset(g.textLeft - 24f, top))
        if (n.endVerse > n.verse) {
            // A note on several verses: a line down beside them (NOTE-1).
            val bottom = layout.verseSpan(n.endVerse).second - 10f
            drawLine(NOTE_COLOR, Offset(g.textLeft - 24f, top), Offset(g.textLeft - 24f, bottom), strokeWidth = 3f)
        }
    }

    // Margin images, then pen ink, layer by layer.
    for (layerId in order) withOpacity(fade[layerId], pageRect) {
        for (img in images) {
            if (img.layerId != layerId || !g.visible(img.region)) continue
            val r = ctl.imageRect(g, img).let { if (img.id in moving) it.translate(shift) else it }
            if (!ReaderController.overlaps(r, view)) continue
            val bmp = vm.bitmap(img.file)
            if (bmp == null) {
                drawRect(theme.rule, topLeft = r.topLeft, size = r.size)
            } else {
                // Cropped (the part of the picture kept) and turned in quarter turns (MRG-8).
                val src = IntOffset((img.cropL * bmp.width).roundToInt(), (img.cropT * bmp.height).roundToInt())
                val srcSize = IntSize(
                    ((img.cropR - img.cropL) * bmp.width).roundToInt().coerceAtLeast(1),
                    ((img.cropB - img.cropT) * bmp.height).roundToInt().coerceAtLeast(1),
                )
                val sideways = img.rotation % 2 == 1
                val dw = if (sideways) r.height else r.width
                val dh = if (sideways) r.width else r.height
                rotate(img.rotation * 90f, pivot = r.center) {
                    drawImage(
                        bmp,
                        srcOffset = src,
                        srcSize = srcSize,
                        dstOffset = IntOffset((r.center.x - dw / 2f).roundToInt(), (r.center.y - dh / 2f).roundToInt()),
                        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
        // Margin text boxes (MRG-12)
        for (t in vm.textsFor(layout.book, layout.chapter)) {
            if (t.layerId != layerId || !g.visible(t.region)) continue
            val r = ctl.textRect(g, t).let { if (t.id in moving) it.translate(shift) else it }
            if (!ReaderController.overlaps(r, view)) continue
            drawTextBox(vm, measurer, t, r.left, r.top, selected = ctl.selectedTextId == t.id, editing = vm.editingText == t.id)
        }
        drawStrokes(vm, g, view, textStrokes, marginStrokes, layerId, highlighter = false, moving, shift)
    }
}

private fun DrawScope.drawStrokes(
    vm: StudyViewModel,
    g: PageGeometry,
    view: Rect,
    textStrokes: List<InkStroke>,
    marginStrokes: List<InkStroke>,
    layerId: Long,
    highlighter: Boolean,
    moving: Set<Long>,
    shift: Offset,
) {
    for (s in textStrokes) {
        if (s.layerId != layerId || s.highlighter != highlighter) continue
        val d = if (s.id in moving) shift else Offset.Zero
        val ox = g.textLeft + d.x
        val oy = Page.TEXT_TOP + d.y
        val r = g.layout.render(s)
        if (!ReaderController.overlaps(r.bounds.translate(ox, oy), view)) continue
        drawStrokeRender(r, strokeColor(s.color, highlighter), ox, oy)
    }
    for (s in marginStrokes) {
        if (s.layerId != layerId || s.highlighter != highlighter || !g.visible(s.region)) continue
        val d = if (s.id in moving) shift else Offset.Zero
        val ox = g.originX(s.region) + d.x
        val oy = g.originY(s.region, s.verse) + d.y
        val r = vm.render(s)
        if (!ReaderController.overlaps(r.bounds.translate(ox, oy), view)) continue
        drawStrokeRender(r, strokeColor(s.color, highlighter), ox, oy)
    }
}

private fun strokeColor(c: Int, highlighter: Boolean) =
    Color(c).let { if (highlighter) it.copy(alpha = HIGHLIGHT_ALPHA) else it }

private val SELECT_BLUE = Color(0xFF1E88E5)
private val NOTE_COLOR = Color(0xFFA07B45)
private const val TEXT_SEL_ID = Long.MIN_VALUE

/** The stroke or lasso in progress, selection outlines and margin grips. */
private fun DrawScope.drawLiveLayer(vm: StudyViewModel, ctl: ReaderController, theme: PageTheme) {
    val panel = ctl.panel
    val zoom = panel.zoom
    withTransform({
        translate(panel.panX, panel.panY)
        scale(zoom, zoom, pivot = Offset.Zero)
    }) {
        ctl.live?.takeIf { !ctl.fastStroke }?.let { ink ->
            ink.tick // redraw on every new point
            val r = buildRender(ink.toArray(), ink.width, ink.highlighter)
            val c = Color(ink.color).let { if (ink.highlighter) it.copy(alpha = HIGHLIGHT_ALPHA) else it }
            drawStrokeRender(r, c, ink.ox, ink.page.top + ink.oy)
        }

        ctl.textSel?.let { ts ->
            val page = ctl.pages().firstOrNull { it.layout === ts.layout }
            if (page != null) {
                translate(page.geo.textLeft, page.top + Page.TEXT_TOP) {
                    drawPath(ts.layout.highlightPath(TEXT_SEL_ID, ts.start, ts.end), SELECT_BLUE.copy(alpha = 0.28f))
                }
            }
        }

        ctl.lasso?.let { l ->
            l.tick
            if (l.points.size > 1) {
                val path = Path()
                path.moveTo(l.points[0].x, l.points[0].y + l.page.top)
                for (i in 1 until l.points.size) path.lineTo(l.points[i].x, l.points[i].y + l.page.top)
                drawPath(
                    path, SELECT_BLUE,
                    style = Stroke(width = 2.5f / zoom, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f / zoom, 10f / zoom))),
                )
            }
        }

        val sel = vm.selection
        if (sel != null) {
            for (page in ctl.pages()) {
                if (!sel.isOn(page.layout)) continue
                val b = ctl.selectionBounds(page) ?: continue
                val k = ctl.resizeScale
                val grown = Rect(b.left, b.top, b.left + b.width * k, b.top + b.height * k)
                val r = grown.inflate(10f / zoom).translate(ctl.moveOffset.x, ctl.moveOffset.y + page.top)
                rotate(Math.toDegrees(ctl.rotateAngle.toDouble()).toFloat(), pivot = b.center.plus(Offset(0f, page.top))) {
                    drawRect(SELECT_BLUE.copy(alpha = 0.06f), topLeft = r.topLeft, size = r.size)
                    drawRect(
                        SELECT_BLUE, topLeft = r.topLeft, size = r.size,
                        style = Stroke(width = 2f / zoom, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f / zoom, 8f / zoom))),
                    )
                    // Drag the corner to resize, the handle above to turn (INK-11).
                    drawCircle(SELECT_BLUE, radius = 12f / zoom, center = r.bottomRight)
                    if (k == 1f) {
                        val h = ctl.rotateHandle(b).plus(Offset(ctl.moveOffset.x, ctl.moveOffset.y + page.top))
                        drawLine(SELECT_BLUE, Offset(h.x, r.top), h, strokeWidth = 2f / zoom)
                        drawCircle(Color.White, radius = 12f / zoom, center = h)
                        drawCircle(SELECT_BLUE, radius = 12f / zoom, center = h, style = Stroke(width = 3f / zoom))
                    }
                }
            }
        }

        ctl.selectedImage()?.let { (page, img) ->
            val r = ctl.imageRect(page.geo, img).translate(0f, page.top)
            drawRect(SELECT_BLUE, topLeft = r.topLeft, size = r.size, style = Stroke(width = 2.5f / zoom))
            drawCircle(SELECT_BLUE, radius = 12f / zoom, center = r.bottomRight)
        }
    }

    // Margin grips (screen coordinates): drag to resize a margin.
    val gripW = 10f * ctl.density
    val gripH = 56f * ctl.density
    val active = ctl.resizing
    for (left in listOf(true, false)) {
        val x = ctl.marginEdgeX(left) ?: continue
        val color = if (active == left) SELECT_BLUE else theme.rule.copy(alpha = 1f)
        val top = size.height / 2f - gripH / 2f
        drawRoundRect(
            color, topLeft = Offset(x - gripW / 2f, top), size = Size(gripW, gripH),
            cornerRadius = CornerRadius(gripW / 2f),
        )
        for (k in -1..1) {
            drawCircle(theme.page, radius = 1.6f * ctl.density, center = Offset(x, size.height / 2f + k * 8f * ctl.density))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Input: pen draws, fingers scroll/zoom/tap. Touches right after pen use are treated as palm.
// ---------------------------------------------------------------------------------------------

private fun PointerInputChange.isPen() =
    type == PointerType.Stylus || type == PointerType.Eraser || type == PointerType.Mouse

private suspend fun PointerInputScope.readerGestures(
    ctl: ReaderController,
    fingerDraw: () -> Boolean,
    readMode: () -> Boolean,
    onLongPress: () -> Unit,
) = coroutineScope {
    var fling: Job? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        fling?.cancel()
        ctl.touched()

        if (!down.isPen() && !ctl.recentlyPenned()) {
            val left = ctl.marginGripAt(down.position)
            if (left != null) {
                trackResize(down, left, ctl)
                return@awaitEachGesture
            }
        }
        // In read mode (PEN-4) the pen scrolls and taps like a finger.
        if (!readMode() && (down.isPen() || (down.type == PointerType.Touch && fingerDraw()))) {
            trackPen(down, ctl)
            return@awaitEachGesture
        }
        if (ctl.recentlyPenned()) {
            consumeUntilUp() // palm resting while writing
            return@awaitEachGesture
        }
        var travelled = 0f
        var multiTouch = false
        var penDown: PointerInputChange? = null
        var lastTime = down.uptimeMillis
        var waitingForLongPress = true
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, down.position)
        while (true) {
            // Until the finger moves, lifts or a second finger lands, holding still selects text.
            val event = if (waitingForLongPress && !multiTouch && travelled < viewConfiguration.touchSlop) {
                val remaining = viewConfiguration.longPressTimeoutMillis - (lastTime - down.uptimeMillis)
                withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                waitingForLongPress = false
                if (ctl.startTextSelect(down.position)) {
                    onLongPress()
                    trackTextSelect(down, ctl)
                    return@awaitEachGesture
                }
                continue
            }
            lastTime = event.changes.first().uptimeMillis
            penDown = event.changes.firstOrNull { it.pressed && !it.previousPressed && it.isPen() }
            if (penDown != null) break
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } > 1) multiTouch = true
            event.changes.firstOrNull { it.id == down.id }?.let { velocity.addPosition(it.uptimeMillis, it.position) }
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val centroid = event.calculateCentroid(useCurrent = true)
            travelled += pan.getDistance()
            if (centroid.isSpecified && (zoom != 1f || pan != Offset.Zero)) ctl.transform(centroid, pan, zoom)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
        val pen = penDown
        if (pen != null) {
            trackPen(pen, ctl) // pen touched down while palm/finger was resting
        } else if (!multiTouch && travelled < viewConfiguration.touchSlop) {
            // A second tap close by toggles fit-width; otherwise it was a single tap on a verse.
            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                awaitFirstDown(requireUnconsumed = false)
            }
            if (second != null && !second.isPen() && (second.position - down.position).getDistance() < 48.dp.toPx()) {
                consumeUntilUp()
                ctl.toggleFit(second.position)
            } else {
                ctl.onTap(down.position)
                if (second != null && second.isPen()) trackPen(second, ctl) // writing straight after a tap
            }
        } else if (!multiTouch) {
            // Keep scrolling after a flick, slowing down naturally.
            val v = velocity.calculateVelocity()
            if (abs(v.y) > 300f) {
                val decay = splineBasedDecay<Float>(this@readerGestures)
                fling = launch {
                    var last = 0f
                    AnimationState(initialValue = 0f, initialVelocity = v.y).animateDecay(decay) {
                        ctl.transform(Offset.Zero, Offset(0f, value - last), 1f)
                        last = value
                    }
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/** Long-press selection: the finger drags the end of the selection until it lifts. */
private suspend fun AwaitPointerEventScope.trackTextSelect(first: PointerInputChange, ctl: ReaderController) {
    while (true) {
        val event = awaitPointerEvent()
        val c = event.changes.firstOrNull { it.id == first.id }
        if (c == null || !c.pressed) break
        ctl.extendTextSelect(c.position)
        event.changes.forEach { it.consume() }
    }
}

private suspend fun AwaitPointerEventScope.trackResize(first: PointerInputChange, left: Boolean, ctl: ReaderController) {
    ctl.startResize(left)
    first.consume()
    try {
        while (true) {
            val event = awaitPointerEvent()
            val c = event.changes.firstOrNull { it.id == first.id }
            if (c == null || !c.pressed) break
            ctl.resizeTo(c.position.x)
            event.changes.forEach { it.consume() }
        }
    } finally {
        ctl.endResize()
    }
}

/** The eraser end of the pen always erases; the side button does what the user chose. */
private fun penToolOverride(first: PointerInputChange, ctl: ReaderController): Tool? = when {
    first.type == PointerType.Eraser -> Tool.ERASER
    first.type == PointerType.Stylus && StylusState.sideButtonHeld -> ctl.sideButtonTool()
    else -> null
}

/** How long the pen is held still to snap a stroke to a shape (INK-12). */
private const val SHAPE_HOLD_MS = 600L

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun AwaitPointerEventScope.trackPen(first: PointerInputChange, ctl: ReaderController) {
    ctl.penStart(first.position, first.pressure, penToolOverride(first, ctl))
    first.consume()
    // Holding the pen still at the end of a stroke snaps it to a shape (INK-12). A resting pen still
    // jitters, so "still" means within a few pixels since the last real movement.
    var stillAt = first.position
    var stillSince = first.uptimeMillis
    var held = false
    try {
        while (true) {
            val event = withTimeoutOrNull(SHAPE_HOLD_MS) { awaitPointerEvent() }
            if (event == null) {
                if (!held) { held = true; ctl.penHold() }
                continue
            }
            val c = event.changes.firstOrNull { it.id == first.id }
            if (c == null || !c.pressed) break
            for (h in c.historical) ctl.penMove(h.position, c.pressure)
            ctl.penMove(c.position, c.pressure)
            if ((c.position - stillAt).getDistance() > 6f * density) {
                stillAt = c.position
                stillSince = c.uptimeMillis
            } else if (!held && c.uptimeMillis - stillSince >= SHAPE_HOLD_MS) {
                held = true
                ctl.penHold()
            }
            event.changes.forEach { it.consume() }
        }
    } finally {
        ctl.penEnd()
    }
}
