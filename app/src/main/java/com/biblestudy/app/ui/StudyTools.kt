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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.biblestudy.app.data.CommentarySection
import com.biblestudy.app.data.LexEntry
import com.biblestudy.app.data.Occurrence
import com.biblestudy.app.data.Passage
import com.biblestudy.app.data.RefLinks
import com.biblestudy.app.data.StudyEntry
import com.biblestudy.app.data.StudyRepository
import com.biblestudy.app.model.VerseId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A word to study (STD-3): its Strong's number, the version it was tapped in, and the English word. */
data class WordStudy(val strong: String, val version: String, val word: String? = null, val verseId: Int = 0)

private val STUDY_LINK = Regex("\\[\\[(\\d+)-(\\d+)\\|([^\\]]*)]]")

/** The passage a study-text link points to (within one book). */
private fun linkPassage(start: Int, end: Int): Passage {
    val b = VerseId.book(start)
    val e = if (VerseId.book(end) == b) end else VerseId.of(b, 999, 999)
    return Passage(b, VerseId.chapter(start), VerseId.verse(start).coerceAtLeast(1), VerseId.chapter(e), VerseId.verse(e))
}

/**
 * Text from the study library: paragraphs, with scripture references written as
 * [[start-end|label]] turned into links that open the passage pop-over.
 */
fun studyAnnotated(body: String, onPassage: (Passage) -> Unit): AnnotatedString = buildAnnotatedString {
    var pos = 0
    val style = TextLinkStyles(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline))
    for (m in STUDY_LINK.findAll(body)) {
        append(body.substring(pos, m.range.first))
        val p = linkPassage(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        val label = m.groupValues[3]
        val at = length
        append(label)
        addLink(LinkAnnotation.Clickable("ref", style) { onPassage(p) }, at, length)
        pos = m.range.last + 1
    }
    append(body.substring(pos))
}

/** Study text (dictionary, topic or commentary) with its links; with [vm] and [doc], it can be written on (INK-16). */
@Composable
fun StudyText(
    body: String,
    onPassage: (Passage) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    vm: StudyViewModel? = null,
    doc: InkDoc? = null,
) {
    val text = remember(body) { studyAnnotated(body, onPassage) }
    if (vm != null && doc != null) InkableText(vm, doc, text, modifier, style) else Text(text, modifier, style = style)
}

/** A passage pop-over (LINK-2) for links in study text; [onNavigate] runs after Go to / Open beside. */
@Composable
fun PassagePopupHost(vm: StudyViewModel, passage: Passage?, version: String, onDismiss: () -> Unit, onNavigate: () -> Unit = {}) {
    val p = passage ?: return
    val index = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        PassageCard(
            vm, p, version,
            onGoTo = { vm.openPassage(p, index, beside = false); onDismiss(); onNavigate() },
            onOpenBeside = { vm.openPassage(p, index, beside = true); onDismiss(); onNavigate() },
            onClose = onDismiss,
        )
    }
}

/**
 * The verse text in the verse window with each word that has a Hebrew or Greek word behind it
 * tappable for a word study (STD-3).
 */
