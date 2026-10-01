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
            // One menu rather than a row of six buttons: what the pane shows.
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Choose what the pane shows" }) {
                    Text(kind.label, style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (k in PaneKind.entries) {
                        DropdownMenuItem(
                            text = { Text(k.label) },
                            onClick = { vm.sidePane = k; menu = false },
                            leadingIcon = if (k == kind) { { Icon(Icons.Filled.Check, contentDescription = null) } } else null,
                        )
                    }
                }
            }
            IconButton(onClick = { vm.sidePane = null }) { Icon(Icons.Filled.Close, contentDescription = "Close side pane") }
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
        }
    }
}

/** The panel the pane works with: the active Bible panel. */
private fun StudyViewModel.readerIndex() = activePanel.coerceIn(0, panels.lastIndex)

/** Cross-references for the verse last opened, or else the verse at the top of the active panel. */
@Composable
private fun CrossRefsPane(vm: StudyViewModel, modifier: Modifier) {
    val index = vm.readerIndex()
    val panel = vm.panels[index]
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

/** Typed notes and bookmarks in the chapter shown in the active panel. */
@Composable
private fun NotesPane(vm: StudyViewModel, modifier: Modifier) {
    val panel = vm.panels[vm.readerIndex()]
    val book = panel.book
    val chapter = panel.chapter
    val notes = vm.notesFor(book, chapter).values.sortedBy { it.verse }
    val marks = vm.bookmarks.filter { it.book == book && it.chapter == chapter }.sortedBy { it.verse }
    Column(modifier) {
        Text("${vm.bible.book(book).name} $chapter", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
        if (notes.isEmpty() && marks.isEmpty()) {
            Text("No typed notes or bookmarks in this chapter. Tap a verse with your finger to add one.")
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
                item { Text("Bookmarks", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
                items(marks, key = { "b${it.id}" }) { b ->
                    val id = VerseId.of(b.book, b.chapter, b.verse)
                    Column(Modifier.fillMaxWidth().clickable { vm.openVerse(book, chapter, b.verse) }.padding(vertical = 8.dp)) {
                        Text(
                            vm.refLabel(id) + if (b.folder.isNotEmpty()) "  ·  ${b.folder}" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(vm.text(panel.version).verseText(id) ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
