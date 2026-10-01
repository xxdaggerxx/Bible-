package com.biblestudy.app.ui

import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.VerticalSplit
import com.biblestudy.app.model.Bookmark
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
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
import com.biblestudy.app.data.StudyRepository
import com.biblestudy.app.data.BookIntros
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
internal fun BigDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
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
internal fun DialogTitle(title: String, onClose: () -> Unit, leading: (@Composable () -> Unit)? = null) {
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
    // Chapters already read get a light tint (ANL-5).
    val readChapters by produceState(emptySet<Int>(), vm.readingGeneration) {
        value = withContext(Dispatchers.IO) { vm.user.readingChapters().filter { it.timesRead > 0 }.mapTo(HashSet()) { it.book * 1000 + it.chapter } }
    }
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
                                GridCell(bk.name, m, onInfo = { vm.introBook = bk.id }) { book = bk.id; if (bk.chapters == 1) chapter = 1 }
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
                    TextButton(onClick = { vm.introBook = b }) {
                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("About this book")
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
                            GridCell(ch.toString(), m, label = "${info.name} $ch", read = b * 1000 + ch in readChapters) { chapter = ch }
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
/** A chapter that has been read (ANL-5). */
val READ_TINT = Color(0x4D7FB77E)

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
private fun GridCell(text: String, marks: Marks? = null, label: String = text, read: Boolean = false, onInfo: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (read) READ_TINT else MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (read) Modifier.semantics { stateDescription = "read" } else Modifier)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = if (onInfo != null) 30.dp else 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            MarkerRow(marks, label)
        }
        if (onInfo != null) {
            // Opens the book's introduction (STD-13).
            Icon(
                Icons.Outlined.Info,
                contentDescription = "About $label",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterEnd).clip(CircleShape).clickable(onClick = onInfo).padding(6.dp).size(18.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------------------------

@Composable
fun SearchDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    BigDialog(onDismiss) {
        Column {
            DialogTitle("Search", onDismiss)
            SearchPane(vm, Modifier.weight(1f), onOpened = onDismiss, inPane = false)
        }
    }
}

/**
 * Search box, options and results. In the dialog, opening a result closes it; in the study pane
 * beside the text (SPLIT-2) the results stay while the Bible panel moves.
 */
@Composable
fun SearchPane(vm: StudyViewModel, modifier: Modifier, onOpened: () -> Unit, inPane: Boolean) {
    var query by remember { mutableStateOf(vm.lastSearch) }
    var scope by remember { mutableStateOf(SearchScope.ALL) }
    var version by remember { mutableStateOf(vm.activeVersion) }
    var inNotes by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchHit>?>(null) }
    var searchedTerms by remember { mutableStateOf(emptyList<String>()) }
    var searchedStrong by remember { mutableStateOf<String?>(null) }
    var strongWords by remember { mutableStateOf<Map<Int, List<IntRange>>>(emptyMap()) }
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
                    val (wanted, excluded) = BibleRepository.splitExcluded(q)
                    vm.user.searchNotes(wanted, lo, hi).filterNot { BibleRepository.containsAny(it.text, excluded) }
                } else if (StudyRepository.normalizeStrong(q) != null) {
                    // A Strong's number, e.g. G26 or H7225 (SRCH-7): every verse using that word.
                    val (lo, hi) = when (scope) {
                        SearchScope.ALL -> 1 to 66
                        SearchScope.OT -> 1 to 39
                        SearchScope.NT -> 40 to 66
                        SearchScope.BOOK -> currentBook to currentBook
                    }
                    val uses = vm.study.occurrences(v, q, { vm.text(v).verseText(it) })
                        .filter { VerseId.book(it.id) in lo..hi }
                    strongWords = uses.associate { it.id to it.words }
                    uses.map { SearchHit(VerseId.book(it.id), VerseId.chapter(it.id), VerseId.verse(it.id), it.text) }
                } else {
                    vm.text(v).search(q, scope, currentBook)
                }
            }
            searchedStrong = if (!notes) StudyRepository.normalizeStrong(q) else null
            searchedTerms = if (searchedStrong != null) emptyList() else BibleRepository.terms(q)
        }
    }
    LaunchedEffect(scope, version, inNotes) { if (query.isNotBlank() && results != null) run() }

    // The search pane runs a search handed over from the search dialog.
    if (inPane) {
        LaunchedEffect(vm.paneSearch) {
            val q = vm.paneSearch ?: return@LaunchedEffect
            vm.paneSearch = null
            query = q
            run()
        }
    }

    Column(modifier) {
        run {
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
            // Inside the dialog, so the field exists by the time it asks for the keyboard.
            if (!inPane) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                "Tips: all words must match; \"quotes\" for an exact phrase; OR between words for either; " +
                    "-word to leave out verses with that word; * for word beginnings (lov* finds love, loved, loveth); " +
                    "a Strong's number like G26 or H2617 finds every verse using that Greek or Hebrew word.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            if (ref != null) {
                val label = vm.bible.book(ref.book).name + " ${ref.chapter}" + (ref.verse?.let { ":$it" } ?: "")
                Button(
                    onClick = { vm.goTo(panelIndex, ref.book, ref.chapter, ref.verse); onOpened() },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Go to $label") }
            }
            val r = results
            if (r != null && r.isNotEmpty() && !inPane) {
                TextButton(onClick = { vm.paneSearch = query; vm.sidePane = PaneKind.SEARCH; onOpened() }) {
                    Icon(Icons.Filled.VerticalSplit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Keep results beside the text")
                }
            }
            searchedStrong?.let { if (r != null) StrongsHeader(vm, it, version) }
            if (r != null) {
                // Results grouped by book, with counts (SRCH-5); a book chip shows just that book.
                var onlyBook by remember(r) { mutableStateOf<Int?>(null) }
                val byBook = remember(r) { r.groupBy { it.book } }
                Text(
                    when {
                        r.isEmpty() -> if (inNotes) "No notes found." else "No verses found."
                        inNotes -> "${r.size} note" + (if (r.size == 1) "" else "s") + " in ${byBook.size} book" + if (byBook.size == 1) "" else "s"
                        r.size >= BibleRepository.MAX_RESULTS -> "Showing the first ${r.size} verses"
                        else -> "${r.size} verse" + (if (r.size == 1) "" else "s") + " in ${byBook.size} book" + if (byBook.size == 1) "" else "s"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                if (byBook.size > 1) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(selected = onlyBook == null, onClick = { onlyBook = null }, label = { Text("All books") })
                        for ((b, hits) in byBook) {
                            FilterChip(
                                selected = onlyBook == b,
                                onClick = { onlyBook = if (onlyBook == b) null else b },
                                label = { Text("${vm.bible.book(b).name} ${hits.size}") },
                            )
                        }
                    }
                }
                val matchStyle = SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x55FFE600))
                LazyColumn(Modifier.weight(1f)) {
                    for ((b, hits) in byBook) {
                        if (onlyBook != null && onlyBook != b) continue
                        item(key = "book$b") {
                            Text(
                                "${vm.bible.book(b).name} \u2014 ${hits.size}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        items(hits, key = { "${it.book}.${it.chapter}.${it.verse}" }) { hit ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable { vm.goTo(panelIndex, hit.book, hit.chapter, hit.verse); onOpened() }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        vm.refLabel(VerseId.of(hit.book, hit.chapter, hit.verse)),
                                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                    )
                                    val words = strongWords[VerseId.of(hit.book, hit.chapter, hit.verse)]
                                    if (searchedStrong != null && words != null) {
                                        Text(buildAnnotatedString { append(hit.text); for (w in words) addStyle(matchStyle, w.first, w.last + 1) })
                                    } else {
                                        Text(markTerms(hit.text, searchedTerms, matchStyle))
                                    }
                                }
                                // Open in the side panel, keeping this passage where it is.
                                IconButton(onClick = {
                                    vm.openPassage(Passage(hit.book, hit.chapter, hit.verse, hit.chapter, hit.verse), panelIndex, beside = true)
                                    onOpened()
                                }) { Icon(Icons.Filled.VerticalSplit, contentDescription = "Open beside") }
                            }
                            HorizontalDivider()
                        }
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
    // The note on this verse, or on a range of verses that includes it (NOTE-1).
    val existing = remember(t) { vm.user.noteCovering(t.book, t.chapter, t.verse) }
    val noteStart = existing?.verse ?: t.verse
    val original = existing?.text ?: ""
    val originalEnd = existing?.endVerse ?: t.verse
    var note by remember(t) { mutableStateOf(original) }
    var noteEnd by remember(t) { mutableStateOf(originalEnd) }
    val lastVerse = remember(t) { vm.bible.chapter(t.book, t.chapter).lastOrNull()?.verse ?: t.verse }
    val refs by produceState(emptyList<CrossRef>(), t) {
        value = withContext(Dispatchers.IO) {
            // Previews in the version being read (the cross-reference list itself is shared).
            val text = vm.text(version)
            vm.bible.crossRefs(id).map { r -> text.verseText(r.toStart)?.let { r.copy(preview = it) } ?: r }
        }
    }
    val panelIndex = vm.activePanel.coerceIn(0, vm.panels.lastIndex)

    fun save() {
        if (note != original || noteEnd != originalEnd) vm.setNote(VerseTarget(t.book, t.chapter, noteStart), note, noteEnd)
    }

    fun close() {
        save()
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
                                save()
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
                // Each word with Hebrew or Greek behind it opens a word study (STD-3).
                StudyableVerse(vm, id, version, verseText)
            }
            // The word tapped on the page, ready to study.
            val tapped by produceState<WordStudy?>(null, t, version) {
                value = if (t.word < 0) null else withContext(Dispatchers.IO) {
                    val strong = vm.study.strongs(version, id).getOrNull(t.word)
                    val range = com.biblestudy.app.data.StudyRepository.words(verseText).getOrNull(t.word)
                    if (strong != null && range != null) WordStudy(strong, version, verseText.substring(range)) else null
                }
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
                // Tags on this verse's note (NOTE-4).
                if (original.isNotBlank()) TagButton(vm, vm.noteKey(t.book, t.chapter, noteStart))
                tapped?.let { w ->
                    FilledTonalButton(onClick = { vm.wordStudy = w }) { Text("Word study: \u201c${w.word}\u201d") }
                }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Note on " + vm.refLabel(VerseId.of(t.book, t.chapter, noteStart), VerseId.of(t.book, t.chapter, noteEnd)),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = { noteEnd-- }, enabled = noteEnd > noteStart) {
                    Icon(Icons.Filled.Remove, contentDescription = "Note on one verse fewer")
                }
                IconButton(onClick = { noteEnd++ }, enabled = noteEnd < lastVerse) {
                    Icon(Icons.Filled.Add, contentDescription = "Note on one more verse")
                }
            }
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
                        onGoTo = { save(); vm.openPassage(p, panelIndex, beside = false); onDismiss() },
                        onOpenBeside = { save(); vm.openPassage(p, panelIndex, beside = true); onDismiss() },
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
                    // A cross-reference opens the passage pop-over (LINK-5): read it here, then
                    // Go to or Open beside.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { notePassage = r.passage() }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(vm.refLabel(r.toStart, r.toEnd), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(r.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                                l.name + (if (l.locked) "  (locked)" else "") +
                                    if (l.opacity < 0.999f) "  \u00b7 ${(l.opacity * 100).toInt()}%" else "",
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
                        // Everything else is in the layer's menu, so each row stays short (UI-1).
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${l.name}") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Move up") },
                                    leadingIcon = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
                                    onClick = { vm.moveLayer(l.id, towardTop = true) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Move down") },
                                    leadingIcon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
                                    onClick = { vm.moveLayer(l.id, towardTop = false) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    onClick = { renaming = l.id; renameText = l.name; menu = false },
                                )
                                // Colour tag and how solid the layer is drawn (LAY-8).
                                Text("Colour", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (c in LAYER_COLORS) {
                                        Box(
                                            Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                                .then(if (c == l.color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                                .clickable { vm.setLayerColor(l.id, c) }
                                        )
                                    }
                                }
                                Text("Opacity", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (o in listOf(1f, 0.75f, 0.5f, 0.25f)) {
                                        FilterChip(
                                            selected = kotlin.math.abs(l.opacity - o) < 0.01f,
                                            onClick = { vm.setLayerOpacity(l.id, o) },
                                            label = { Text("${(o * 100).toInt()}%") },
                                        )
                                    }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text("Delete layer") },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    enabled = vm.layers.size > 1,
                                    onClick = { confirmDelete = l.id; menu = false },
                                )
                            }
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
    // The notes browser (NOTE-5): typed notes and text boxes, highlights, and bookmarks.
    var tab by remember { mutableStateOf(0) }
    BigDialog(onDismiss) {
        Column {
            DialogTitle("My notes", onDismiss)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Notes") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Highlights") })
                FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("Bookmarks") })
            }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                0 -> NotesList(vm, panelIndex, onDismiss, Modifier.weight(1f))
                1 -> HighlightsList(vm, panelIndex, onDismiss, Modifier.weight(1f))
                else -> BookmarksList(vm, panelIndex, onDismiss, Modifier.weight(1f))
            }
        }
    }
}

