package com.biblestudy.app.ui

import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.InputChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    // On a phone the window fills the screen (PH-7).
    val phone = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp in 1 until 600
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(if (phone) 0.dp else 20.dp),
            tonalElevation = 6.dp,
            modifier = if (phone) Modifier.fillMaxWidth().fillMaxHeight() else Modifier.fillMaxWidth(0.92f).widthIn(max = 820.dp).fillMaxHeight(0.88f),
        ) {
            Box(Modifier.padding(if (phone) 12.dp else 20.dp)) { content() }
        }
    }
}

@Composable
internal fun DialogTitle(title: String, onClose: () -> Unit, leading: (@Composable () -> Unit)? = null, actions: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        actions?.invoke()
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
    }
}

// ---------------------------------------------------------------------------------------------
// Book & chapter picker
// ---------------------------------------------------------------------------------------------

@Composable
fun BookPickerDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    var pickerTab by remember { mutableStateOf(0) }
    var book by remember { mutableStateOf<Int?>(null) }
    var chapter by remember { mutableStateOf<Int?>(null) }
    val panelIndex = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    val version = vm.activeVersion
    // Where the user has notes; ink on the words counts for the version being read.
    val markers by produceState<MarkerIndex?>(null, version, vm.dataGeneration) { value = vm.loadMarkers(version) }
    // Chapters already read get a light tint (ANL-5).
    val readChapters by produceState(emptySet<Int>(), vm.readingGeneration) {
        value = background { vm.user.readingChapters().filter { it.timesRead > 0 }.mapTo(HashSet()) { it.book * 1000 + it.chapter } }
    }
    val visible = vm.visibleLayerIds()
    val colors = vm.layers.associate { it.id to it.color }

    fun marks(layerIds: List<Long>, note: Boolean) = Marks(layerIds.mapNotNull { colors[it] }, note)

    BigDialog(onDismiss) {
        Column {
            val b = book
            val c = chapter
            when {
                b == null -> {
                    DialogTitle("Choose a book", onDismiss)
                    // Books, the chapters read lately (READ-8) and bookmarks (NOTE-3).
                    TabRow(selectedTabIndex = pickerTab, modifier = Modifier.padding(bottom = 8.dp)) {
                        for ((i, name) in listOf("Books", "Recently read", "Bookmarks").withIndex()) {
                            Tab(selected = pickerTab == i, onClick = { pickerTab = i }, text = { Text(name) }, modifier = Modifier.testTag("pickerTab$i"))
                        }
                    }
                    if (pickerTab == 1) {
                        RecentList(vm, version, Modifier.weight(1f)) { r -> vm.goTo(panelIndex, r.book, r.chapter, r.verse); onDismiss() }
                        return@Column
                    }
                    if (pickerTab == 2) {
                        BookmarkList(vm, version, Modifier.weight(1f)) { m -> vm.goTo(panelIndex, m.book, m.chapter, m.verse); onDismiss() }
                        return@Column
                    }
                    MarkerLegend()
                    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.weight(1f)) {
                        for ((label, range) in listOf("Old Testament" to 1..39, "New Testament" to 40..66)) {
                            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(label) }
                            items(vm.bible.books.filter { it.id in range }, key = { it.id }) { bk ->
                                val m = markers?.let { idx ->
                                    marks(idx.layers(visible, bk.id), idx.hasNote(bk.id))
                                }
                                // The book's introduction is on its chapter screen (About this book), not on every book here.
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
                    TextButton(onClick = { vm.introBook = b }) {
                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("About this book")
                    }
                    MarkerLegend()
                    LazyVerticalGrid(columns = GridCells.Adaptive(72.dp), modifier = Modifier.weight(1f)) {
                        items((1..info.chapters).toList()) { ch ->
                            val m = markers?.let { idx ->
                                marks(idx.layers(visible, b, ch), idx.hasNote(b, ch))
                            }
                            GridCell(ch.toString(), m, label = "${info.name} $ch", read = b * 1000 + ch in readChapters) { chapter = ch }
                        }
                    }
                }
                else -> {
                    val info = vm.bible.book(b)
                    val verses by produceState(emptyList<Verse>(), b, c, version) {
                        value = background { vm.text(version).chapter(b, c) }
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
                            val m = marks(byVerse[v.verse] ?: emptyList(), markers?.hasNote(b, c, v.verse) == true)
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

/** The chapters read lately (READ-8), newest first: tap one to go back to where you were. */
@Composable
private fun RecentList(vm: StudyViewModel, version: String, modifier: Modifier, onOpen: (RecentRead) -> Unit) {
    val items = vm.recent.toList()
    Column(modifier) {
        if (items.isEmpty()) {
            Text("The chapters you read show here, newest first, so you can go back to where you were.", modifier = Modifier.padding(vertical = 12.dp))
            return@Column
        }
        LazyColumn(Modifier.weight(1f).testTag("recentList")) {
            items(items, key = { "${it.book}-${it.chapter}" }) { r ->
                PlaceRow(vm, version, r.book, r.chapter, r.verse, whenText(r.at)) { onOpen(r) }
            }
        }
        TextButton(onClick = { vm.clearRecent() }) { Text("Clear this list") }
    }
}

/** Bookmarked verses (NOTE-3), newest first. */
@Composable
private fun BookmarkList(vm: StudyViewModel, version: String, modifier: Modifier, onOpen: (com.biblestudy.app.model.Bookmark) -> Unit) {
    val items = vm.bookmarks.toList()
    Column(modifier) {
        if (items.isEmpty()) {
            Text("No bookmarks yet. Tap a verse, then tap Bookmark.", modifier = Modifier.padding(vertical = 12.dp))
            return@Column
        }
        LazyColumn(Modifier.weight(1f).testTag("bookmarkList")) {
            items(items, key = { it.id }) { m ->
                PlaceRow(vm, version, m.book, m.chapter, m.verse, "Bookmarked " + whenText(m.created), onRemove = { vm.removeBookmark(m) }) { onOpen(m) }
            }
        }
    }
}

/** A place in the Bible: its reference, the start of the verse, and when. */
@Composable
private fun PlaceRow(vm: StudyViewModel, version: String, book: Int, chapter: Int, verse: Int, time: String, onRemove: (() -> Unit)? = null, onOpen: () -> Unit) {
    val id = VerseId.of(book, chapter, verse)
    val text by produceState("", id, version) {
        value = background { vm.text(version).verseText(id) ?: vm.bible.verseText(id) ?: "" }
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vm.refLabel(id), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("  $time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove bookmark on ${vm.refLabel(id)}") }
    }
    HorizontalDivider()
}

/** "Just now", "5 minutes ago", "Yesterday", "3 Oct". */
private fun whenText(at: Long): String {
    val now = System.currentTimeMillis()
    if (now - at < 60_000) return "Just now"
    return android.text.format.DateUtils.getRelativeTimeSpanString(at, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
}

/** What a picker cell has: colours of layers with ink, highlights or images; a typed note. */
private class Marks(val layerColors: List<Int>, val note: Boolean) {
    val any get() = layerColors.isNotEmpty() || note
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MarkerLegend() {
    // Wraps onto two lines on a phone rather than squeezing (PH-7).
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Text("Ink, highlights or images (one dot per visible layer)", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.EditNote, contentDescription = null, modifier = Modifier.size(14.dp))
            Text("Typed note", style = MaterialTheme.typography.bodySmall)
        }
    }
}

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
private fun GridCell(text: String, marks: Marks? = null, label: String = text, read: Boolean = false, onClick: () -> Unit) {
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
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            MarkerRow(marks, label)
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
            results = background {
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
                                label = { Text("${vm.bookLabel(b)} ${hits.size}") },
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
                                "${vm.bookLabel(b)} \u2014 ${hits.size}",
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
                                        // Tap to go there; hold a finger to open it in a new tab (TAB-3).
                                        .combinedClickable(
                                            onClick = { vm.goTo(panelIndex, hit.book, hit.chapter, hit.verse); onOpened() },
                                            onLongClick = { vm.newTab(hit.book, hit.chapter, hit.verse); onOpened() },
                                        )
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        vm.hitLabel(hit.book, hit.chapter, hit.verse),
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
// Verse: note, cross-references
// ---------------------------------------------------------------------------------------------

@Composable
fun VerseDialog(vm: StudyViewModel, t: VerseTarget, onDismiss: () -> Unit) {
    val id = VerseId.of(t.book, t.chapter, t.verse)
    val version = vm.activeVersion
    BigDialog(onDismiss) {
        Column {
            DialogTitle("${vm.refLabel(id)} ($version)", onDismiss, actions = { CopyVerseButton(vm, id, version) })
            // The note is saved when the window closes (the details leave the screen).
            VerseDetails(vm, t, version, inPanel = false, onDone = onDismiss, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Copies a verse (NOTE-7): its reference ("Hebrews 13:21"), or its words with the reference and
 * version after them, from a menu under a copy button.
 */
@Composable
internal fun CopyVerseButton(vm: StudyViewModel, id: Int, version: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy verse") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Copy reference") },
                onClick = {
                    open = false
                    clipboard.setText(AnnotatedString(vm.refLabel(id)))
                    vm.message = "Copied ${vm.refLabel(id)}."
                },
            )
            DropdownMenuItem(
                text = { Text("Copy verse") },
                onClick = {
                    open = false
                    val quote = vm.verseQuote(version, id)
                    if (quote == null) vm.message = "The verse is still loading. Try again in a moment."
                    else { clipboard.setText(AnnotatedString(quote)); vm.message = "Copied ${vm.refLabel(id)}." }
                },
            )
        }
    }
}

/**
 * What the Bible aids say about the word tapped (AID-9): a person or place, a custom or feast, a
 * symbol or number, each with *More* for the whole entry. Returns whether there was any.
 */
@Composable
private fun AidCards(vm: StudyViewModel, version: String, verseId: Int, verseText: String, word: Int, onOpen: () -> Unit): Boolean {
    val on = vm.aidSwitches()
    if (word < 0 || !(on.names || on.customs || on.symbols)) return false
    val found by produceState<List<Pair<com.biblestudy.app.data.AidMark, Any>>?>(null, version, verseId, verseText, word, on) {
        value = background { com.biblestudy.app.data.Aids.at(vm.getApplication(), vm.study, version, verseId, verseText, word, on) }
    }
    val list = found ?: return false
    if (list.isEmpty()) return false
    for ((_, what) in list) {
        val (title, label, text) = when (what) {
            is com.biblestudy.app.data.NameEntry -> Triple(what.name, if (what.place) "Place" else "Person", what.brief)
            is com.biblestudy.app.data.AidEntry -> Triple(what.title, what.group, what.text)
            else -> continue
        }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("aid"),
        ) {
            Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title) }
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("  $label") }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                TextButton(
                    onClick = {
                        when (what) {
                            is com.biblestudy.app.data.NameEntry -> vm.openName(what.id)
                            is com.biblestudy.app.data.AidEntry -> vm.openAid(what)
                        }
                        onOpen()
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("More") }
            }
        }
    }
    return true
}

/**
 * Points to Christ (AID-13): for a verse that points to Jesus, whether it's a prophecy or a picture
 * of Christ, what it says of him, and the New Testament passages as links ([onPassage]).
 */
@Composable
private fun ChristCard(vm: StudyViewModel, id: Int, onPassage: (com.biblestudy.app.data.Passage) -> Unit) {
    val e by produceState<com.biblestudy.app.data.ChristEntry?>(null, id) {
        value = background { com.biblestudy.app.data.Christ.at(vm.getApplication(), id) }
    }
    val entry = e ?: return
    Surface(
        color = CHRIST_LINE.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("christCard"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                "Points to Christ \u00b7 ${entry.kindLabel} \u00b7 ${entry.ref}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            Text(entry.note, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            StudyText(
                (if (entry.prophecy) "Fulfilled in " else "See ") + entry.fulfilled,
                onPassage = onPassage,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The meaning of the hard word tapped (STD-23), in one line, with *Read more* opening its Bible
 * dictionary article when there is one. Nothing when the word tapped isn't a hard word.
 */
@Composable
private fun HardWordCard(vm: StudyViewModel, version: String, verseText: String, word: Int, onOpen: () -> Unit) {
    if (!vm.hardWords || word < 0) return
    val hard = remember(version, verseText, word) {
        com.biblestudy.app.data.StudyRepository.words(verseText).getOrNull(word)
            ?.let { com.biblestudy.app.data.HardWords.lookup(vm.getApplication(), version, verseText.substring(it)) }
    } ?: return
    val article by produceState<Long?>(null, hard.term) {
        value = if (hard.term.isEmpty()) null else background { vm.study.dictionaryEntry(hard.term)?.id }
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("hardWord"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(hard.word) }
                    if (hard.oldWord) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(" (old English)") }
                    append(": ")
                    append(hard.meaning)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            article?.let { a -> TextButton(onClick = { vm.openDictionary(a); onOpen() }) { Text("Read more") } }
        }
    }
}

/** A typed note being written in [VerseDetails]; saved when the verse changes or it closes. */
private class NoteDraft(val book: Int, val chapter: Int, val start: Int, original: String, originalEnd: Int) {
    var text by mutableStateOf(original)
    var end by mutableStateOf(originalEnd)
    private var saved = original
    private var savedEnd = originalEnd

    fun save(vm: StudyViewModel) {
        if (text == saved && end == savedEnd) return
        vm.setNote(VerseTarget(book, chapter, start), text, end)
        saved = text; savedEnd = end
    }
}

/**
 * Everything about one verse (STD-3, STD-4, SPLIT-4, NOTE-1, LINK-5): the verse with its words to
 * study, the other versions or the Hebrew or Greek, the people and places in it, a typed note, and
 * its cross-references. Shown in the verse window, or in the Verse details panel ([inPanel], SPLIT-9),
 * where it all scrolls as one.
 *
 * @param onDone called after something here takes you elsewhere (a version, a person, a passage).
 */
@Composable
fun VerseDetails(vm: StudyViewModel, t: VerseTarget, version: String, inPanel: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val id = VerseId.of(t.book, t.chapter, t.verse)
    val verseText = remember(id, version, vm.onlineArrivals) { vm.verseTextNow(version, id) }
    // The note on this verse, or on a range of verses that includes it (NOTE-1).
    val draft = remember(id) {
        val existing = vm.user.noteCovering(t.book, t.chapter, t.verse)
        NoteDraft(t.book, t.chapter, existing?.verse ?: t.verse, existing?.text ?: "", existing?.endVerse ?: t.verse)
    }
    DisposableEffect(draft) { onDispose { draft.save(vm) } }
    if (inPanel) {
        // In a panel the note is kept as you type, in case the app is closed.
        LaunchedEffect(draft, draft.text, draft.end) { kotlinx.coroutines.delay(1500); draft.save(vm) }
    }
    val lastVerse = remember(t.book, t.chapter) { vm.bible.chapter(t.book, t.chapter).lastOrNull()?.verse ?: t.verse }
    val refs by produceState(emptyList<CrossRef>(), id, version) {
        // Previews in the version being read, also an online one (the cross-reference list itself is shared).
        vm.crossRefsIn(version, id) { value = it }
    }
    val panelIndex = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    var notePassage by remember(id) { mutableStateOf<Passage?>(null) }
    var noteOpen by remember(id) { mutableStateOf(false) }
    val noteFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // The commentary's note on this verse (STD-20), loaded once the Commentary tab is chosen.
    val cid = vm.lastCommentary
    val cInfo = com.biblestudy.app.data.Commentaries.info(cid)
    val note by produceState<VerseNote?>(null, cid, id, vm.verseCommentary) {
        value = null
        if (vm.verseCommentary) value = VerseNote(vm.commentaryOnVerse(cid, id))
    }

    fun done() { draft.save(vm); onDone() }

    @Composable
    fun CommentaryChoice() {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Choose a commentary" }) {
                    Text(cInfo.short, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (c in com.biblestudy.app.data.Commentaries.menu) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(c.short)
                                    Text("${c.covers} · ${c.years}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                            },
                            onClick = { menu = false; vm.chooseCommentary(c.id) },
                            leadingIcon = if (c.id == cid) { { Icon(Icons.Filled.Check, contentDescription = null) } } else null,
                        )
                    }
                }
            }
            // The whole chapter's commentary, beside the Bible text.
            if (!inPanel) TextButton(onClick = { done(); vm.openCommentaryBeside() }) { Text("Whole chapter beside the text") }
        }
        val n = note
        val status = when {
            !cInfo.covers(t.book) -> "${cInfo.short} covers the ${cInfo.covers}. Choose another commentary for ${vm.bible.book(t.book).name}."
            n == null -> if (com.biblestudy.app.data.Commentaries.isUnpacked(vm.getApplication(), cid)) "Loading…"
                else "Getting ${cInfo.short} ready (the first time only)…"
            n.section == null -> "${cInfo.short} has no notes on this verse. Try another commentary."
            else -> null
        }
        if (status != null) Text(status, modifier = Modifier.padding(vertical = 8.dp))
        n?.section?.let { s ->
            Text(
                commentaryHeading(vm, s, t.chapter),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    @Composable
    fun Top() {
        // A verse that points to Christ (AID-13): how, and where the New Testament fulfils it.
        if (vm.aidChrist && t.book < 40) ChristCard(vm, id) { notePassage = it }
        // A Bible aid tapped on the page (AID-9), or a hard word (STD-23): explained first.
        if (!AidCards(vm, version, id, verseText, t.word, onOpen = ::done)) HardWordCard(vm, version, verseText, t.word, onOpen = ::done)
        if (vm.compareVersions) {
            // Parallel view: the verse in every version, stacked (SPLIT-4). Tap one to read it.
            CompareVersions(vm, id, version, verseText, Modifier.heightIn(max = if (inPanel) 480.dp else 320.dp)) { code ->
                draft.save(vm)
                if (inPanel) {
                    vm.showBible(); vm.setVersion(panelIndex, code)
                } else {
                    vm.setVersion(panelIndex, code)
                    vm.goTo(panelIndex, t.book, t.chapter, t.verse, remember = false)
                    onDone()
                }
            }
        } else if (vm.originalView) {
            // The Hebrew or Greek, word by word (STD-4).
            OriginalVerse(vm, id, version)
        } else {
            // Each word with Hebrew or Greek behind it opens a word study (STD-3).
            StudyableVerse(vm, id, version, verseText)
        }
        // People and places in the verse open in the study pane (STD-10, STD-11).
        NamesInVerse(vm, id, onOpen = ::done)
        // The word tapped on the page, ready to study.
        val tapped by produceState<WordStudy?>(null, t, version) {
            value = if (t.word < 0) null else background {
                val strong = vm.study.strongs(version, id).getOrNull(t.word)
                val range = com.biblestudy.app.data.StudyRepository.words(verseText).getOrNull(t.word)
                if (strong != null && range != null) WordStudy(strong, version, verseText.substring(range), id) else null
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            // Bookmark the verse (NOTE-3): it shows a ribbon and is listed under Bookmarks in the book picker.
            val marked = vm.bookmarkAt(t.book, t.chapter, t.verse) != null
            FilterChip(
                selected = marked,
                onClick = { vm.toggleBookmark(t.book, t.chapter, t.verse) },
                label = { Text(if (marked) "Bookmarked" else "Bookmark") },
                leadingIcon = { Icon(if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("bookmarkChip"),
            )
            FilterChip(
                selected = vm.compareVersions,
                onClick = { vm.compareVersions = !vm.compareVersions; vm.originalView = false },
                label = { Text("Compare versions") },
            )
            FilterChip(
                selected = vm.originalView && !vm.compareVersions,
                onClick = { vm.originalView = !(vm.originalView && !vm.compareVersions); vm.compareVersions = false },
                label = { Text(if (t.book < 40) "Hebrew" else "Greek") },
                modifier = Modifier.testTag("originalChip"),
            )
            // Write at length about the verse on a full page (MRG-15).
            TextButton(onClick = { done(); vm.openNotePage(t.book, t.chapter, t.verse) }) { Text("Write full screen") }
            // Tags on this verse's note (NOTE-4).
            if (vm.notesFor(t.book, t.chapter)[draft.start]?.text?.isNotBlank() == true) TagButton(vm, vm.noteKey(t.book, t.chapter, draft.start))
            tapped?.let { w ->
                FilledTonalButton(onClick = { vm.openWordStudy(w) }) { Text("Word study: “${w.word}”") }
            }
            // Ask the AI chat about this verse (AI-6).
            if (vm.chat.enabled) TextButton(onClick = {
                draft.save(vm)
                vm.sendToChat("${vm.refLabel(id)} ($version)", verseText)
                if (!inPanel) onDone()
            }) { Text("Ask AI") }
        }
        // The typed note: a small button until there is one, so the commentary below has the room.
        if (draft.text.isBlank() && !noteOpen) {
            TextButton(onClick = { noteOpen = true }, modifier = Modifier.testTag("addNote")) {
                Icon(Icons.Filled.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add a note")
            }
        } else {
            OutlinedTextField(
                value = draft.text,
                onValueChange = { draft.text = it },
                label = { Text("Typed note (shows in every version)") },
                supportingText = { Text("References like Rom 8:28 or Psalm 23 become links.") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().focusRequester(noteFocus),
            )
            // Opened with Add a note: ready to type.
            LaunchedEffect(noteOpen) { if (noteOpen) runCatching { noteFocus.requestFocus() } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Note on " + vm.refLabel(VerseId.of(t.book, t.chapter, draft.start), VerseId.of(t.book, t.chapter, draft.end)),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = { draft.end-- }, enabled = draft.end > draft.start) {
                    Icon(Icons.Filled.Remove, contentDescription = "Note on one verse fewer")
                }
                IconButton(onClick = { draft.end++ }, enabled = draft.end < lastVerse) {
                    Icon(Icons.Filled.Add, contentDescription = "Note on one more verse")
                }
            }
        }
        // References typed in the note, as links to their passages (LINK-4).
        val noteLinks = remember(draft.text) { RefLinks.find(draft.text, vm.bible.books) }
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
        // Below: the cross-references, or what a commentary says on the verse (STD-20).
        // The commentary comes first: it helps most in understanding the verse.
        TabRow(selectedTabIndex = if (vm.verseCommentary) 0 else 1, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
            Tab(
                selected = vm.verseCommentary, onClick = { vm.showVerseCommentary(true) },
                text = { Text("Commentary") }, modifier = Modifier.testTag("verseCommentaryTab"),
            )
            Tab(
                selected = !vm.verseCommentary, onClick = { vm.showVerseCommentary(false) },
                text = { Text("Cross-references (${refs.size})") }, modifier = Modifier.testTag("verseRefsTab"),
            )
        }
        if (vm.verseCommentary) CommentaryChoice()
    }

    @Composable
    fun Paragraph(text: String) {
        StudyText(text, onPassage = { notePassage = it }, modifier = Modifier.padding(vertical = 4.dp).testTag("verseCommentary"))
    }

    @Composable
    fun Ref(r: CrossRef) {
        // A cross-reference opens the passage pop-over (LINK-5): read it here, then Go to or Open beside.
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

    @Composable
    fun Credit() {
        Text(
            when {
                !vm.verseCommentary -> "Cross-references: OpenBible.info (CC BY)"
                cid == com.biblestudy.app.data.Commentaries.AI -> "Written by AI from trusted sources. Check anything important against the other commentaries."
                else -> "${cInfo.name}, ${cInfo.author} (public domain)"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
    }

    notePassage?.let { p ->
        Popup(alignment = Alignment.Center, onDismissRequest = { notePassage = null }, properties = PopupProperties(focusable = true)) {
            PassageCard(
                vm, p, version,
                onGoTo = { notePassage = null; draft.save(vm); vm.openPassage(p, panelIndex, beside = false); if (!inPanel) onDone() },
                onOpenBeside = { notePassage = null; draft.save(vm); vm.openPassage(p, panelIndex, beside = true); if (!inPanel) onDone() },
                onClose = { notePassage = null },
            )
        }
    }
    val paragraphs = remember(note) { note?.section?.body?.split("\n\n")?.filter { it.isNotBlank() }.orEmpty() }
    if (inPanel) {
        LazyColumn(modifier.testTag("verseDetails")) {
            item(key = "top") { Column { Top() } }
            if (vm.verseCommentary) items(paragraphs.size, key = { "c$it" }) { Paragraph(paragraphs[it]) }
            else items(refs, key = { "r${it.toStart}-${it.toEnd}" }) { Ref(it) }
            item(key = "credit") { Credit() }
        }
    } else {
        Column(modifier) {
            Top()
            LazyColumn(Modifier.weight(1f)) {
                if (vm.verseCommentary) items(paragraphs) { Paragraph(it) } else items(refs) { Ref(it) }
            }
            Credit()
        }
    }
}

/** A commentary's note on a verse, once loaded: [section] is null when it has none. */
private class VerseNote(val section: com.biblestudy.app.data.CommentarySection?)

// ---------------------------------------------------------------------------------------------
// Layers
// ---------------------------------------------------------------------------------------------

@Composable
fun LayersDialog(vm: StudyViewModel, onExportLayer: (Long) -> Unit = {}, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    var savingView by remember { mutableStateOf(false) }
    var viewName by remember { mutableStateOf("") }
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
            // Saved views: which layers are shown, switched with one tap (LAY-10).
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Views:", style = MaterialTheme.typography.labelLarge)
                for (name in vm.layerPresets.keys.sorted()) {
                    InputChip(
                        selected = false,
                        onClick = { vm.applyLayerPreset(name) },
                        label = { Text(name) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Forget view $name",
                                modifier = Modifier.size(24.dp).clickable { vm.deleteLayerPreset(name) })
                        },
                    )
                }
                TextButton(onClick = { viewName = ""; savingView = true }) { Text("Save what\u2019s shown\u2026") }
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
                                    text = { Text("Export this chapter\u2019s ${l.name} as PDF\u2026") },
                                    onClick = { menu = false; onExportLayer(l.id) },
                                )
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

    if (savingView) {
        AlertDialog(
            onDismissRequest = { savingView = false },
            title = { Text("Save this view") },
            text = {
                Column {
                    Text("Remembers which layers are shown now, to switch back with one tap.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = viewName, onValueChange = { viewName = it }, singleLine = true,
                        placeholder = { Text("e.g. Sermon prep") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveLayerPreset(viewName); savingView = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { savingView = false }) { Text("Cancel") } },
        )
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
// My notes: typed notes, highlights and sketch pages
// ---------------------------------------------------------------------------------------------

@Composable
fun MyNotesDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    val panelIndex = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    // The notes browser (NOTE-5): typed notes and text boxes, highlights, and sketch pages.
    var tab by remember { mutableStateOf(0) }
    BigDialog(onDismiss) {
        Column {
            DialogTitle("My notes", onDismiss)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Notes") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Highlights") })
                FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("Sketch pages") })
            }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                0 -> NotesList(vm, panelIndex, onDismiss, Modifier.weight(1f))
                1 -> HighlightsList(vm, panelIndex, onDismiss, Modifier.weight(1f))
                else -> SketchList(vm, panelIndex, onDismiss, Modifier.weight(1f))
            }
        }
    }
}

/**
 * Every sketch page (SKT-2): yours, newest first, then the ready-made ones (SKT-5). Each shows the
 * verse it's linked to, or that it stands on its own. Tap one to open it.
 */
@Composable
private fun SketchList(vm: StudyViewModel, panelIndex: Int, onDismiss: () -> Unit, modifier: Modifier) {
    val mine = vm.sketches.filter { !it.readyMade }.sortedByDescending { it.created }
    val ready = vm.sketches.filter { it.readyMade }.sortedBy { it.created }
    val missing = SketchTemplates.all.size > ready.size
    Column(modifier) {
        LazyColumn(Modifier.weight(1f)) {
            item { MySketchesHeader(vm, Modifier.padding(top = 8.dp)) { made -> vm.openSketch(made, panelIndex); onDismiss() } }
            if (mine.isEmpty()) item {
                Text(
                    "None yet. Tap New sketch page to make one, linked to a verse or on its own.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(mine, key = { it.id }) { s -> SketchRow(vm, s) { vm.openSketch(s, panelIndex); onDismiss() } }
            item { Text("Ready-made pages", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp)) }
            items(ready, key = { it.id }) { s -> SketchRow(vm, s) { vm.openSketch(s, panelIndex); onDismiss() } }
            if (missing) item {
                TextButton(onClick = { vm.addReadyMadePages(announce = true) }) { Text("Put back deleted ready-made pages") }
            }
        }
    }
}

/**
 * "My sketch pages" with a button to make a new one (SKT-2), at the top of both lists of sketch pages.
 * [open] shows the new page the way tapping a page in that list would.
 */
@Composable
internal fun MySketchesHeader(vm: StudyViewModel, modifier: Modifier = Modifier, open: (com.biblestudy.app.model.Sketch) -> Unit) {
    var making by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("My sketch pages", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = { making = true }, modifier = Modifier.testTag("newSketchPage")) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("New sketch page")
        }
    }
    if (making) NewSketchDialog(vm, onDismiss = { making = false }, open = open)
}

/** A sketch page in a list: its name, the verse it's on (or "On its own"), paper and date. */
@Composable
internal fun SketchRow(vm: StudyViewModel, s: com.biblestudy.app.model.Sketch, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp).testTag("sketchRow")
    ) {
        Text(s.name, style = MaterialTheme.typography.titleMedium)
        val where = if (s.linked) "On " + vm.refLabel(VerseId.of(s.linkBook, s.linkChapter, s.linkVerse)) else "On its own"
        val detail = listOfNotNull(
            where, s.paper.label,
            if (s.readyMade) null else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(s.created)),
        )
        Text(detail.joinToString(" \u00b7 "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    HorizontalDivider()
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
                        vm.refLabel(VerseId.of(h.book, h.chapter, e.verse), VerseId.of(h.book, h.chapter, e.endVerse)) +
                            "  \u00b7  ${h.version}  \u00b7  $layerName",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // The whole verse, with the highlighted words in the highlight's colour.
                    val mark = SpanStyle(background = Color(h.color).copy(alpha = HIGHLIGHT_ALPHA))
                    Text(
                        buildAnnotatedString {
                            append(e.text)
                            val r = e.marked
                            if (r != null) addStyle(mark, r.first, r.last + 1)
                            else if (e.text == e.words) addStyle(mark, 0, e.text.length)
                        },
                        modifier = Modifier.testTag("highlightText"),
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
        title = { Text("Ink & Word \u2014 version ${BuildConfig.VERSION_NAME}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Works completely offline. Your notes stay on this tablet unless you make a backup.")
                Spacer(Modifier.height(12.dp))
                Text("Credits", style = MaterialTheme.typography.titleMedium)
                for (v in BibleRepository.ALL) Text("\u2022 ${v.name}: ${v.copyright}")
                Text("\u2022 Cross-references: OpenBible.info, licensed CC BY.")
                Text("\u2022 Hebrew and Greek dictionaries: Strong's (1890, public domain), from the Open Scriptures edition, licensed CC BY-SA.")
                Text("\u2022 Strong's numbers for each word: the KJV, BSB and WEB editions at eBible.org (public domain). Imported Bibles are tagged on the tablet by matching each word to these and to the Hebrew and Greek of its verse.")
                Text("\u2022 People and places: STEPBible.org TIPNR, licensed CC BY 4.0. Map outline: Natural Earth (public domain).")
                Text("\u2022 Hebrew and Greek word by word: STEPBible.org TAHOT and TAGNT (Tyndale House, Cambridge), licensed CC BY 4.0. Only the columns shown are kept.")
                Text("\u2022 Easton's Bible Dictionary (1897), Nave's Topical Bible (1896) and Matthew Henry's Concise Commentary: public domain, from the Christian Classics Ethereal Library.")
                Text("\u2022 Commentaries (public domain): Matthew Henry's Complete, Jamieson-Fausset-Brown, Wesley, the Geneva notes, Barnes, Adam Clarke, Keil & Delitzsch, Robertson's Word Pictures, Calvin and Spurgeon's Treasury of David, from the CrossWire Bible Society's SWORD library.")
                Text("\u2022 Writing sounds: \u201cPencil\u201d, \u201cMarker circle\u201d and \u201cDrawing\u201d by freesound_community, from Pixabay (Pixabay Content License).")
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
            BookIntroBody(vm, book, intro, Modifier.weight(1f), onOpen = ::open, onShow = { shown = it })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { open(Passage(book, 1, 1, 1, 1)) }) { Text("Read from chapter 1") }
            }
        }
        // Inside the window: a pop-up opened from outside it would sit behind it, out of sight.
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
}

/** A book's introduction (STD-12): facts, background, outline and key verses, in a window or a panel. */
@Composable
fun BookIntroBody(vm: StudyViewModel, book: Int, intro: com.biblestudy.app.data.BookIntro, modifier: Modifier = Modifier, onOpen: (Passage) -> Unit, onShow: (Passage) -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())) {
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

        /** Text with Bible references made into links (LINK-4 style), which can be written on (INK-16). */
        @Composable
        fun Linked(text: String, part: Int) {
            val links = remember(text) { RefLinks.find(text, vm.bible.books) }
            val annotated = buildAnnotatedString {
                append(text)
                for (l in links) {
            addLink(
                LinkAnnotation.Clickable("ref", TextLinkStyles(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline))) {
                    onShow(l.passage)
                },
                l.start, l.end,
            )
                }
            }
            InkableText(vm, InkDoc(StudyInk.INTRO, book, part), annotated, style = MaterialTheme.typography.bodyLarge)
        }

        Fact("Author", intro.author)
        Fact("Written", intro.date)
        Fact("Where", intro.place)
        Fact("Written to", intro.audience)
        Fact("Type", intro.type)

        Heading("Historical background")
        Linked(intro.background, 0)
        Heading("Purpose")
        Linked(intro.purpose, 1)
        Heading("Main themes")
        Linked(intro.themes, 3)

        Heading("Outline")
        for (o in intro.outline) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onOpen(o.passage) }.padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(o.title, modifier = Modifier.weight(1f))
                Text(vm.passageLabel(o.passage).removePrefix(vm.bible.book(book).name + " "), color = LINK_COLOR, style = MaterialTheme.typography.labelLarge)
            }
        }

        Heading("Key people")
        Linked(intro.people, 4)
        Heading("Key places")
        Linked(intro.places, 5)

        Heading("Key verses")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in intro.keyVerses) {
                AssistChip(
            onClick = { onShow(p) },
            label = { Text(vm.passageLabel(p)) },
            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
            }
        }

        Heading("Connections")
        Linked(intro.connections, 2)

        Text(
            "Authorship and dates follow the traditional view.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 16.dp),
        )
                }
}

