package com.biblestudy.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.biblestudy.app.BuildConfig
import com.biblestudy.app.data.BibleRepository
import com.biblestudy.app.model.SideButton
import com.biblestudy.app.model.TextFont

/**
 * Every option in one place (SET-1, SET-2), grouped, applied as soon as it's changed. The toolbar
 * keeps only what's used while writing (SET-5).
 */
@Composable
fun SettingsDialog(
    vm: StudyViewModel,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onAbout: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf(false) }
    var meaningsOpen by remember { mutableStateOf(false) }
    var confirmClearStats by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    val pickBible = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> picked = uris }
    val context = LocalContext.current
    // A folder for automatic backups, e.g. one synced to the cloud (DATA-6).
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.backupFolder = uri.toString()
            vm.savePrefs()
        }
    }
    BigDialog({ vm.savePrefs(); onDismiss() }) {
        Column {
            DialogTitle("Settings", { vm.savePrefs(); onDismiss() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Group("Reading")
                Choices("Page", PageTheme.entries, vm.theme, { it.label }) { vm.theme = it }
                Choices("Font", TextFont.entries, vm.textFont, { it.label }) { vm.changeTextFont(it) }
                Choices("Line spacing", LineSpacing.entries, vm.lineSpacing, { it.label }) { vm.lineSpacing = it }
                Choices("Layout", listOf(false, true), vm.paragraphMode, { if (it) "Paragraphs" else "Verse per line" }) { vm.changeParagraphs(it) }
                Toggle("Verse numbers", null, vm.verseNumbers) { vm.changeVerseNumbers(it) }
                Toggle("Section headings", "Headings and parallel-passage links from the BSB", vm.showHeadings) { vm.showHeadings = it }
                Choices(
                    "New panels open in", listOf<String?>(null) + BibleRepository.ALL.map { it.code }, vm.newPanelVersion,
                    { it ?: "Same version" },
                ) { vm.newPanelVersion = it }
                Toggle("Count reading time", "For Reading stats: kept only on this tablet", vm.trackReading) { vm.trackReading = it }
                TextButton(onClick = { confirmClearStats = true }) { Text("Clear reading statistics\u2026") }

                Group("Pen & ink")
                Toggle("Draw with finger", "Off: fingers scroll and tap; only the pen draws", vm.fingerDraw) { vm.fingerDraw = it }
                Choices("S Pen button", SideButton.entries, vm.sideButton, { it.label }) { vm.sideButton = it }
                Choices("Eraser", listOf(false, true), vm.partialEraser, { if (it) "Partial" else "Whole strokes" }) { vm.partialEraser = it }
                Toggle("Snap highlighter to words", "Highlights follow the lines of text", vm.snapHighlights) { vm.snapHighlights = it }
                Toggle("Fast ink", "Pen strokes go straight to the screen for the lowest delay", vm.fastInk) { vm.fastInk = it }

                Group("Highlights")
                Toggle(
                    "Highlights in every version", "Shown over the whole verses in other translations, a shade lighter",
                    vm.highlightsAllVersions,
                ) { vm.highlightsAllVersions = it }
                // What each colour means (HL-5), in its own small window so the keyboard only opens there.
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Colour meanings")
                        Text(
                            vm.meanings.entries.sortedBy { HIGHLIGHT_COLORS.indexOf(it.key) }.joinToString { it.value }
                                .ifEmpty { "e.g. yellow = promises, green = commands" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    OutlinedButton(onClick = { meaningsOpen = true }) { Text("Edit\u2026") }
                }

                Group("Margins & panels")
                Toggle("Left margin", null, vm.marginLeft) { vm.marginLeft = it }
                Toggle("Right margin", null, vm.marginRight) { vm.marginRight = it }
                Toggle("Expand to fit", "Opens space below a verse when its margin notes are taller than it", vm.expandToFit) { vm.expandToFit = it }
                Toggle("Margins in every panel", "Off: only the first Bible panel has margins", vm.marginsAllPanels) { vm.marginsAllPanels = it }
                Toggle("Link panels", "Panels scroll together, verse by verse", vm.linkPanels) { vm.linkPanels = it }

                Group("Verse window")
                Toggle("Compare versions", "Show the verse in every version when it opens", vm.compareVersions) { vm.compareVersions = it }

                Group("Bibles")
                // The version manager (BIB-5) and importing (BIB-4).
                for (v in BibleRepository.ALL) {
                    val size = remember(v.code) { BibleRepository.fileOf(context, v).length() }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${v.code} \u2014 ${v.name}")
                            Text(
                                (if (size > 0) "%.1f MB \u00b7 ".format(size / 1e6) else "") + v.copyright,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (v.imported) TextButton(onClick = { removing = v.code }) { Text("Remove") }
                        else Text("Built in", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                OutlinedButton(onClick = { runCatching { pickBible.launch(arrayOf("*/*")) } }, enabled = !vm.importing) {
                    Text(if (vm.importing) "Importing\u2026" else "Import a Bible\u2026")
                }
                Text(
                    "USFM files (or a .zip of them), OSIS XML, or this app's own database. Only import versions you have the right to use.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )

                Group("Backup")
                Choices("Automatic backup", AutoBackup.entries, vm.autoBackup, { it.label }) {
                    vm.autoBackup = it
                    vm.savePrefs()
                }
                if (vm.autoBackup != AutoBackup.OFF) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Backup folder")
                            Text(
                                vm.backupFolderName() + (
                                    if (vm.lastAutoBackup > 0) " \u00b7 last " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(vm.lastAutoBackup))
                                    else ""
                                    ) + " \u00b7 keeps the newest 5",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        OutlinedButton(onClick = { runCatching { pickFolder.launch(null) } }) { Text("Choose\u2026") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    OutlinedButton(onClick = onBackup) { Text("Back up my notes…") }
                    OutlinedButton(onClick = onRestore) { Text("Restore from backup…") }
                }

                Group("About")
                Text("Bible Study, version ${BuildConfig.VERSION_NAME}. Works completely offline.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    TextButton(onClick = { versions = true }) { Text("About these versions") }
                    TextButton(onClick = onAbout) { Text("Credits") }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                TextButton(onClick = { confirmReset = true }) { Text("Reset settings to defaults") }
                Spacer(Modifier.padding(8.dp))
            }
        }
    }
    if (versions) VersionsDialog { versions = false }
    if (meaningsOpen) {
        AlertDialog(
            onDismissRequest = { meaningsOpen = false },
            title = { Text("Colour meanings") },
            text = {
                Column {
                    Text("Shown in the highlighter's menu and the highlights list.", style = MaterialTheme.typography.bodySmall)
                    for ((i, c) in HIGHLIGHT_COLORS.withIndex()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.padding(end = 12.dp).size(26.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(c))
                            )
                            var label by remember(c) { mutableStateOf(vm.meanings[c].orEmpty()) }
                            OutlinedTextField(
                                value = label,
                                onValueChange = { label = it; vm.setMeaning(c, it) },
                                singleLine = true,
                                placeholder = { Text(listOf("Promises", "Commands", "Prayer", "Sin", "Holy Spirit", "Prophecy")[i % 6]) },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { meaningsOpen = false }) { Text("Done") } },
        )
    }
    removing?.let { code ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $code?") },
            text = { Text("The version is removed from this tablet. Ink drawn on its words stays saved and comes back if you import it again with the same code.") },
            confirmButton = { TextButton(onClick = { vm.removeBible(code); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    if (picked.isNotEmpty()) {
        val names = remember(picked) { picked.map { u -> displayName(context, u) } }
        var code by remember(picked) { mutableStateOf(names.first().substringBefore('.').take(6).uppercase().filter { it.isLetterOrDigit() }) }
        var name by remember(picked) { mutableStateOf("") }
        var copyright by remember(picked) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picked = emptyList() },
            title = { Text("Import a Bible") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(names.joinToString(), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = code, onValueChange = { code = it.take(8) }, singleLine = true, label = { Text("Short code, e.g. NIV") })
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name, e.g. New International Version") })
                    OutlinedTextField(value = copyright, onValueChange = { copyright = it }, label = { Text("Copyright line (shown in About and on exports)") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uris = picked
                    vm.importBible(names, { i -> context.contentResolver.openInputStream(uris[i])!! }, code, name, copyright)
                    picked = emptyList()
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { picked = emptyList() }) { Text("Cancel") } },
        )
    }
    if (confirmClearStats) {
        AlertDialog(
            onDismissRequest = { confirmClearStats = false },
            title = { Text("Clear reading statistics?") },
            text = { Text("Reading time, chapters read and days read are all reset to nothing. Your notes are not changed.") },
            confirmButton = { TextButton(onClick = { vm.clearReadingStats(); confirmClearStats = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClearStats = false }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset settings?") },
            text = { Text("All settings go back to their defaults. Your notes, ink, highlights, bookmarks and layers are not changed.") },
            confirmButton = { TextButton(onClick = { vm.resetSettings(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Toggle(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Choices(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.width(170.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (o in options) FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) })
        }
    }
}

/** A picked file's name, e.g. "niv.zip". */
private fun displayName(context: android.content.Context, uri: android.net.Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
