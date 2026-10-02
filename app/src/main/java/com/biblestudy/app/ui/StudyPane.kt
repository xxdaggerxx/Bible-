package com.biblestudy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.biblestudy.app.data.Passage
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biblestudy.app.model.CrossRef
import com.biblestudy.app.model.VerseId
import com.biblestudy.app.model.VerseTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The study pane beside the Bible panels (SPLIT-2): search results, cross-references for the
 * verse being read, or the notes in the chapter being read. It works with the active Bible panel.
 */
@Composable
fun StudyPane(vm: StudyViewModel, kind: PaneKind, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surface).testTag("pane")) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // One menu rather than a row of buttons: what the panel shows, its arrangement and tab (SPLIT-7).
            val slot = Slot.Study(kind)
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Choose what the pane shows" }) {
                    Text(kind.label, style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                PanelViewMenu(vm, slot, menu) { menu = false }
            }
            vm.tab.pinned?.let { p ->
                // Pinned: the view stays on this passage; tap to follow your reading again.
                TextButton(onClick = { vm.togglePin() }) {
                    Icon(Icons.Filled.PushPin, contentDescription = "Unpin", modifier = Modifier.padding(end = 4.dp))
                    Text("${vm.bible.book(p.book.coerceIn(1, 66)).name} ${p.chapter}", maxLines = 1)
                }
            }
            if (vm.tab.shown > 1 || vm.tabs.size > 1) {
                IconButton(onClick = { vm.closeSlot(slot) }) { Icon(Icons.Filled.Close, contentDescription = "Close side pane") }
            }
        }
        val inner = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
        when (kind) {
            PaneKind.SEARCH -> SearchPane(vm, inner.padding(top = 8.dp), onOpened = {}, inPane = true)
            PaneKind.CROSSREFS -> CrossRefsPane(vm, inner)
            PaneKind.NOTES -> NotesPane(vm, inner)
            PaneKind.DICTIONARY -> DictionaryPane(vm, inner)
            PaneKind.TOPICS -> TopicsPane(vm, inner)
            PaneKind.COMMENTARY -> CommentaryPane(vm, inner.padding(top = 4.dp))
            PaneKind.NAMES -> NamesPane(vm, inner)
            PaneKind.SKETCHES -> SketchesPane(vm, inner)
        }
    }
}

/** The panel the pane works with: the active Bible panel. */
private fun StudyViewModel.readerIndex() = activePanel.coerceIn(0, panels.lastIndex)

