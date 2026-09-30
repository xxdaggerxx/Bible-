package com.biblestudy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.biblestudy.app.BuildConfig
import com.biblestudy.app.data.BibleRepository
import com.biblestudy.app.data.Passage
import com.biblestudy.app.data.RefLinks
import com.biblestudy.app.data.RefParser
import com.biblestudy.app.model.HighlightEntry
import com.biblestudy.app.model.CrossRef
import com.biblestudy.app.model.SearchHit
import com.biblestudy.app.model.SearchScope
import com.biblestudy.app.model.Verse
import com.biblestudy.app.model.VerseId
import com.biblestudy.app.model.VerseTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A large dialog sized for tablets. */
@Composable
private fun BigDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 820.dp).fillMaxHeight(0.88f),
        ) {
            Box(Modifier.padding(20.dp)) { content() }
        }
    }
}

@Composable
private fun DialogTitle(title: String, onClose: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
    }
}

// ---------------------------------------------------------------------------------------------
// Book & chapter picker
// ---------------------------------------------------------------------------------------------

@Composable
fun BookPickerDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    var book by remember { mutableStateOf<Int?>(null) }
    var chapter by remember { mutableStateOf<Int?>(null) }
    val panelIndex = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    val version = vm.activeVersion
    // Where the user has notes; ink on the words counts for the version being read.
    val markers by produceState<MarkerIndex?>(null, version, vm.dataGeneration) { value = vm.loadMarkers(version) }
    val visible = vm.visibleLayerIds()
    val colors = vm.layers.associate { it.id to it.color }

    fun marks(layerIds: List<Long>, note: Boolean, bookmark: Boolean) =
        Marks(layerIds.mapNotNull { colors[it] }, note, bookmark)

    BigDialog(onDismiss) {
        Column {
            val b = book
            val c = chapter
            when {
                b == null -> {
                    DialogTitle("Choose a book", onDismiss)
                    MarkerLegend()
                    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.weight(1f)) {
                        for ((label, range) in listOf("Old Testament" to 1..39, "New Testament" to 40..66)) {
                            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(label) }
                            items(vm.bible.books.filter { it.id in range }, key = { it.id }) { bk ->
                                val m = markers?.let { idx ->
                                    marks(idx.layers(visible, bk.id), idx.hasNote(bk.id), vm.bookmarks.any { it.book == bk.id })
                                }
                                GridCell(bk.name, m) { book = bk.id; if (bk.chapters == 1) chapter = 1 }
                            }
                        }
                    }
                }
                c == null -> {
                    val info = vm.bible.book(b)
                    DialogTitle(info.name, onDismiss) {
                        IconButton(onClick = { book = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to books")
                        }
                    }
                    MarkerLegend()
                    LazyVerticalGrid(columns = GridCells.Adaptive(72.dp), modifier = Modifier.weight(1f)) {
                        items((1..info.chapters).toList()) { ch ->
                            val m = markers?.let { idx ->
                                marks(
                                    idx.layers(visible, b, ch), idx.hasNote(b, ch),
                                    vm.bookmarks.any { it.book == b && it.chapter == ch },
                                )
                            }
                            GridCell(ch.toString(), m, label = "${info.name} $ch") { chapter = ch }
                        }
                    }
                }
                else -> {
                    val info = vm.bible.book(b)
                    val verses by produceState(emptyList<Verse>(), b, c, version) {
                        value = withContext(Dispatchers.IO) { vm.text(version).chapter(b, c) }
                    }
                    DialogTitle("${info.name} $c", onDismiss) {
                        IconButton(onClick = { chapter = null; if (info.chapters == 1) book = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chapters")
                        }
                    }
                    MarkerLegend()
                    val byVerse = markers?.verseLayers(visible, b, c, verses) ?: emptyMap()
                    LazyVerticalGrid(columns = GridCells.Adaptive(64.dp), modifier = Modifier.weight(1f)) {
                        items(verses, key = { it.verse }) { v ->
                            val m = marks(
                                byVerse[v.verse] ?: emptyList(),
                                markers?.hasNote(b, c, v.verse) == true,
                                vm.bookmarks.any { it.book == b && it.chapter == c && it.verse == v.verse },
                            )
                            GridCell(v.verse.toString(), m, label = "${info.name} $c:${v.verse}") {
                                vm.goTo(panelIndex, b, c, v.verse)
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What a picker cell has: colours of layers with ink, highlights or images; a typed note; a bookmark. */
private class Marks(val layerColors: List<Int>, val note: Boolean, val bookmark: Boolean) {
    val any get() = layerColors.isNotEmpty() || note || bookmark
}

@Composable
private fun MarkerLegend() {
    Row(
        Modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Text("Ink, highlights or images (one dot per visible layer)", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.EditNote, contentDescription = null, modifier = Modifier.size(14.dp))
        Text("Typed note", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Bookmark, contentDescription = null, tint = BOOKMARK_RED, modifier = Modifier.size(14.dp))
        Text("Bookmark", style = MaterialTheme.typography.bodySmall)
    }
}

private val BOOKMARK_RED = Color(0xFFC62828)

@Composable
private fun MarkerRow(m: Marks?, label: String) {
    // Always the same height, so cells line up whether or not they have markers.
    Row(
        Modifier
            .height(12.dp)
            .then(if (m?.any == true) Modifier.semantics { contentDescription = "$label has notes" } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (m == null) return@Row
        for (c in m.layerColors.take(4)) Box(Modifier.size(7.dp).clip(CircleShape).background(Color(c)))
        if (m.layerColors.size > 4) Text("+", style = MaterialTheme.typography.labelSmall)
        if (m.note) Icon(Icons.Filled.EditNote, contentDescription = null, modifier = Modifier.size(12.dp))
        if (m.bookmark) Icon(Icons.Filled.Bookmark, contentDescription = null, tint = BOOKMARK_RED, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun GridCell(text: String, marks: Marks? = null, label: String = text, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        MarkerRow(marks, label)
    }
}

// ---------------------------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------------------------

@Composable
fun SearchDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(vm.lastSearch) }
    var scope by remember { mutableStateOf(SearchScope.ALL) }
    var version by remember { mutableStateOf(vm.activeVersion) }
    var inNotes by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchHit>?>(null) }
    var searchedTerms by remember { mutableStateOf(emptyList<String>()) }
    val co = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val panelIndex = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    val currentBook = vm.panels[panelIndex].book
    val ref = remember(query) { RefParser.parse(query, vm.bible.books) }

    fun run() {
        val q = query
        vm.lastSearch = q
        co.launch {
            val v = version
            val notes = inNotes
            results = withContext(Dispatchers.IO) {
                if (notes) {
                    val (lo, hi) = when (scope) {
                        SearchScope.ALL -> 1 to 66
                        SearchScope.OT -> 1 to 39
                        SearchScope.NT -> 40 to 66
                        SearchScope.BOOK -> currentBook to currentBook
                    }
                    vm.user.searchNotes(q, lo, hi)
                } else {
                    vm.text(v).search(q, scope, currentBook)
                }
            }
            searchedTerms = BibleRepository.terms(q)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(scope, version, inNotes) { if (query.isNotBlank() && results != null) run() }

    BigDialog(onDismiss) {
        Column {
            DialogTitle("Search", onDismiss)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Words, \"exact phrase\", or a reference like John 3:16") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run() }),
                trailingIcon = { IconButton(onClick = { run() }) { Icon(Icons.Filled.Search, contentDescription = "Search") } },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in BibleRepository.ALL) {
                    FilterChip(
                        selected = !inNotes && version == v.code,
                        onClick = { version = v.code; inNotes = false },
                        label = { Text(v.code) },
                    )
                }
                FilterChip(selected = inNotes, onClick = { inNotes = true }, label = { Text("My notes") })
                VerticalDivider(Modifier.height(32.dp))
                for (s in SearchScope.entries) {
                    val label = if (s == SearchScope.BOOK) vm.bible.book(currentBook).name else s.label
                    FilterChip(selected = scope == s, onClick = { scope = s }, label = { Text(label) })
                }
            }
            Text(
                "Tip: use OR between words for either word, and * for word beginnings (lov* finds love, loved, loveth).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            if (ref != null) {
                val label = vm.bible.book(ref.book).name + " ${ref.chapter}" + (ref.verse?.let { ":$it" } ?: "")
                Button(
                    onClick = { vm.goTo(panelIndex, ref.book, ref.chapter, ref.verse); onDismiss() },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Go to $label") }
            }
            val r = results
            if (r != null) {
                Text(
                    when {
                        r.isEmpty() -> if (inNotes) "No notes found." else "No verses found."
                        inNotes -> "${r.size} note" + if (r.size == 1) "" else "s"
                        r.size >= BibleRepository.MAX_RESULTS -> "Showing the first ${r.size} verses"
                        else -> "${r.size} verse" + if (r.size == 1) "" else "s"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                val matchStyle = SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x55FFE600))
                LazyColumn(Modifier.weight(1f)) {
                    items(r) { hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.goTo(panelIndex, hit.book, hit.chapter, hit.verse); onDismiss() }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                vm.refLabel(VerseId.of(hit.book, hit.chapter, hit.verse)),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            )
                            Text(markTerms(hit.text, searchedTerms, matchStyle))
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun markTerms(text: String, terms: List<String>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    val lower = text.lowercase()
    for (t in terms) {
        val stem = t.trimEnd('*')
        if (stem.length < 2) continue
        var i = lower.indexOf(stem)
        while (i >= 0) {
            addStyle(style, i, i + stem.length)
            i = lower.indexOf(stem, i + stem.length)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Verse: note, bookmark, cross-references
// ---------------------------------------------------------------------------------------------

@Composable
fun VerseDialog(vm: StudyViewModel, t: VerseTarget, onDismiss: () -> Unit) {
    val id = VerseId.of(t.book, t.chapter, t.verse)
    val version = vm.activeVersion
    val verseText = remember(t, version) { vm.text(version).verseText(id) ?: vm.bible.verseText(id) ?: "" }
    val original = remember(t) { vm.user.note(t.book, t.chapter, t.verse) ?: "" }
    var note by remember(t) { mutableStateOf(original) }
    val refs by produceState(emptyList<CrossRef>(), t) {
        value = withContext(Dispatchers.IO) {
            // Previews in the version being read (the cross-reference list itself is shared).
            val text = vm.text(version)
            vm.bible.crossRefs(id).map { r -> text.verseText(r.toStart)?.let { r.copy(preview = it) } ?: r }
        }
    }
    val panelIndex = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    val otherPanel = if (vm.panels.size > 1) 1 - panelIndex else null

    fun close() {
        if (note != original) vm.setNote(t, note)
        onDismiss()
    }

    BigDialog(::close) {
        Column {
            DialogTitle("${vm.refLabel(id)} ($version)", ::close)
            if (vm.compareVersions) {
                // Parallel view: the verse in every version, stacked (SPLIT-4). Tap one to read it.
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    for (v in BibleRepository.ALL) {
                        val text = remember(t, v) { vm.text(v.code).verseText(id) }
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                if (note != original) vm.setNote(t, note)
                                vm.setVersion(panelIndex, v.code)
                                vm.goTo(panelIndex, t.book, t.chapter, t.verse, remember = false)
                                onDismiss()
                            }.padding(vertical = 4.dp)
                        ) {
                            Text(
                                v.code + if (v.code == version) "  (reading)" else "",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text ?: "Not in this version (see its footnotes).",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (text == null) MaterialTheme.colorScheme.outline else Color.Unspecified,
                            )
                        }
                    }
                }
            } else {
                Text(verseText, style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                val marked = vm.isBookmarked(t)
                OutlinedButton(onClick = { vm.toggleBookmark(t) }) {
                    Icon(if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (marked) "Bookmarked" else "Bookmark")
                }
                FilterChip(
                    selected = vm.compareVersions,
                    onClick = { vm.compareVersions = !vm.compareVersions },
                    label = { Text("Compare versions") },
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Typed note (shows in every version)") },
                supportingText = { Text("References like Rom 8:28 or Psalm 23 become links.") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            // References typed in the note, as links to their passages (LINK-4).
            val noteLinks = remember(note) { RefLinks.find(note, vm.bible.books) }
            var notePassage by remember(t) { mutableStateOf<Passage?>(null) }
            if (noteLinks.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Links in this note:", style = MaterialTheme.typography.labelLarge)
                    for (l in noteLinks) {
                        AssistChip(
                            onClick = { notePassage = l.passage },
                            label = { Text(vm.passageLabel(l.passage)) },
                            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            notePassage?.let { p ->
                Popup(alignment = Alignment.Center, onDismissRequest = { notePassage = null }, properties = PopupProperties(focusable = true)) {
                    PassageCard(
                        vm, p, version,
                        onGoTo = { if (note != original) vm.setNote(t, note); vm.openPassage(p, panelIndex, beside = false); onDismiss() },
                        onOpenBeside = { if (note != original) vm.setNote(t, note); vm.openPassage(p, panelIndex, beside = true); onDismiss() },
                        onClose = { notePassage = null },
                    )
                }
            }
            Text(
                "Cross-references (${refs.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(refs) { r ->
                    val b = VerseId.book(r.toStart); val c = VerseId.chapter(r.toStart); val v = VerseId.verse(r.toStart)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable {
                                    if (note != original) vm.setNote(t, note)
                                    vm.goTo(panelIndex, b, c, v); onDismiss()
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(vm.refLabel(r.toStart, r.toEnd), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(r.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (otherPanel != null) {
                            TextButton(onClick = { vm.goTo(otherPanel, b, c, v) }) { Text("Open in other panel") }
                        }
                    }
                    HorizontalDivider()
                }
            }
            Text(
                "Cross-references: OpenBible.info (CC BY)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Layers
// ---------------------------------------------------------------------------------------------

@Composable
fun LayersDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }

    BigDialog(onDismiss) {
        Column {
            DialogTitle("Layers", onDismiss)
            Text(
                "Layers are global: hiding a layer hides it on every page. New ink goes on the selected layer. " +
                    "Layers higher in this list draw on top.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { vm.setAllLayersVisible(true) }) { Text("Show all") }
                OutlinedButton(onClick = { vm.setAllLayersVisible(false) }) { Text("Hide all") }
                OutlinedButton(onClick = { vm.activeLayer()?.let { vm.showOnlyLayer(it.id) } }) { Text("Show only selected") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(vm.layers.reversed(), key = { it.id }) { l ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.activeLayerId = l.id }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = vm.activeLayerId == l.id, onClick = { vm.activeLayerId = l.id })
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(l.color)))
                        Spacer(Modifier.width(10.dp))
                        if (renaming == l.id) {
                            OutlinedTextField(
                                value = renameText, onValueChange = { renameText = it }, singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { vm.renameLayer(l.id, renameText); renaming = null }),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.renameLayer(l.id, renameText); renaming = null }) { Text("Save") }
                        } else {
                            Text(
                                l.name + if (l.locked) "  (locked)" else "",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (l.visible) Color.Unspecified else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        IconButton(onClick = { vm.toggleLayerVisible(l.id) }) {
                            Icon(if (l.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = if (l.visible) "Hide" else "Show")
                        }
                        IconButton(onClick = { vm.toggleLayerLocked(l.id) }) {
                            Icon(if (l.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = if (l.locked) "Unlock" else "Lock")
                        }
                        IconButton(onClick = { vm.moveLayer(l.id, towardTop = true) }) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                        }
                        IconButton(onClick = { vm.moveLayer(l.id, towardTop = false) }) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                        }
                        IconButton(onClick = { renaming = l.id; renameText = l.name }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Rename")
                        }
                        IconButton(onClick = { confirmDelete = l.id }, enabled = vm.layers.size > 1) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                    HorizontalDivider()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    placeholder = { Text("New layer name, e.g. Sermon notes") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.addLayer(newName); newName = "" }) { Text("Add layer") }
            }
        }
    }

    confirmDelete?.let { id ->
        val name = vm.layers.firstOrNull { it.id == id }?.name ?: ""
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \u201c$name\u201d?") },
            text = { Text("Everything drawn on this layer, on every page, will be deleted. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteLayer(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Bookmarks
// ---------------------------------------------------------------------------------------------

@Composable
fun BookmarksDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    val panelIndex = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    var tab by remember { mutableStateOf(0) }
    BigDialog(onDismiss) {
        Column {
            DialogTitle(if (tab == 0) "Bookmarks" else "Highlights", onDismiss)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Bookmarks") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Highlights") })
            }
            Spacer(Modifier.height(8.dp))
            if (tab == 0) {
                if (vm.bookmarks.isEmpty()) {
                    Text("No bookmarks yet. Tap a verse with your finger, then tap Bookmark.")
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.bookmarks.toList(), key = { it.id }) { b ->
                        val id = VerseId.of(b.book, b.chapter, b.verse)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(1f).clickable { vm.goTo(panelIndex, b.book, b.chapter, b.verse); onDismiss() }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(vm.refLabel(id), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(vm.text(vm.activeVersion).verseText(id) ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { vm.deleteBookmark(b) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove bookmark") }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                HighlightsList(vm, panelIndex, onDismiss, Modifier.weight(1f))
            }
        }
    }
}

/** Every highlight in Bible order, with its words in their colour; filter by colour or layer (HL-8). */
@Composable
private fun HighlightsList(vm: StudyViewModel, panelIndex: Int, onDismiss: () -> Unit, modifier: Modifier) {
    val entries by produceState<List<HighlightEntry>?>(null, vm.dataGeneration, vm.editCount) { value = vm.highlightEntries() }
    var colorFilter by remember { mutableStateOf<Int?>(null) }
    var layerFilter by remember { mutableStateOf<Long?>(null) }
    val all = entries
    if (all == null) {
        Text("Loading\u2026")
        return
    }
    if (all.isEmpty()) {
        Text("No highlights yet. Hold a finger on a word, drag to choose the words, then tap Highlight.")
        return
    }
    val colors = all.map { it.highlight.color }.distinct()
    val layerIds = all.map { it.highlight.layerId }.toSet()
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = colorFilter == null && layerFilter == null, onClick = { colorFilter = null; layerFilter = null }, label = { Text("All") })
        for (c in colors) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        if (colorFilter == c) 3.dp else 1.dp,
                        if (colorFilter == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
                    .semantics { contentDescription = "Only this colour" }
                    .clickable { colorFilter = if (colorFilter == c) null else c }
            )
        }
        if (layerIds.size > 1) {
            VerticalDivider(Modifier.height(24.dp))
            for (l in vm.layers.filter { it.id in layerIds }) {
                FilterChip(
                    selected = layerFilter == l.id,
                    onClick = { layerFilter = if (layerFilter == l.id) null else l.id },
                    label = { Text(l.name) },
                )
            }
        }
    }
    val shown = all.filter { e ->
        (colorFilter == null || e.highlight.color == colorFilter) && (layerFilter == null || e.highlight.layerId == layerFilter)
    }
    Text(
        "${shown.size} highlight" + (if (shown.size == 1) "" else "s"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    LazyColumn(modifier) {
        items(shown, key = { it.highlight.id }) { e ->
            val h = e.highlight
            val layerName = vm.layers.firstOrNull { it.id == h.layerId }?.name ?: ""
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clickable {
                        vm.setVersion(panelIndex, h.version)
                        vm.goTo(panelIndex, h.book, h.chapter, e.verse)
                        onDismiss()
                    }.padding(vertical = 8.dp)
                ) {
                    Text(
                        vm.refLabel(VerseId.of(h.book, h.chapter, e.verse)) + "  \u00b7  ${h.version}  \u00b7  $layerName",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        buildAnnotatedString {
                            pushStyle(SpanStyle(background = Color(h.color).copy(alpha = HIGHLIGHT_ALPHA)))
                            append(e.words)
                            pop()
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { vm.removeHighlight(h) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove highlight") }
            }
            HorizontalDivider()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------------------------

/** What each bundled translation is, so readers know how they differ. */
@Composable
fun VersionsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("About these versions") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (v in BibleRepository.ALL) {
                    Text("${v.code} \u2014 ${v.name}", style = MaterialTheme.typography.titleMedium)
                    Text(v.summary, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(v.description)
                    Text(v.copyright, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    "The NIV and NLT are copyrighted and can be added once permission is granted by their publishers.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Bible Study \u2014 version ${BuildConfig.VERSION_NAME}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Works completely offline. Your notes stay on this tablet unless you make a backup.")
                Spacer(Modifier.height(12.dp))
                Text("Credits", style = MaterialTheme.typography.titleMedium)
                for (v in BibleRepository.ALL) Text("\u2022 ${v.name}: ${v.copyright}")
                Text("\u2022 Cross-references: OpenBible.info, licensed CC BY.")
                Text("\u2022 Bible text font: Gentium Book Plus \u00a9 SIL International, SIL Open Font License.")
                Spacer(Modifier.height(12.dp))
                Text("How to use", style = MaterialTheme.typography.titleMedium)
                Text("\u2022 Pen: draws with the selected tool. Fingers: scroll, pinch to zoom, tap a verse for notes and cross-references.")
                Text("\u2022 Text never rewraps: zoom changes the size, so your ink always stays on the right words.")
                Text("\u2022 Margin notes follow their verse. Ink on the words belongs to the version you drew it on.")
            }
        },
    )
}
