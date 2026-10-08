package com.biblestudy.app.ui

import androidx.compose.material3.Slider
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Check
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
    var addingOnline by remember { mutableStateOf(false) }
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
            // Find a setting by name (a few words of it are enough).
            var query by remember { mutableStateOf("") }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Find a setting, e.g. red, margin, backup") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("settingsSearch"),
            )
            CompositionLocalProvider(LocalSettingsQuery provides query.trim()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Group("Reading")
                Choices("Page", PageTheme.entries, vm.theme, { it.label }) { vm.theme = it }
                Choices("Font", TextFont.entries, vm.textFont, { it.label }) { vm.changeTextFont(it) }
                Choices("Text size", com.biblestudy.app.model.TextSize.entries, vm.textSize, { it.label }) { vm.changeTextSize(it) }
                Choices("Line spacing", LineSpacing.entries, vm.lineSpacing, { it.label }) { vm.lineSpacing = it }
                Choices("Layout", listOf(false, true), vm.paragraphMode, { if (it) "Paragraphs" else "Verse per line" }) { vm.changeParagraphs(it) }
                Toggle("Verse numbers", null, vm.verseNumbers) { vm.changeVerseNumbers(it) }
                Toggle("Mark word differences", "When two versions are side by side", vm.markDifferences) { vm.markDifferences = it }
                Toggle("Words of Jesus in red", "In every version, imported ones too", vm.redLetters) { vm.redLetters = it }
                Toggle("Section headings", "Headings and parallel-passage links from the BSB", vm.showHeadings) { vm.showHeadings = it }
                Toggle("Chapter at a glance", "A short card under the header: what's happening, who and where, the key verse", vm.showGlance) { vm.showGlance = it }
                Choices(
                    "New panels open in", listOf<String?>(null) + BibleRepository.ALL.map { it.code }, vm.newPanelVersion,
                    { it ?: "Same version" },
                ) { vm.newPanelVersion = it }
                Toggle("Count reading time", "For Reading stats: kept only on this tablet", vm.trackReading) { vm.trackReading = it }
                Matches("Clear reading statistics", "reading stats") {
                    TextButton(onClick = { confirmClearStats = true }) { Text("Clear reading statistics\u2026") }
                }

                Group("Bible aids")
                Toggle("Hard words explained", "A dotted line under hard and old words; tap one for its meaning", vm.hardWords) { vm.hardWords = it }
                // Bible aids (AID-10): marked the same way; each can be switched off.
                Toggle("People & places", "Who someone was, or where a place is, from a dotted line under the name", vm.aidNames) { vm.aidNames = it }
                Toggle("Customs & feasts", "Jewish customs, feasts and holy days explained", vm.aidCustoms) { vm.aidCustoms = it }
                Toggle("Symbols & numbers", "What a symbol or number stands for, where the Bible uses it so", vm.aidSymbols) { vm.aidSymbols = it }

                Group("Pen & ink")
                Toggle("Draw with finger", "On: one finger draws, two fingers scroll. Off: only the pen draws", vm.fingerDraw) { vm.fingerDraw = it }
                Choices("S Pen button", SideButton.entries, vm.sideButton, { it.label }) { vm.sideButton = it }
                Choices("Eraser", listOf(false, true), vm.partialEraser, { if (it) "Partial" else "Whole strokes" }) { vm.partialEraser = it }
                Toggle("Snap highlighter to words", "Highlights follow the lines of text", vm.snapHighlights) { vm.snapHighlights = it }
                Toggle("Writing sounds", "A soft pen-on-paper sound as you write", vm.writingSounds) { vm.changeWritingSounds(it) }
                if (vm.writingSounds) Matches("Sound volume", "writing sounds") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sound volume", modifier = Modifier.width(170.dp))
                        Slider(value = vm.soundVolume, onValueChange = { vm.changeSoundVolume(it) }, modifier = Modifier.weight(1f).testTag("soundVolume"))
                    }
                }
                Toggle("Fast ink", "Pen strokes go straight to the screen for the lowest delay", vm.fastInk) { vm.fastInk = it }

                Group("Highlights")
                Toggle(
                    "Highlights in every version", "Shown over the whole verses in other translations, a shade lighter",
                    vm.highlightsAllVersions,
                ) { vm.highlightsAllVersions = it }
                // What each colour means (HL-5), in its own small window so the keyboard only opens there.
                Matches("Colour meanings", "highlight colours") {
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
                }

                Group("Margins & panels")
                Toggle("Left margin", null, vm.marginLeft) { vm.marginLeft = it }
                Toggle("Right margin", null, vm.marginRight) { vm.marginRight = it }
                Toggle("Expand to fit", "Opens space below a verse when its margin notes are taller than it", vm.expandToFit) { vm.expandToFit = it }
                Toggle("Margins in every panel", "Off: only the first Bible panel has margins", vm.marginsAllPanels) { vm.marginsAllPanels = it }
                Toggle("Link panels", "Panels scroll together, verse by verse", vm.linkPanels) { vm.linkPanels = it }

                Group("Verse details")
                Toggle("Verse details in a panel", "Tapping a verse shows it beside the text. Off: in a pop-up (simplest)", vm.verseInPanel) { vm.verseInPanel = it }
                Toggle("Compare versions", "Show the verse in every version when it opens", vm.compareVersions) { vm.compareVersions = it }

                Group("AI chat (online)")
                Toggle("AI chat", "The chat bubble and Ask AI buttons. Off: the app never goes online", vm.chat.enabled) { vm.chat.changeEnabled(it) }
                if (vm.chat.enabled) Matches("AI chat", "API key", "Claude", "sites", "websites", "search") {
                    AiChatSettings(vm)
                }

                Group("Bibles")
                // The version manager (BIB-5) and importing (BIB-4).
                Matches("Bibles", "versions", "import a Bible", "translations", *BibleRepository.ALL.map { it.code + " " + it.name }.toTypedArray()) {
                for (v in BibleRepository.ALL) {
                    val size = remember(v.code, vm.onlineArrivals) { BibleRepository.fileOf(context, v).length() }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${v.code} \u2014 ${v.name}")
                            if (v.online > 0) {
                                // An online Bible (BIB-12): how much is on the tablet, and saving it all.
                                val saved = remember(v.code, vm.onlineArrivals) { vm.onlineSaved(v.code) }
                                val progress = vm.onlineDownloads[v.code]
                                val source = when (v.online) { com.biblestudy.app.data.Esv.ID -> "Crossway"; com.biblestudy.app.data.Nlt.ID -> "Tyndale"; else -> "YouVersion" }
                                Text(
                                    when {
                                        progress != null -> "Saving for offline\u2026 ${(progress * 100).toInt()}%"
                                        saved != null && saved.first >= saved.second -> "Online, from $source \u00b7 saved on this tablet, works offline"
                                        saved != null -> "Online, from $source \u00b7 ${saved.first} of ${saved.second} chapters on this tablet"
                                        else -> "Online, from $source"
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                )
                                if (progress != null) androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                            }
                            Text(
                                (if (size > 0) "%.1f MB \u00b7 ".format(size / 1e6) else "") + v.copyright,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                                maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        if (v.online > 0) {
                            if (v.code in vm.onlineDownloads) TextButton(onClick = { vm.stopSavingForOffline(v.code) }) { Text("Stop") }
                            else if (vm.onlineSaved(v.code)?.let { it.first < it.second } == true) TextButton(onClick = { vm.saveForOffline(v.code) }) { Text("Save for offline") }
                        }
                        if (v.imported) TextButton(onClick = { removing = v.code }) { Text("Remove") }
                        else Text("Built in", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                // Online Bibles (BIB-12): versions that can't be built in, read from YouVersion.
                OutlinedButton(onClick = { addingOnline = true; vm.loadOnlineBibles() }, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text("Add an online Bible\u2026")
                }
                Text(
                    "NIV, ESV, NLT, NASB, Amplified and more, read online from YouVersion (the ESV from Crossway, the NLT from Tyndale). Each chapter is kept on this tablet once read, so it opens instantly next time, also offline. Every feature works with them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
                // How long downloaded chapters stay, and clearing them (1.19).
                if (BibleRepository.ALL.any { it.online > 0 }) {
                    Choices("Keep downloaded chapters", CACHE_DAYS, vm.cacheDays, { if (it == 0) "Always" else "$it days" }) { vm.changeCacheDays(it) }
                    Text(
                        "Chapters you haven't read for this long are removed and download again when you read them. Bibles saved for offline always stay.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                    var clearing by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { clearing = true }, modifier = Modifier.padding(vertical = 4.dp)) { Text("Clear downloaded text\u2026") }
                    if (clearing) AlertDialog(
                        onDismissRequest = { clearing = false },
                        title = { Text("Clear downloaded text?") },
                        text = { Text("The text of every online Bible is removed from this tablet, including Bibles saved for offline. Your notes, highlights and ink stay. Chapters download again when you read them, so you'll need the internet.") },
                        confirmButton = { TextButton(onClick = { clearing = false; vm.clearOnlineCache() }) { Text("Clear") } },
                        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } },
                    )
                }
                if (com.biblestudy.app.BuildConfig.NLT_KEY.isEmpty() || vm.nltKey.isNotEmpty()) {
                    var nlt by remember { mutableStateOf(vm.nltKey) }
                    OutlinedTextField(
                        value = nlt, onValueChange = { nlt = it; vm.changeNltKey(it) }, singleLine = true,
                        label = { Text("NLT API key (Tyndale)") },
                        supportingText = { Text(if (com.biblestudy.app.BuildConfig.NLT_KEY.isEmpty()) "For the NLT: from api.nlt.to" else "Leave empty to use the one built in") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                if (com.biblestudy.app.BuildConfig.ESV_KEY.isEmpty() || vm.esvKey.isNotEmpty()) {
                    var esv by remember { mutableStateOf(vm.esvKey) }
                    OutlinedTextField(
                        value = esv, onValueChange = { esv = it; vm.changeEsvKey(it) }, singleLine = true,
                        label = { Text("ESV API key (Crossway)") },
                        supportingText = { Text(if (com.biblestudy.app.BuildConfig.ESV_KEY.isEmpty()) "For the ESV: from api.esv.org" else "Leave empty to use the one built in") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                if (com.biblestudy.app.BuildConfig.YOUVERSION_KEY.isEmpty() || vm.youVersionKey.isNotEmpty()) {
                    var key by remember { mutableStateOf(vm.youVersionKey) }
                    OutlinedTextField(
                        value = key, onValueChange = { key = it; vm.changeYouVersionKey(it) }, singleLine = true,
                        label = { Text("YouVersion app key") },
                        supportingText = { Text(if (com.biblestudy.app.BuildConfig.YOUVERSION_KEY.isEmpty()) "Needed for online Bibles: from platform.youversion.com" else "Leave empty to use the one built in") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                OutlinedButton(onClick = { runCatching { pickBible.launch(arrayOf("*/*")) } }, enabled = !vm.importing) {
                    Text(if (vm.importing) vm.importStatus ?: "Importing\u2026" else "Import a Bible\u2026")
                }
                Text(
                    "USFM files (or a .zip of them), OSIS XML, or this app's own database. Word studies, words of Jesus in red and paragraphs work in it too. Only import versions you have the right to use.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
                }

                Group("Backup")
                Choices("Automatic backup", AutoBackup.entries, vm.autoBackup, { it.label }) {
                    vm.autoBackup = it
                    vm.savePrefs()
                }
                if (vm.autoBackup != AutoBackup.OFF) Matches("Backup folder", "automatic backup") {
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
                Matches("Back up my notes", "Restore from backup") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    OutlinedButton(onClick = onBackup) { Text("Back up my notes…") }
                    OutlinedButton(onClick = onRestore) { Text("Restore from backup…") }
                }
                }

                Group("About")
                Matches("About", "version", "credits", "licences") {
                Text("Ink & Word, version ${BuildConfig.VERSION_NAME}. Works offline; only online Bibles and the AI chat use the internet.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    TextButton(onClick = { versions = true }) { Text("About these versions") }
                    TextButton(onClick = onAbout) { Text("Credits") }
                }
                }

                Matches("Reset settings to defaults") {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                TextButton(onClick = { confirmReset = true }) { Text("Reset settings to defaults") }
                }
                Spacer(Modifier.padding(8.dp))
            }
            }
        }
    }
    if (versions) VersionsDialog { versions = false }
    if (addingOnline) OnlineBiblesDialog(vm) { addingOnline = false }
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
            text = { Text("All settings go back to their defaults. Your notes, ink, highlights, sketch pages and layers are not changed.") },
            confirmButton = { TextButton(onClick = { vm.resetSettings(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

/** What's typed in the Settings search box; rows that don't match it are hidden. */
private val LocalSettingsQuery = compositionLocalOf { "" }

/** Shows [content] when nothing is being searched for, or one of [words] contains the search. */
@Composable
private fun Matches(vararg words: String, content: @Composable () -> Unit) {
    val q = LocalSettingsQuery.current
    if (q.isEmpty() || words.any { it.contains(q, ignoreCase = true) } ||
        q.split(' ').filter { it.isNotBlank() }.all { part -> words.any { it.contains(part, ignoreCase = true) } }) content()
}

@Composable
private fun Group(title: String) {
    // Group headings step aside while searching, so the matches sit together.
    if (LocalSettingsQuery.current.isNotEmpty()) return
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Toggle(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) = Matches(title, detail ?: "") {
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

/** Choices for how long online Bibles' chapters are kept, in days (0 = always). */
private val CACHE_DAYS = listOf(7, 30, 90, 365, 0)

@Composable
private fun <T> Choices(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) =
    Matches(title, *options.map(label).toTypedArray()) {
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

/** The AI chat's key and the sites it may search (AI-4, AI-5). */
@Composable
private fun AiChatSettings(vm: StudyViewModel) {
    val chat = vm.chat
    var key by remember { mutableStateOf("") }
    var sites by remember(chat.sites.toList()) { mutableStateOf(chat.sites.joinToString("\n")) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (chat.apiKey.isBlank()) "No Claude API key yet." else "Claude API key saved (ends \u2026${chat.apiKey.takeLast(4)}).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true,
                label = { Text(if (chat.apiKey.isBlank()) "API key" else "New API key") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { chat.changeKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
            if (chat.apiKey.isNotBlank()) TextButton(onClick = { chat.changeKey("") }) { Text("Forget key") }
        }
        Row(Modifier.fillMaxWidth().clickable { chat.changeOnlySites(!chat.onlySites) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Only search my sites")
                Text("Off: the whole web, with your sites searched first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            androidx.compose.material3.Switch(checked = chat.onlySites, onCheckedChange = { chat.changeOnlySites(it) })
        }
        Text("Your sites, one per line. ${if (chat.onlySites) "The AI answers only from these." else "The AI searches these first."}", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = sites, onValueChange = { sites = it },
            minLines = 4, maxLines = 10,
            modifier = Modifier.fillMaxWidth().testTag("chatSites"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { chat.changeSites(sites.lines()) }) { Text("Save sites") }
            TextButton(onClick = { chat.changeSites(com.biblestudy.app.data.AiChat.DEFAULT_SITES) }) { Text("Use the suggested sites") }
        }
    }
}

/**
 * The Bibles YouVersion offers (BIB-12), to add as online Bibles. Ones already added are ticked.
 */
@Composable
private fun OnlineBiblesDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    val list = vm.onlineBibles
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an online Bible") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Read online from YouVersion. Chapters download as you read them and stay on this tablet. Show its copyright with any text you share.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
                when {
                    vm.onlineListError != null -> {
                        Text(vm.onlineListError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = { vm.loadOnlineBibles() }) { Text("Try again") }
                    }
                    list == null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Looking up the Bibles\u2026", modifier = Modifier.padding(start = 12.dp))
                    }
                    else -> for (info in list) {
                        val added = BibleRepository.ALL.any { it.online == info.id }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("online-${info.code}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(info.title)
                                Text(
                                    info.code + if (info.books.size < 66) " \u00b7 ${info.books.size} books" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            if (added) Icon(androidx.compose.material.icons.Icons.Filled.Check, contentDescription = "Added")
                            else TextButton(onClick = { vm.addOnlineBible(info) }) { Text("Add") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