/** Cross-references for the verse last opened, or else the verse at the top of the active panel. */
@Composable
private fun CrossRefsPane(vm: StudyViewModel, modifier: Modifier) {
    val index = vm.readerIndex()
    val panel = vm.studyPanel()
    val chosen = vm.paneVerse?.takeIf { it.book == panel.book && it.chapter == panel.chapter }
    val t = chosen ?: VerseTarget(panel.book, panel.chapter, panel.topVerse)
    val id = VerseId.of(t.book, t.chapter, t.verse)
    val version = panel.version
    val refs by produceState<List<CrossRef>?>(null, id, version) {
        value = withContext(Dispatchers.IO) {
            val text = vm.text(version)
            vm.bible.crossRefs(id).map { r -> text.verseText(r.toStart)?.let { r.copy(preview = it) } ?: r }
        }
    }
    val lastVerse = remember(t.book, t.chapter) { vm.bible.chapter(t.book, t.chapter).lastOrNull()?.verse ?: 1 }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.paneVerse = t.copy(verse = t.verse - 1) }, enabled = t.verse > 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous verse")
            }
            Text(vm.refLabel(id), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.paneVerse = t.copy(verse = t.verse + 1) }, enabled = t.verse < lastVerse) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next verse")
            }
        }
        if (chosen != null) {
            TextButton(onClick = { vm.paneVerse = null }) { Text("Follow the page as I read") }
        } else {
            Text(
                "Follows the verse at the top of the page. Tap a verse to fix on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        val r = refs
        when {
            r == null -> Text("Loading…", modifier = Modifier.padding(top = 8.dp))
            r.isEmpty() -> Text("No cross-references for this verse.", modifier = Modifier.padding(top = 8.dp))
        }
        // A cross-reference opens the passage pop-over (LINK-5), with Go to and Open beside.
        var shown by remember { mutableStateOf<Passage?>(null) }
        shown?.let { p ->
            Popup(alignment = Alignment.Center, onDismissRequest = { shown = null }, properties = PopupProperties(focusable = true)) {
                PassageCard(
                    vm, p, version,
                    onGoTo = { vm.openPassage(p, index, beside = false); shown = null },
                    onOpenBeside = { vm.openPassage(p, index, beside = true); shown = null },
                    onClose = { shown = null },
                )
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(r.orEmpty()) { x ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { shown = x.passage() }
                        .padding(vertical = 8.dp)
                ) {
                    Text(vm.refLabel(x.toStart, x.toEnd), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(x.preview, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
            // Topics, parallel accounts and passages that share topics (STD-2, STD-6, STD-9).
            // Added once the list is in, so the list doesn't open scrolled to it.
            if (r != null) item(key = "related") { RelatedPassages(vm, id, version, onShow = { shown = it }) }
        }
    }
}

/** Typed notes and highlighted verses in the chapter shown in the active panel. */
@Composable
private fun NotesPane(vm: StudyViewModel, modifier: Modifier) {
    val panel = vm.studyPanel()
    val book = panel.book
    val chapter = panel.chapter
    val notes = vm.notesFor(book, chapter).values.sortedBy { it.verse }
    // Highlighted verses, whole (in the version being read).
    val marks = vm.highlightsFor(panel.version, book, chapter).map { vm.highlightVerses(it) }.distinct().sortedBy { it.first }
    Column(modifier) {
        Text("${vm.bible.book(book).name} $chapter", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
        if (notes.isEmpty() && marks.isEmpty()) {
            Text("No typed notes or highlights in this chapter. Tap a verse with your finger to add a note.")
        }
        LazyColumn(Modifier.weight(1f)) {
            items(notes, key = { "n${it.verse}" }) { n ->
                Column(
                    Modifier.fillMaxWidth().clickable { vm.openVerse(book, chapter, n.verse) }.padding(vertical = 8.dp)
                ) {
                    Text(
                        vm.refLabel(VerseId.of(book, chapter, n.verse), VerseId.of(book, chapter, n.endVerse)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(n.text)
                }
                HorizontalDivider()
            }
            if (marks.isNotEmpty()) {
                item { Text("Highlights", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
                items(marks, key = { "h${it.first}-${it.second}" }) { (from, to) ->
                    Column(Modifier.fillMaxWidth().clickable { vm.openVerse(book, chapter, from) }.padding(vertical = 8.dp)) {
                        Text(
                            vm.refLabel(VerseId.of(book, chapter, from), VerseId.of(book, chapter, to)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            (from..to).mapNotNull { vm.text(panel.version).verseText(VerseId.of(book, chapter, it)) }.joinToString(" "),
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Every sketch page (SKT-2): tap one to show it in a panel beside the Bible text, to read and write
 * side by side.
 */
@Composable
private fun SketchesPane(vm: StudyViewModel, modifier: Modifier) {
    val mine = vm.sketches.filter { !it.readyMade }.sortedByDescending { it.created }
    val ready = vm.sketches.filter { it.readyMade }.sortedBy { it.created }
    LazyColumn(modifier.testTag("sketchesPane")) {
        item {
            Text(
                "Tap a page to show it beside the text.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        item { Text("My sketch pages", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        if (mine.isEmpty()) item {
            Text("None yet. Make one from Insert \u2192 Sketch page.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(mine, key = { it.id }) { s -> SketchRow(vm, s) { vm.openSketchBeside(s) } }
        item { Text("Ready-made pages", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp)) }
        items(ready, key = { it.id }) { s -> SketchRow(vm, s) { vm.openSketchBeside(s) } }
    }
}