@Composable
fun StudyableVerse(vm: StudyViewModel, verseId: Int, version: String, text: String) {
    val strongs by produceState(emptyList<String?>(), verseId, version) {
        value = withContext(Dispatchers.IO) { vm.study.strongs(version, verseId) }
    }
    val ranges = remember(text) { StudyRepository.words(text) }
    val annotated = remember(text, strongs) {
        buildAnnotatedString {
            append(text)
            val style = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.None))
            for ((i, r) in ranges.withIndex()) {
                val s = strongs.getOrNull(i) ?: continue
                val word = text.substring(r)
                addLink(LinkAnnotation.Clickable("w$i", style) { vm.openWordStudy(WordStudy(s, version, word, verseId)) }, r.first, r.last + 1)
            }
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("studyVerse"))
    if (strongs.any { it != null }) {
        Text(
            "Tap a word to study the ${if (verseId < StudyRepository.NT_START) "Hebrew" else "Greek"} behind it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Word study (STD-3, STD-8): the original word, how it's said, its meaning from Strong's, how the
 * KJV translates it, and every verse that uses it in the version being read, by book.
 */
@Composable
fun WordStudyDialog(vm: StudyViewModel, start: WordStudy, onDismiss: () -> Unit) {
    BigDialog(onDismiss) {
        Column(Modifier.testTag("wordStudy")) {
            WordStudyBody(vm, start, onDismiss) { title, back ->
                DialogTitle(title, onDismiss, leading = back?.let { b ->
                    { IconButton(onClick = b) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
                })
            }
        }
    }
}

/** A word study (STD-3, STD-8) in a panel beside the text (SPLIT-7): the word last tapped. */
@Composable
fun WordStudyPane(vm: StudyViewModel, modifier: Modifier, w: WordStudy? = vm.studyWord, onBack: (() -> Unit)? = null) {
    Column(modifier.testTag("wordStudyPane")) {
        if (w == null) {
            Text(
                "Tap a word in the Bible, or a Hebrew or Greek word, and its word study shows here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            return@Column
        }
        key(w) {
            WordStudyBody(vm, w, onDismiss = { onBack?.invoke() }) { title, back ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    else if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the verse") }
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

/** The word study itself, in a window or a panel; [header] shows its title and a Back button. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.WordStudyBody(
    vm: StudyViewModel,
    start: WordStudy,
    onDismiss: () -> Unit,
    header: @Composable (String, (() -> Unit)?) -> Unit,
) {
    // Following "from G25" links keeps a trail to come back along.
    val trail = remember(start) { mutableStateListOf(start.strong) }
    val strong = trail.last()
    val version = start.version
    val entry by produceState<LexEntry?>(null, strong) { value = withContext(Dispatchers.IO) { vm.study.lexicon(strong) } }
    val uses by produceState<List<Occurrence>?>(null, strong, version) {
        value = null
        value = withContext(Dispatchers.IO) { vm.study.occurrences(version, strong, { vm.text(version).verseText(it) }) }
    }
    var onlyBook by remember(strong) { mutableStateOf<Int?>(null) }
    val panelIndex = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    // A dictionary article for the English word, if Easton's has one.
    val article by produceState<StudyEntry?>(null, start.word) {
        value = start.word?.let { w -> withContext(Dispatchers.IO) { vm.study.dictionaryEntry(w) ?: vm.study.dictionaryEntry(w.trimEnd('s')) } }
    }

    val person by produceState<com.biblestudy.app.data.NameEntry?>(null, strong, start.verseId) {
        value = withContext(Dispatchers.IO) { vm.study.nameForStrong(strong, start.verseId) }
    }
    run {
        run {
            header(
                "Word study" + (start.word?.takeIf { trail.size == 1 }?.let { ": “$it”" } ?: ""),
                if (trail.size > 1) { { trail.removeAt(trail.lastIndex) } } else null,
            )
            val e = entry
            if (e == null) {
                Text("Loading…")
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(e.lemma, style = TextStyle(fontSize = 34.sp), modifier = Modifier.testTag("lemma"))
                    Text(e.xlit, style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    listOfNotNull(e.pron.takeIf { it.isNotBlank() }?.let { "say “$it”" }, "${e.language} · Strong's ${e.id}").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text("Meaning", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Text(e.def.trim().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                if (e.derivation.isNotBlank()) {
                    // Strong's numbers in "from G25 (ἀγαπάω)" open that word.
                    val deriv = remember(e.derivation) {
                        buildAnnotatedString {
                            append("Comes ")
                            var pos = 0
                            for (m in Regex("[HG]\\d+").findAll(e.derivation)) {
                                append(e.derivation.substring(pos, m.range.first))
                                val at = length
                                append(m.value)
                                addLink(
                                    LinkAnnotation.Clickable(m.value, TextLinkStyles(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline))) {
                                        trail.add(m.value)
                                    },
                                    at, length,
                                )
                                pos = m.range.last + 1
                            }
                            append(e.derivation.substring(pos))
                        }
                    }
                    Text(deriv, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
                if (e.kjv.isNotBlank()) {
                    Text("Translated in the KJV as", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    Text(e.kjv.trimEnd('.'), style = MaterialTheme.typography.bodyMedium)
                }
                // A name: who or where it is (STD-10, STD-11).
                person?.let { n ->
                    OutlinedButton(
                        onClick = { vm.openName(n.id); vm.verseSheet = null; onDismiss() },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("About ${n.name} (${if (n.place) "place" else "person"})") }
                }
                article?.let { a ->
                    OutlinedButton(
                        onClick = { vm.openDictionary(a.id); onDismiss() },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("“${a.title}” in Easton's Bible Dictionary") }
                }
            }

            // Concordance: every use, with counts per book (STD-8).
            val list = uses
            val byBook = remember(list) { list.orEmpty().groupBy { VerseId.book(it.id) } }
            Text(
                when {
                    list == null -> "Finding every use…"
                    list.isEmpty() -> "Not found in the $version."
                    else -> "Used in ${list.size} verse${if (list.size == 1) "" else "s"} of the $version" +
                        ", in ${byBook.size} book${if (byBook.size == 1) "" else "s"}"
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).testTag("useCount"),
            )
            if (byBook.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = onlyBook == null, onClick = { onlyBook = null }, label = { Text("All") })
                    for ((b, hits) in byBook) {
                        FilterChip(
                            selected = onlyBook == b,
                            onClick = { onlyBook = if (onlyBook == b) null else b },
                            label = { Text("${vm.bible.book(b).name} ${hits.size}") },
                        )
                    }
                }
            }
            val mark = SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x55FFE600))
            LazyColumn(Modifier.weight(1f)) {
                items(list.orEmpty().filter { onlyBook == null || VerseId.book(it.id) == onlyBook }, key = { it.id }) { o ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            vm.goTo(panelIndex, VerseId.book(o.id), VerseId.chapter(o.id), VerseId.verse(o.id))
                            vm.verseSheet = null
                            onDismiss()
                        }.padding(vertical = 8.dp)
                    ) {
                        Text(vm.refLabel(o.id), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(buildAnnotatedString {
                            append(o.text)
                            for (r in o.words) addStyle(mark, r.first, r.last + 1)
                        })
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** A list of titles to pick from, or one article; used by the dictionary and topics panes. */
@Composable
private fun LookupPane(
    vm: StudyViewModel,
    modifier: Modifier,
    hint: String,
    open: Long?,
    onOpen: (Long?) -> Unit,
    load: (Long) -> StudyEntry?,
    search: (String) -> List<StudyEntry>,
    suggestions: List<StudyEntry>,
    suggestionsLabel: String?,
    inkBook: Int,
) {
    var query by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<Passage?>(null) }
    val version = vm.studyPanel().version
    PassagePopupHost(vm, shown, version, onDismiss = { shown = null })
    Column(modifier) {
        val entry by produceState<StudyEntry?>(null, open) { value = open?.let { withContext(Dispatchers.IO) { load(it) } } }
        val e = entry
        if (open != null && e != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onOpen(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the list") }
                Text(e.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                StudyText(e.body, onPassage = { shown = it }, vm = vm, doc = InkDoc(inkBook, e.id.toInt()))
            }
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text(hint) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        val results by produceState(emptyList<StudyEntry>(), query) {
            value = if (query.isBlank()) emptyList() else withContext(Dispatchers.IO) { search(query) }
        }
        val list = if (query.isBlank()) suggestions else results
        if (query.isBlank() && suggestionsLabel != null && suggestions.isNotEmpty()) {
            Text(suggestionsLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        }
        if (query.isNotBlank() && list.isEmpty()) Text("Nothing found.", modifier = Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.id }) { s ->
                Text(
                    s.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(s.id) }.padding(vertical = 10.dp).testTag("entry"),
                )
                HorizontalDivider()
            }
        }
    }
}

/** Easton's Bible Dictionary (STD-5), suggesting names and words from the chapter being read. */
@Composable
fun DictionaryPane(vm: StudyViewModel, modifier: Modifier) {
    val panel = vm.studyPanel()
    val suggestions by produceState(emptyList<StudyEntry>(), panel.book, panel.chapter, panel.version) {
        value = withContext(Dispatchers.IO) { vm.chapterArticles(panel.version, panel.book, panel.chapter) }
    }
    LookupPane(
        vm, modifier,
        hint = "Look up a name, place or word",
        open = vm.dictionaryOpen,
        onOpen = { vm.dictionaryOpen = it },
        load = { vm.study.dictionaryById(it) },
        search = { vm.study.dictionarySearch(it) },
        suggestions = suggestions,
        suggestionsLabel = "In ${vm.bible.book(panel.book).name} ${panel.chapter}",
        inkBook = StudyInk.DICTIONARY,
    )
}

/** Nave's Topical Bible (STD-6), suggesting the topics that list the verse being read. */
@Composable
fun TopicsPane(vm: StudyViewModel, modifier: Modifier) {
    val panel = vm.studyPanel()
    val t = vm.paneVerse?.takeIf { it.book == panel.book && it.chapter == panel.chapter }
    val verse = t?.verse ?: panel.topVerse
    val id = VerseId.of(panel.book, panel.chapter, verse)
    val suggestions by produceState(emptyList<StudyEntry>(), id) {
        value = withContext(Dispatchers.IO) { vm.study.topicsFor(id) }
    }
    LookupPane(
        vm, modifier,
        hint = "Find a topic, e.g. Prayer or Faith",
        open = vm.topicOpen,
        onOpen = { vm.topicOpen = it },
        load = { vm.study.topicById(it) },
        search = { vm.study.topicSearch(it) },
        suggestions = suggestions,
        suggestionsLabel = "Topics for ${vm.refLabel(id)}",
        inkBook = StudyInk.TOPIC,
    )
}

/** One line of a commentary panel: a section's heading, or one of its paragraphs. */
private data class CommentaryLine(val section: Int, val para: Int, val text: String)

/**
 * A commentary on the chapter being read (STD-7, STD-17): the one chosen for this panel, with its
 * introduction (STD-19) and, when linked, scrolling together with the Bible panel both ways (STD-18).
 */
@Composable
fun CommentaryPane(vm: StudyViewModel, pos: Int, modifier: Modifier) {
    val panel = vm.studyPanel()
    val id = vm.commentaryAt(pos)
    val info = com.biblestudy.app.data.Commentaries.info(id)
    val canLink = vm.tab.pinned == null && !vm.tab.bibleHidden
    val linked = vm.commentaryLinked(pos) && canLink
    val sections by produceState<List<CommentarySection>?>(null, id, panel.book, panel.chapter) {
        value = null
        value = vm.loadCommentary(id, panel.book, panel.chapter)
    }
    var shown by remember { mutableStateOf<Passage?>(null) }
    PassagePopupHost(vm, shown, panel.version, onDismiss = { shown = null })
    var about by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    val list = sections
    // Paragraphs are separate lines, so long notes (Matthew Henry, Spurgeon) stay quick to show.
    val lines = remember(list) {
        list.orEmpty().flatMapIndexed { si, s ->
            listOf(CommentaryLine(si, -1, "")) + s.body.split("\n\n").filter { it.isNotBlank() }.mapIndexed { pi, t -> CommentaryLine(si, pi, t) }
        }
    }
    val firstLine = remember(lines) { lines.withIndex().filter { it.value.para == -1 }.associate { it.value.section to it.index } }
    val t = vm.paneVerse?.takeIf { it.book == panel.book && it.chapter == panel.chapter }
    val verse = t?.verse ?: panel.topVerse
    // The section about a verse: the last one starting at or before it in this chapter.
    fun sectionFor(v: Int): Int = list?.indexOfLast { s ->
        VerseId.chapter(s.start) < panel.chapter || (VerseId.chapter(s.start) == panel.chapter && VerseId.verse(s.start) <= v)
    } ?: -1
    var moving by remember { mutableStateOf(false) }
    // Bible to commentary: keep the note on the verse being read at the top.
    LaunchedEffect(lines, verse, linked) {
        if (!linked && state.firstVisibleItemIndex > 0) return@LaunchedEffect
        val target = sectionFor(verse).takeIf { it >= 0 } ?: return@LaunchedEffect
        val at = lines.getOrNull(state.firstVisibleItemIndex)?.section
        if (at == target) return@LaunchedEffect
        moving = true
        try { state.animateScrollToItem(firstLine[target] ?: 0) } finally { moving = false }
    }
    // Commentary to Bible: scrolling the commentary by hand (a drag and the glide after it) brings
    // the Bible to the verses it's on. Scrolling done here to follow the Bible never leads.
    var byHand by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) byHand = true }
    }
    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.isScrollInProgress }.collect { if (!it) byHand = false }
    }
    LaunchedEffect(state, lines, linked) {
        if (!linked) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex }.collect { i ->
            if (moving || !byHand) return@collect
            val s = lines.getOrNull(i)?.let { list?.getOrNull(it.section) } ?: return@collect
            if (VerseId.chapter(s.start) != panel.chapter || VerseId.verse(s.start) == 0) return@collect
            if (sectionFor(verse) == lines[i].section) return@collect
            vm.followCommentary(panel.book, panel.chapter, VerseId.verse(s.start))
        }
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            var menu by remember { mutableStateOf(false) }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                androidx.compose.material3.TextButton(
                    onClick = { menu = true },
                    modifier = Modifier.semantics { contentDescription = "Choose a commentary" },
                ) {
                    Text(info.short, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (c in com.biblestudy.app.data.Commentaries.menu) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Column {
                                    Text(c.short)
                                    Text("${c.covers} · ${c.years}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                            },
                            onClick = { menu = false; vm.setCommentary(pos, c.id) },
                            leadingIcon = if (c.id == id) { { Icon(Icons.Filled.Check, contentDescription = null) } } else null,
                        )
                    }
                }
            }
            IconButton(onClick = { about = true }) {
                Icon(Icons.Outlined.Info, contentDescription = "About this commentary")
            }
            if (canLink) {
                IconButton(onClick = { vm.toggleCommentaryLink(pos) }) {
                    Icon(
                        if (linked) Icons.Filled.Link else Icons.Filled.LinkOff,
                        contentDescription = if (linked) "Unlink from the Bible" else "Link to the Bible",
                        tint = if (linked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        Text(
            "${vm.bible.book(panel.book).name} ${panel.chapter}" + if (linked) " · scrolls with the Bible" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when {
            list == null -> Text(
                if (com.biblestudy.app.data.Commentaries.isUnpacked(vm.getApplication(), id)) "Loading…"
                else "Getting ${info.short} ready (the first time only)…",
            )
            !info.covers(panel.book) -> Text("${info.short} covers the ${info.covers}. Choose another commentary for ${vm.bible.book(panel.book).name}.")
            list.isEmpty() -> Text("${info.short} has no notes on this chapter.")
        }
        val inkBook = vm.commentaryInkBook(id)
        LazyColumn(Modifier.weight(1f).testTag("commentary"), state = state) {
            items(lines.size, key = { "${lines[it].section}-${lines[it].para}" }) { li ->
                val line = lines[li]
                val s = list?.getOrNull(line.section) ?: return@items
                if (line.para == -1) {
                    if (li > 0) HorizontalDivider(Modifier.padding(top = 6.dp))
                    Text(
                        commentaryHeading(vm, s, panel.chapter),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                } else {
                    // Writing stays with its note and paragraph (INK-16); the Concise keeps its 1.2 key.
                    val doc = if (id == com.biblestudy.app.data.Commentaries.CONCISE) InkDoc(StudyInk.COMMENTARY, s.start, s.end)
                    else InkDoc(inkBook, s.start, line.para)
                    StudyText(line.text, onPassage = { shown = it }, modifier = Modifier.padding(vertical = 4.dp), vm = vm, doc = doc)
                }
            }
        }
    }
    if (about) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { about = false },
            title = { Text(info.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${info.author} · ${info.years} · ${info.covers}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    for ((k, v) in info.about) {
                        Text(k, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(if (info.id == com.biblestudy.app.data.Commentaries.AI) "Written for this app. Each note names its sources." else "Public domain.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 10.dp))
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { about = false }) { Text("Close") } },
        )
    }
}

/** "Verses 14–16", "Introduction to Romans" or "Introduction to chapter 3". */
internal fun commentaryHeading(vm: StudyViewModel, s: CommentarySection, chapter: Int): String = when {
    VerseId.chapter(s.start) == 0 -> "Introduction to ${vm.bible.book(VerseId.book(s.start)).name}"
    VerseId.verse(s.start) == 0 -> "Introduction to chapter ${VerseId.chapter(s.start)}"
    VerseId.chapter(s.start) != VerseId.chapter(s.end) || VerseId.chapter(s.start) != chapter -> vm.refLabel(s.start, s.end)
    s.start == s.end -> "Verse " + VerseId.verse(s.start)
    else -> "Verses " + vm.refLabel(s.start, s.end).substringAfter(':')
}

/**
 * Topics and related passages for a verse (STD-9, STD-2), shown under its cross-references:
 * the parallel accounts listed under its BSB section heading, then passages that share its topics.
 */
@Composable
fun RelatedPassages(vm: StudyViewModel, verseId: Int, version: String, onShow: (Passage) -> Unit) {
    val related by produceState<Pair<List<Passage>, List<Triple<Int, Int, Int>>>?>(null, verseId) {
        value = withContext(Dispatchers.IO) { vm.parallelAccounts(verseId) to vm.study.relatedByTopics(verseId) }
    }
    val topics by produceState(emptyList<StudyEntry>(), verseId) { value = withContext(Dispatchers.IO) { vm.study.topicsFor(verseId, 8) } }
    Column(Modifier.padding(top = 12.dp)) {
        if (topics.isNotEmpty()) {
            Text("Topics", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in topics) SuggestionChip(onClick = { vm.openTopic(t.id) }, label = { Text(t.title) })
            }
        }
        val r = related ?: return@Column
        if (r.first.isNotEmpty()) {
            Text("Parallel accounts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            for (p in r.first) {
                Text(
                    vm.passageLabel(p),
                    color = LINK_COLOR,
                    modifier = Modifier.fillMaxWidth().clickable { onShow(p) }.padding(vertical = 6.dp),
                )
            }
        }
        if (r.second.isNotEmpty()) {
            Text("Related passages", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text("Listed under the same topics in Nave's Topical Bible", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            for ((s, e, k) in r.second) {
                Row(Modifier.fillMaxWidth().clickable { onShow(linkPassage(s, e)) }.padding(vertical = 6.dp)) {
                    Text(vm.refLabel(s, e), color = LINK_COLOR, modifier = Modifier.weight(1f))
                    Text("$k topics", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        if (topics.isEmpty() && r.first.isEmpty() && r.second.isEmpty()) {
            Text("No related passages found for this verse.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A Strong's search result header (SRCH-7): the word searched for, with a way into its word study. */
@Composable
fun StrongsHeader(vm: StudyViewModel, strong: String, version: String) {
    val e by produceState<LexEntry?>(null, strong) { value = withContext(Dispatchers.IO) { vm.study.lexicon(strong) } }
    val entry = e ?: return
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 22.sp)) { append(entry.lemma) }
                append("  ${entry.xlit} · ${entry.id}")
            })
            Text(entry.def.trim(), maxLines = 2, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { vm.openWordStudy(WordStudy(entry.id, version)) }) { Text("Word study") }
    }
}
