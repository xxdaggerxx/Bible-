package com.biblestudy.app.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private enum class DialogKind { PICKER, SEARCH, LAYERS, BOOKMARKS, ABOUT, RESTORE, SETTINGS }

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
        val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) vm.exportRequest = ExportRequest(uri, pdf = true)
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
                    onBookmarks = { dialog = DialogKind.BOOKMARKS },
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
                        backupLauncher.launch("bible-study-backup-$stamp.zip")
                    },
                    onExport = { pdf ->
                        val name = vm.exportName() + if (pdf) ".pdf" else ".png"
                        runCatching { (if (pdf) exportPdf else exportPng).launch(name) }.onFailure { vm.message = "No file app is available." }
                    },
                    onSettings = { dialog = DialogKind.SETTINGS },
                    onAbout = { dialog = DialogKind.ABOUT },
                )
            },
        ) { padding ->
            BoxWithConstraints(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(vm.theme.surround)
            ) {
                val landscape = maxWidth >= maxHeight
                SideEffect { vm.landscape = landscape }
                val openPicker = { dialog = DialogKind.PICKER }
                // Three Bible panels fit on a large screen in landscape (ADP-3), otherwise two.
                val widthClass = WidthClass.of(maxWidth.value)
                val maxPanels = if (landscape && maxWidth >= 1200.dp) 3 else 2
                SideEffect { vm.maxPanels = maxPanels; vm.widthClass = widthClass }
                LaunchedEffect(maxPanels) { while (vm.panels.size > maxPanels) vm.closePanel(vm.panels.lastIndex) }
                val sideBySide = landscape
                val density = LocalDensity.current
                val pane = vm.sidePane
                val totalPx = with(density) { (if (sideBySide) maxWidth else maxHeight).toPx() }

                @Composable
                fun Divider(onDrag: (Float) -> Unit) {
                    val state = rememberDraggableState(onDrag)
                    Box(
                        (if (sideBySide) Modifier.width(14.dp).fillMaxHeight() else Modifier.height(14.dp).fillMaxWidth())
                            .draggable(state, if (sideBySide) Orientation.Horizontal else Orientation.Vertical),
                        contentAlignment = Alignment.Center,
                    ) { Handle(vertical = sideBySide) }
                }

                @Composable
                fun Panels(modifier: Modifier) {
                    val n = vm.panels.size.coerceAtMost(maxPanels)
                    val weights = vm.weights()
                    val areaPx = totalPx * (if (pane != null) 1f - vm.paneFraction else 1f)
                    @Composable
                    fun Items(cell: @Composable (Int, Float) -> Unit) {
                        for (i in 0 until n) {
                            if (i > 0) Divider { d -> vm.dragDivider(i - 1, d / areaPx) }
                            key(vm.panels[i]) { cell(i, weights.getOrElse(i) { 1f }) }
                        }
                    }
                    if (sideBySide) {
                        Row(modifier) { Items { i, w -> ReaderPanel(vm, i, openPicker, Modifier.weight(w).fillMaxHeight()) } }
                    } else {
                        Column(modifier) { Items { i, w -> ReaderPanel(vm, i, openPicker, Modifier.weight(w).fillMaxWidth()) } }
                    }
                }

                if (pane == null) {
                    Panels(Modifier.fillMaxSize())
                } else {
                    // The study pane beside the Bible panels (SPLIT-2): on the right, or below in portrait.
                    val paneDrag: (Float) -> Unit = { d -> vm.paneFraction = (vm.paneFraction - d / totalPx).coerceIn(0.2f, 0.6f) }
                    if (sideBySide) {
                        Row(Modifier.fillMaxSize()) {
                            Panels(Modifier.weight(1f - vm.paneFraction).fillMaxHeight())
                            Divider(paneDrag)
                            StudyPane(vm, pane, Modifier.weight(vm.paneFraction).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Panels(Modifier.weight(1f - vm.paneFraction).fillMaxWidth())
                            Divider(paneDrag)
                            StudyPane(vm, pane, Modifier.weight(vm.paneFraction).fillMaxWidth())
                        }
                    }
                }
            }
        }

        when (dialog) {
            DialogKind.PICKER -> BookPickerDialog(vm) { dialog = null }
            DialogKind.SEARCH -> SearchDialog(vm) { dialog = null }
            DialogKind.LAYERS -> LayersDialog(vm) { dialog = null }
            DialogKind.BOOKMARKS -> BookmarksDialog(vm) { dialog = null }
            DialogKind.ABOUT -> AboutDialog { dialog = null }
            DialogKind.SETTINGS -> SettingsDialog(
                vm,
                onBackup = {
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    backupLauncher.launch("bible-study-backup-$stamp.zip")
                },
                onRestore = { dialog = DialogKind.RESTORE },
                onAbout = { dialog = DialogKind.ABOUT },
                onDismiss = { dialog = null },
            )
            DialogKind.RESTORE -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Restore from a backup?") },
                text = { Text("This replaces all ink, highlights, images, notes, layers and bookmarks on this tablet with the ones in the backup file.") },
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