/** The introduction to the book being read, beside the text (SPLIT-7). */
@Composable
fun BookIntroPane(vm: StudyViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val panel = vm.studyPanel()
    val book = panel.book.coerceIn(1, 66)
    val intro = remember(book) { BookIntros.get(context, book) }
    var shown by remember(book) { mutableStateOf<Passage?>(null) }
    val index = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    Column(modifier) {
        Text("About ${vm.bible.book(book).name}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        if (intro == null) { Text("No introduction for this book yet."); return@Column }
        BookIntroBody(vm, book, intro, Modifier.weight(1f), onOpen = { p -> vm.showBible(); vm.goTo(index, p.book, p.chapter, p.verse) }, onShow = { shown = it })
    }
    PassagePopupHost(vm, shown, panel.version, onDismiss = { shown = null })
}

/** The passage a cross-reference points to (LINK-5). */
fun CrossRef.passage() = Passage(
    VerseId.book(toStart), VerseId.chapter(toStart), VerseId.verse(toStart), VerseId.chapter(toEnd), VerseId.verse(toEnd),
)

/**
 * One verse in every version, stacked (SPLIT-4), with the words that differ from [version] lightly
 * marked (SPLIT-5). Used in the verse window and as a panel view (SPLIT-7).
 */
@Composable
fun CompareVersions(vm: StudyViewModel, id: Int, version: String, verseText: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        for (v in BibleRepository.ALL) {
            val text = remember(id, v, vm.onlineArrivals) { vm.text(v.code).verseText(id) }
            Column(Modifier.fillMaxWidth().clickable { onPick(v.code) }.padding(vertical = 4.dp)) {
                Text(
                    v.code + if (v.code == version) "  (reading)" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                val shown = remember(text, verseText, v) {
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(text ?: "Not in this version (see its footnotes).")
                        if (text != null && v.code != version && verseText.isNotEmpty()) {
                            for (r in com.biblestudy.app.data.WordDiff.changed(text, verseText)) {
                                addStyle(androidx.compose.ui.text.SpanStyle(background = DIFF_MARK), r.first, r.last + 1)
                            }
                        }
                    }
                }
                Text(
                    shown,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (text == null) MaterialTheme.colorScheme.outline else Color.Unspecified,
                    modifier = Modifier.testTag("compare_${v.code}"),
                )
            }
        }
        Text("Words that differ from the $version are marked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}
