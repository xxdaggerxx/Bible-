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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private enum class DialogKind { PICKER, SEARCH, LAYERS, BOOKMARKS, ABOUT, RESTORE }

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
        val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) vm.backup(uri)
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
                    onInsertImage = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onBackup = {
                        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                        backupLauncher.launch("bible-study-backup-$stamp.zip")
                    },
                    onRestore = { dialog = DialogKind.RESTORE },
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
                if (vm.panels.size == 1) {
                    key(vm.panels[0]) {
                        ReaderPanel(vm, 0, openPicker, Modifier.fillMaxSize())
                    }
                } else {
                    // Side by side in landscape, stacked in portrait.
                    val sideBySide = maxWidth >= maxHeight
                    val totalPx = with(LocalDensity.current) { (if (sideBySide) maxWidth else maxHeight).toPx() }
                    val drag = rememberDraggableState { delta ->
                        vm.splitFraction = (vm.splitFraction + delta / totalPx).coerceIn(0.2f, 0.8f)
                    }
                    val f = vm.splitFraction
                    if (sideBySide) {
                        Row(Modifier.fillMaxSize()) {
                            key(vm.panels[0]) { ReaderPanel(vm, 0, openPicker, Modifier.weight(f).fillMaxHeight()) }
                            Box(
                                Modifier.width(14.dp).fillMaxHeight()
                                    .draggable(drag, Orientation.Horizontal),
                                contentAlignment = Alignment.Center,
                            ) { Handle(vertical = true) }
                            key(vm.panels[1]) { ReaderPanel(vm, 1, openPicker, Modifier.weight(1f - f).fillMaxHeight()) }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            key(vm.panels[0]) { ReaderPanel(vm, 0, openPicker, Modifier.weight(f).fillMaxWidth()) }
                            Box(
                                Modifier.height(14.dp).fillMaxWidth()
                                    .draggable(drag, Orientation.Vertical),
                                contentAlignment = Alignment.Center,
                            ) { Handle(vertical = false) }
                            key(vm.panels[1]) { ReaderPanel(vm, 1, openPicker, Modifier.weight(1f - f).fillMaxWidth()) }
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