/**
 * Typed notes and margin text boxes (NOTE-5), by book, tag and layer, in Bible order or newest
 * first. Tap one to go there.
 */
@Composable
private fun NotesList(vm: StudyViewModel, panelIndex: Int, onDismiss: () -> Unit, modifier: Modifier) {
    val data by produceState<Pair<List<com.biblestudy.app.data.NoteEntry>, List<com.biblestudy.app.model.MarginText>>?>(
        null, vm.dataGeneration, vm.editCount,
    ) { value = vm.browseNotes() }
    var book by remember { mutableStateOf<Int?>(null) }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var layer by remember { mutableStateOf<Long?>(null) }
    var newest by remember { mutableStateOf(false) }
    val d = data
    if (d == null) { Text("Loading\u2026"); return }

    /** One row: a typed note (layer null) or a text box. */
    class Item(val key: String, val book: Int, val chapter: Int, val verse: Int, val endVerse: Int, val text: String, val time: Long, val layer: Long?)
    val items = d.first.map { Item(vm.noteKey(it.book, it.chapter, it.verse), it.book, it.chapter, it.verse, it.endVerse, it.text, it.updated, null) } +
        d.second.filter { it.text.isNotBlank() }.map { Item("t:${it.id}", it.book, it.chapter, it.verse, it.verse, it.text, it.id / 1000, it.layerId) }
    if (items.isEmpty()) {
        Text("No notes yet. Tap a verse to type a note, or add a text box from the Insert menu.")
        return
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = !newest, onClick = { newest = false }, label = { Text("Bible order") })
        FilterChip(selected = newest, onClick = { newest = true }, label = { Text("Newest") })
        VerticalDivider(Modifier.height(24.dp))
        var bookMenu by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = book != null,
                onClick = { bookMenu = true },
                label = { Text(book?.let { vm.bible.book(it).name } ?: "All books") },
            )
            DropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                DropdownMenuItem(text = { Text("All books") }, onClick = { book = null; bookMenu = false })
                for (b in items.map { it.book }.distinct().sorted()) {
                    DropdownMenuItem(text = { Text(vm.bible.book(b).name) }, onClick = { book = b; bookMenu = false })
                }
            }
        }
        val layerIds = items.mapNotNull { it.layer }.toSet()
        if (layerIds.size > 1) {
            for (l in vm.layers.filter { it.id in layerIds }) {
                FilterChip(selected = layer == l.id, onClick = { layer = if (layer == l.id) null else l.id }, label = { Text(l.name) })
            }
        }
    }
    TagFilter(vm, items.map { it.key }, tagFilter) { tagFilter = it }
    val shown = items.filter { i ->
        (book == null || i.book == book) && (layer == null || i.layer == layer) &&
            (tagFilter == null || tagFilter in vm.tags[i.key].orEmpty())
    }.let { l -> if (newest) l.sortedByDescending { it.time } else l.sortedWith(compareBy({ it.book }, { it.chapter }, { it.verse })) }
    val dateFormat = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    Text(
        "${shown.size} note" + (if (shown.size == 1) "" else "s"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    LazyColumn(modifier) {
        items(shown, key = { it.key }) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clickable { vm.goTo(panelIndex, i.book, i.chapter, i.verse); onDismiss() }.padding(vertical = 8.dp)
                ) {
                    val where = if (i.layer != null) "margin" else "note"
                    Text(
                        vm.refLabel(VerseId.of(i.book, i.chapter, i.verse), VerseId.of(i.book, i.chapter, i.endVerse)) +
                            "  \u00b7  $where  \u00b7  ${dateFormat.format(java.util.Date(i.time))}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(i.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    vm.tags[i.key]?.let { t ->
                        Text(t.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                TagButton(vm, i.key)
            }
            HorizontalDivider()
        }
    }
}

/** Chips to show only items with one tag (NOTE-4); hidden when none of [keys] has a tag. */
@Composable
private fun TagFilter(vm: StudyViewModel, keys: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    val used = keys.flatMap { vm.tags[it].orEmpty() }.distinct().sortedBy { it.lowercase() }
    if (used.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Sell, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        for (t in used) {
            FilterChip(selected = selected == t, onClick = { onSelect(if (selected == t) null else t) }, label = { Text("#$t") })
        }
    }
}

/** A small tag button for one item; shows how many tags it has and opens the tag editor. */
@Composable
fun TagButton(vm: StudyViewModel, key: String) {
    var open by remember { mutableStateOf(false) }
    val count = vm.tags[key]?.size ?: 0
    IconButton(onClick = { open = true }) {
        Icon(
            Icons.Filled.Sell,
            contentDescription = if (count == 0) "Add tags" else "Tags ($count)",
            tint = if (count == 0) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
        )
    }
    if (open) TagEditor(vm, key) { open = false }
}

/** Pick existing tags or type a new one (NOTE-4). */
@Composable
fun TagEditor(vm: StudyViewModel, key: String, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(vm.tags[key].orEmpty()) }
    var typed by remember { mutableStateOf("") }
    val known = (vm.allTags() + chosen).distinct().sortedBy { it.lowercase() }
    fun add() {
        val t = typed.trim().removePrefix("#")
        if (t.isNotEmpty()) chosen = chosen + t
        typed = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (known.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (t in known) {
                            FilterChip(
                                selected = t in chosen,
                                onClick = { chosen = if (t in chosen) chosen - t else chosen + t },
                                label = { Text("#$t") },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true,
                    placeholder = { Text("New tag, e.g. grace") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    trailingIcon = { TextButton(onClick = ::add, enabled = typed.isNotBlank()) { Text("Add") } },
                )
            }
        },
        confirmButton = { TextButton(onClick = { add(); vm.setTags(key, chosen); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Bookmarks, newest first, filtered by folder (NOTE-3). */
@Composable
private fun BookmarksList(vm: StudyViewModel, panelIndex: Int, onDismiss: () -> Unit, modifier: Modifier) {
    // null = all bookmarks, "" = not in a folder
    var folder by remember { mutableStateOf<String?>(null) }
    // Asking for a folder name: to make a new folder (and move [naming] into it), or to rename one.
    var naming by remember { mutableStateOf<Bookmark?>(null) }
    var askName by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val folders = vm.allBookmarkFolders()

    Column(modifier) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = folder == null, onClick = { folder = null }, label = { Text("All (${vm.bookmarks.size})") })
            if (folders.isNotEmpty()) {
                val loose = vm.bookmarks.count { it.folder.isEmpty() }
                FilterChip(selected = folder == "", onClick = { folder = "" }, label = { Text("Not in a folder ($loose)") })
            }
            for (f in folders) {
                FilterChip(
                    selected = folder == f,
                    onClick = { folder = f },
                    label = { Text("$f (${vm.bookmarks.count { it.folder == f }})") },
                    leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
            }
            TextButton(onClick = { naming = null; askName = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New folder")
            }
        }
        val f = folder
        if (f != null && f.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { renaming = f }) { Text("Rename folder") }
                TextButton(onClick = { vm.deleteBookmarkFolder(f); folder = null }) { Text("Delete folder") }
            }
        }
        var tagFilter by remember { mutableStateOf<String?>(null) }
        TagFilter(vm, vm.bookmarks.map { "b:${it.id}" }, tagFilter) { tagFilter = it }
        val shown = vm.bookmarks.filter { (f == null || it.folder == f) && (tagFilter == null || tagFilter in vm.tags["b:${it.id}"].orEmpty()) }
        if (shown.isEmpty()) {
            Text(
                if (vm.bookmarks.isEmpty()) "No bookmarks yet. Tap a verse with your finger, then tap Bookmark."
                else "No bookmarks in this folder. Use the folder button on a bookmark to move it here.",
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { b ->
                val id = VerseId.of(b.book, b.chapter, b.verse)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).clickable { vm.goTo(panelIndex, b.book, b.chapter, b.verse); onDismiss() }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            vm.refLabel(id) + if (b.folder.isNotEmpty() && f == null) "  \u00b7  ${b.folder}" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(vm.text(vm.activeVersion).verseText(id) ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move to folder") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Not in a folder" + if (b.folder.isEmpty()) "  \u2713" else "") },
                                onClick = { vm.moveBookmark(b, ""); menu = false },
                            )
                            for (name in folders) {
                                DropdownMenuItem(
                                    text = { Text(name + if (b.folder == name) "  \u2713" else "") },
                                    onClick = { vm.moveBookmark(b, name); menu = false },
                                )
                            }
                            DropdownMenuItem(text = { Text("New folder\u2026") }, onClick = { menu = false; naming = b; askName = true })
                        }
                    }
                    TagButton(vm, "b:${b.id}")
                    IconButton(onClick = { vm.deleteBookmark(b) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove bookmark") }
                }
                HorizontalDivider()
            }
        }
    }

    if (askName || renaming != null) {
        var name by remember { mutableStateOf(renaming ?: "") }
        fun done() {
            val r = renaming
            if (r != null) {
                vm.renameBookmarkFolder(r, name)
                if (folder == r) folder = name.trim().ifEmpty { r }
            } else {
                vm.addBookmarkFolder(name)?.let { n -> naming?.let { vm.moveBookmark(it, n) } }
            }
            askName = false; renaming = null; naming = null
        }
        AlertDialog(
            onDismissRequest = { askName = false; renaming = null; naming = null },
            title = { Text(if (renaming != null) "Rename folder" else "New folder") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text("e.g. Sermon series, Promises") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { done() }),
                )
            },
            confirmButton = { TextButton(onClick = ::done, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { askName = false; renaming = null; naming = null }) { Text("Cancel") } },
        )
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
            val meaning = vm.meanings[c]
            if (meaning != null) {
                // A colour with a meaning (HL-5) shows it, e.g. "Promises".
                FilterChip(
                    selected = colorFilter == c,
                    onClick = { colorFilter = if (colorFilter == c) null else c },
                    label = { Text(meaning) },
                    leadingIcon = { Box(Modifier.size(16.dp).clip(CircleShape).background(Color(c))) },
                )
            } else Box(
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
    var tagFilter by remember { mutableStateOf<String?>(null) }
    TagFilter(vm, all.map { "h:${it.highlight.id}" }, tagFilter) { tagFilter = it }
    val shown = all.filter { e ->
        (colorFilter == null || e.highlight.color == colorFilter) && (layerFilter == null || e.highlight.layerId == layerFilter) &&
            (tagFilter == null || tagFilter in vm.tags["h:${e.highlight.id}"].orEmpty())
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
                TagButton(vm, "h:${h.id}")
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
                Text("\u2022 Hebrew and Greek dictionaries: Strong's (1890, public domain), from the Open Scriptures edition, licensed CC BY-SA.")
                Text("\u2022 Strong's numbers for each word: the KJV, BSB and WEB editions at eBible.org (public domain).")
                Text("\u2022 Easton's Bible Dictionary (1897), Nave's Topical Bible (1896) and Matthew Henry's Concise Commentary: public domain, from the Christian Classics Ethereal Library.")
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

// ---------------------------------------------------------------------------------------------
// Book introductions
// ---------------------------------------------------------------------------------------------

/**
 * A study introduction to a book (STD-12): opened from the book picker and the chapter header
 * (STD-13). The outline, key verses and references in the text open the passage.
 */
@Composable
/** [onDismiss] is told whether the reader was sent to a passage (so the book picker can close too). */
fun BookIntroDialog(vm: StudyViewModel, book: Int, onDismiss: (navigated: Boolean) -> Unit) {
    val context = LocalContext.current
    val intro = remember(book) { BookIntros.get(context, book) }
    val panelIndex = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    val version = vm.activeVersion
    val info = vm.bible.book(book)
    var shown by remember(book) { mutableStateOf<Passage?>(null) }
    val linkColor = LINK_COLOR

    fun open(p: Passage) {
        vm.goTo(panelIndex, p.book, p.chapter, p.verse)
        onDismiss(true)
    }

    BigDialog({ onDismiss(false) }) {
        Column {
            DialogTitle("About ${info.name}", { onDismiss(false) })
            if (intro == null) {
                Text("No introduction for this book yet.")
                return@Column
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                @Composable
                fun Fact(label: String, text: String) {
                    if (text.isBlank()) return
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(110.dp))
                        Text(text, modifier = Modifier.weight(1f))
                    }
                }

                @Composable
                fun Heading(text: String) {
                    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                }

                /** Text with Bible references made into links (LINK-4 style). */
                @Composable
                fun Linked(text: String) {
                    val links = remember(text) { RefLinks.find(text, vm.bible.books) }
                    Text(buildAnnotatedString {
                        append(text)
                        for (l in links) {
                            addLink(
                                LinkAnnotation.Clickable("ref", TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) {
                                    shown = l.passage
                                },
                                l.start, l.end,
                            )
                        }
                    })
                }

                Fact("Author", intro.author)
                Fact("Written", intro.date)
                Fact("Where", intro.place)
                Fact("Written to", intro.audience)
                Fact("Type", intro.type)

                Heading("Historical background")
                Linked(intro.background)
                Heading("Purpose")
                Linked(intro.purpose)
                Heading("Main themes")
                Text(intro.themes)

                Heading("Outline")
                for (o in intro.outline) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open(o.passage) }.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(o.title, modifier = Modifier.weight(1f))
                        Text(vm.passageLabel(o.passage).removePrefix(info.name + " "), color = linkColor, style = MaterialTheme.typography.labelLarge)
                    }
                }

                Heading("Key people")
                Text(intro.people)
                Heading("Key places")
                Text(intro.places)

                Heading("Key verses")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in intro.keyVerses) {
                        AssistChip(
                            onClick = { shown = p },
                            label = { Text(vm.passageLabel(p)) },
                            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }

                Heading("Connections")
                Linked(intro.connections)

                Text(
                    "Authorship and dates follow the traditional view.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { open(Passage(book, 1, 1, 1, 1)) }) { Text("Read from chapter 1") }
            }
        }
    }
    shown?.let { p ->
        Popup(alignment = Alignment.Center, onDismissRequest = { shown = null }, properties = PopupProperties(focusable = true)) {
            PassageCard(
                vm, p, version,
                onGoTo = { vm.openPassage(p, panelIndex, beside = false); onDismiss(true) },
                onOpenBeside = { vm.openPassage(p, panelIndex, beside = true); onDismiss(true) },
                onClose = { shown = null },
            )
        }
    }
}

/** The passage a cross-reference points to (LINK-5). */
fun CrossRef.passage() = Passage(
    VerseId.book(toStart), VerseId.chapter(toStart), VerseId.verse(toStart), VerseId.chapter(toEnd), VerseId.verse(toEnd),
)
