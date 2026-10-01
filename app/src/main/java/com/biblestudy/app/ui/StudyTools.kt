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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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

/** Study text (dictionary, topic or commentary) with its links. */
@Composable
fun StudyText(body: String, onPassage: (Passage) -> Unit, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val text = remember(body) { studyAnnotated(body, onPassage) }
    Text(text, modifier, style = style)
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
                addLink(LinkAnnotation.Clickable("w$i", style) { vm.wordStudy = WordStudy(s, version, word, verseId) }, r.first, r.last + 1)
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
    BigDialog(onDismiss) {
        Column(Modifier.testTag("wordStudy")) {
            DialogTitle(
                "Word study" + (start.word?.takeIf { trail.size == 1 }?.let { ": “$it”" } ?: ""),
                onDismiss,
                leading = if (trail.size > 1) {
                    { IconButton(onClick = { trail.removeAt(trail.lastIndex) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
                } else null,
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
) {
    var query by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<Passage?>(null) }
    val version = vm.panels[vm.activePanel.coerceIn(0, vm.panels.lastIndex)].version
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
                StudyText(e.body, onPassage = { shown = it })
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
    val panel = vm.panels[vm.activePanel.coerceIn(0, vm.panels.lastIndex)]
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
    )
}

/** Nave's Topical Bible (STD-6), suggesting the topics that list the verse being read. */
@Composable
fun TopicsPane(vm: StudyViewModel, modifier: Modifier) {
    val panel = vm.panels[vm.activePanel.coerceIn(0, vm.panels.lastIndex)]
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
    )
}

/** Matthew Henry's Concise Commentary on the chapter being read (STD-7), at the verse in view. */
@Composable
fun CommentaryPane(vm: StudyViewModel, modifier: Modifier) {
    val panel = vm.panels[vm.activePanel.coerceIn(0, vm.panels.lastIndex)]
    val sections by produceState<List<CommentarySection>?>(null, panel.book, panel.chapter) {
        value = withContext(Dispatchers.IO) { vm.study.commentary(panel.book, panel.chapter) }
    }
    var shown by remember { mutableStateOf<Passage?>(null) }
    PassagePopupHost(vm, shown, panel.version, onDismiss = { shown = null })
    val state = rememberLazyListState()
    val t = vm.paneVerse?.takeIf { it.book == panel.book && it.chapter == panel.chapter }
    val verse = t?.verse ?: panel.topVerse
    val list = sections
    // Keep the section about the verse being read in view.
    LaunchedEffect(list, verse) {
        val i = list?.indexOfLast { VerseId.verse(it.start) <= verse || VerseId.chapter(it.start) < panel.chapter } ?: -1
        if (i >= 0) state.animateScrollToItem(i)
    }
    Column(modifier) {
        Text(
            "Matthew Henry · ${vm.bible.book(panel.book).name} ${panel.chapter}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        when {
            list == null -> Text("Loading…")
            list.isEmpty() -> Text("Matthew Henry's Concise Commentary has no comment on this chapter.")
        }
        LazyColumn(Modifier.weight(1f).testTag("commentary"), state = state) {
            items(list.orEmpty(), key = { "${it.start}-${it.end}" }) { s ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "Verses " + vm.refLabel(s.start, s.end).substringAfter(':').let { r ->
                            if (VerseId.chapter(s.start) != VerseId.chapter(s.end) || VerseId.chapter(s.start) != panel.chapter) vm.refLabel(s.start, s.end) else r
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    StudyText(s.body, onPassage = { shown = it })
                }
                HorizontalDivider()
            }
        }
    }
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
        OutlinedButton(onClick = { vm.wordStudy = WordStudy(entry.id, version) }) { Text("Word study") }
    }
}
