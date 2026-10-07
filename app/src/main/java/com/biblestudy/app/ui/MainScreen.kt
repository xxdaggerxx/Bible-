package com.biblestudy.app.ui

import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private enum class DialogKind { PICKER, SEARCH, LAYERS, NOTES, ABOUT, RESTORE, SETTINGS, STATS, HELP }

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5C2E),
    secondary = Color(0xFF6D4C41),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE0C08A),
    secondary = Color(0xFFBCAAA4),
)

@Composable
fun StudyApp(vm: StudyViewModel) {
    MaterialTheme(colorScheme = if (vm.theme.dark) DarkColors else LightColors) {
        var dialog by remember { mutableStateOf<DialogKind?>(null) }
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(vm.message) {
            val m = vm.message ?: return@LaunchedEffect
            vm.message = null
            snackbar.showSnackbar(m)
        }

        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) vm.insertImage(uri)
        }
        val openImageFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.insertImage(uri)
        }
        var cameraUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
        val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            cameraUri?.takeIf { ok }?.let { vm.insertImage(it) }
        }
        val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) vm.backup(uri)
        }
        // Chapter export (DATA-5): pick where to save, then the active panel draws it.
        // Set when exporting one layer from the Layers window (LAY-11).
        var exportLayer by remember { mutableStateOf<Long?>(null) }
        val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) vm.exportRequest = ExportRequest(uri, pdf = true, layer = exportLayer)
            exportLayer = null
        }
        val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri != null) vm.exportRequest = ExportRequest(uri, pdf = false)
        }
        val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.restore(uri)
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                StudyToolbar(
                    vm = vm,
                    onLayers = { dialog = DialogKind.LAYERS },
                    onSearch = { dialog = DialogKind.SEARCH },
                    onNotes = { dialog = DialogKind.NOTES },
                    onInsertImage = { src ->
                        when (src) {
                            ImageSource.GALLERY ->
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            ImageSource.CAMERA -> {
                                val uri = vm.newCameraUri()
                                cameraUri = uri
                                runCatching { takePhoto.launch(uri) }.onFailure { vm.message = "No camera app is available." }
                            }
                            ImageSource.FILES -> openImageFile.launch(arrayOf("image/*"))
                            ImageSource.CLIPBOARD -> vm.pasteImage()
                        }
                    },
                    onBackup = {
                        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                        backupLauncher.launch("ink-and-word-backup-$stamp.zip")
                    },
                    onExport = { pdf ->
                        val name = vm.exportName() + if (pdf) ".pdf" else ".png"
                        runCatching { (if (pdf) exportPdf else exportPng).launch(name) }.onFailure { vm.message = "No file app is available." }
                    },
                    onSettings = { dialog = DialogKind.SETTINGS },
                    onStats = { dialog = DialogKind.STATS },
                    onHelp = { dialog = DialogKind.HELP },
                    onAbout = { dialog = DialogKind.ABOUT },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().background(vm.theme.surround)) {
                // Tabs (TAB-1, TAB-2): the strip shows once there's a second tab.
                if (vm.tabs.size > 1) TabStrip(vm)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val landscape = maxWidth >= maxHeight
                    SideEffect { vm.landscape = landscape }
                    val openPicker = { dialog = DialogKind.PICKER }
                    val widthClass = WidthClass.of(maxWidth.value)
                    SideEffect { vm.widthClass = widthClass }
                    // A phone, or a foldable opened into a tablet (PH-1, PH-11).
                    val phone = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp in 1 until 600
                    SideEffect { vm.phone = phone }
                    val tab = vm.tab
                    // One or two panels (SPLIT-8), side by side or one above the other.
                    val stacked = vm.isStacked(tab)
                    val density = LocalDensity.current
                    val totalPx = with(density) { (if (stacked) maxHeight else maxWidth).toPx() }
                    val slots = tab.slots()

                    @Composable
                    fun Cell(slot: Slot, modifier: Modifier) {
                        when (slot) {
                            is Slot.Bible -> key(tab.panels[slot.index]) { ReaderPanel(vm, slot.index, openPicker, modifier) }
                            is Slot.Study -> key(slot) { StudyPane(vm, slot, modifier) }
                        }
                    }

                    @Composable
                    fun Divider() {
                        val state = rememberDraggableState { d -> vm.dragDivider(0, d / totalPx) }
                        Box(
                            (if (stacked) Modifier.height(14.dp).fillMaxWidth() else Modifier.width(14.dp).fillMaxHeight())
                                .draggable(state, if (stacked) Orientation.Vertical else Orientation.Horizontal)
                                // Double-tap: both panels the same size.
                                .pointerInput(tab) { detectTapGestures(onDoubleTap = { tab.split = 0.5f }) }
                                .testTag("divider"),
                            contentAlignment = Alignment.Center,
                        ) { Handle(vertical = !stacked) }
                    }

                    // On a phone a second panel opened (a study view, say) comes to the front.
                    LaunchedEffect(tab, slots.size, slots.lastOrNull()) { if (slots.size == 2) vm.phoneShown = 1 }
                    key(tab) {
                        if (slots.size < 2) {
                            slots.firstOrNull()?.let { Cell(it, Modifier.fillMaxSize()) }
                        } else if (phone) {
                            // Phones show one panel at a time; the other is a bar to tap (PH-6).
                            val shown = vm.phoneShown.coerceIn(0, 1)
                            Column(Modifier.fillMaxSize()) {
                                Cell(slots[shown], Modifier.weight(1f).fillMaxWidth())
                                CollapsedPanel(vm, slots[1 - shown]) { vm.phoneShown = 1 - shown }
                            }
                        } else if (stacked) {
                            Column(Modifier.fillMaxSize()) {
                                Cell(slots[0], Modifier.weight(tab.split).fillMaxWidth())
                                Divider()
                                Cell(slots[1], Modifier.weight(1f - tab.split).fillMaxWidth())
                            }
                        } else {
                            Row(Modifier.fillMaxSize()) {
                                Cell(slots[0], Modifier.weight(tab.split).fillMaxHeight())
                                Divider()
                                Cell(slots[1], Modifier.weight(1f - tab.split).fillMaxHeight())
                            }
                        }
                    }
                    // The tablet's Back gesture is the toolbar's Back (NAV-1).
                    androidx.activity.compose.BackHandler(enabled = vm.backSteps.isNotEmpty()) { vm.back() }
                    // The AI chat bubble (AI-1) and its little window (AI-10), over the text so you can keep reading.
                    if (vm.chat.enabled && PaneKind.CHAT !in tab.studies) {
                        if (vm.chatWindow) ChatWindow(
                            vm,
                            Modifier.align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 72.dp)
                                .width(minOf(440.dp, maxWidth - 32.dp))
                                .height(minOf(640.dp, maxHeight - 96.dp)),
                        )
                        androidx.compose.material3.SmallFloatingActionButton(
                            onClick = { if (vm.chatWindow) vm.chatWindow = false else vm.openChat() },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("chatBubble"),
                        ) {
                            if (vm.chatWindow) Icon(Icons.Filled.Close, contentDescription = "Close the AI chat")
                            else Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "AI chat")
                        }
                    }
                }
            }
        }

        when (dialog) {
            DialogKind.PICKER -> BookPickerDialog(vm) { dialog = null }
            DialogKind.SEARCH -> SearchDialog(vm) { dialog = null }
            DialogKind.LAYERS -> LayersDialog(vm, onExportLayer = { id ->
                dialog = null
                exportLayer = id
                val layer = vm.layers.firstOrNull { it.id == id }?.name ?: "layer"
                runCatching { exportPdf.launch("${vm.exportName()} - $layer.pdf") }.onFailure { vm.message = "No file app is available." }
            }) { dialog = null }
            DialogKind.NOTES -> MyNotesDialog(vm) { dialog = null }
            DialogKind.ABOUT -> AboutDialog { dialog = null }
            DialogKind.STATS -> ReadingStatsDialog(vm) { dialog = null }
            DialogKind.HELP -> HelpDialog { dialog = null }
            DialogKind.SETTINGS -> SettingsDialog(
                vm,
                onBackup = {
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    backupLauncher.launch("ink-and-word-backup-$stamp.zip")
                },
                onRestore = { dialog = DialogKind.RESTORE },
                onAbout = { dialog = DialogKind.ABOUT },
                onDismiss = { dialog = null },
            )
            DialogKind.RESTORE -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Restore from a backup?") },
                text = { Text("This replaces all ink, highlights, images, notes, sketch pages and layers on this tablet with the ones in the backup file.") },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }) { Text("Choose backup file") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
            null -> {}
        }

        vm.verseSheet?.let { t -> VerseDialog(vm, t) { vm.verseSheet = null } }
        vm.wordStudy?.let { w -> WordStudyDialog(vm, w) { vm.wordStudy = null } }
        vm.familyTree?.let { u -> FamilyTreeDialog(vm, u) { vm.familyTree = null } }
        vm.introBook?.let { b ->
            BookIntroDialog(vm, b) { navigated ->
                vm.introBook = null
                if (navigated) dialog = null
            }
        }
    }
}

@Composable
private fun Handle(vertical: Boolean) {
    Box(
        Modifier
            .then(if (vertical) Modifier.size(width = 5.dp, height = 48.dp) else Modifier.size(width = 48.dp, height = 5.dp))
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.outline)
    )
}

/** The panel not shown on a phone (PH-6): a slim bar naming it; tap to show it instead. */
@Composable
private fun CollapsedPanel(vm: StudyViewModel, slot: Slot, onShow: () -> Unit) {
    val label = when (slot) {
        is Slot.Bible -> vm.panels.getOrNull(slot.index)?.let { p ->
            vm.sketchOf(p.book)?.name ?: "${vm.bible.book(p.book).name} ${p.chapter} (${p.version})"
        } ?: "Bible"
        is Slot.Study -> slot.kind.label
    }
    Surface(
        tonalElevation = 4.dp, shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onShow).testTag("collapsedPanel"),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text("Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
