package com.biblestudy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biblestudy.app.data.SmartResult
import com.biblestudy.app.data.SmartVerse
import com.biblestudy.app.data.Passage
import com.biblestudy.app.model.VerseId

/**
 * Smart search results (section 29): the passages that matter most, then the Nave's subtopics
 * or the steps of a person's life, reflections, and every verse found. [texts] holds each verse's
 * words in the version being read; verses without text (not in this version) are left out.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SmartResults(
    vm: StudyViewModel,
    r: SmartResult,
    texts: Map<Int, String>,
    version: String,
    panelIndex: Int,
    onOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = remember(r) { mutableStateMapOf<String, Boolean>() }
    var shownAll by remember(r) { mutableStateOf(ALL_PAGE) }
    val matchStyle = SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x55FFE600))
    val all = remember(r, texts) { r.verses.filter { it.id in texts }.sortedBy { it.id } }

    @Composable
    fun VerseRow(id: Int, why: List<String>) {
        val text = texts[id] ?: return
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    // Tap to go there; hold a finger to open it in a new tab (TAB-3).
                    .combinedClickable(
                        onClick = { vm.goTo(panelIndex, VerseId.book(id), VerseId.chapter(id), VerseId.verse(id)); onOpened() },
                        onLongClick = { vm.newTab(VerseId.book(id), VerseId.chapter(id), VerseId.verse(id)); onOpened() },
                    )
                    .padding(vertical = 8.dp)
            ) {
                Text(vm.hitLabel(VerseId.book(id), VerseId.chapter(id), VerseId.verse(id)),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(markTerms(text, r.forms, matchStyle))
                if (why.isNotEmpty()) {
                    Text(why.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
            IconButton(onClick = {
                val (b, c, v) = Triple(VerseId.book(id), VerseId.chapter(id), VerseId.verse(id))
                vm.openPassage(Passage(b, c, v, c, v), panelIndex, beside = true)
                onOpened()
            }) { Icon(Icons.Filled.VerticalSplit, contentDescription = "Open beside") }
        }
        HorizontalDivider()
    }

    fun LazyListScope.section(key: String, title: String, count: Int?, foldable: Boolean) {
        item(key = "h-$key") {
            val isOpen = !foldable || open[key] == true
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .then(if (foldable) Modifier.clickable { open[key] = !isOpen } else Modifier)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .semantics { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title + (count?.let { " — $it" } ?: ""), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (foldable) Icon(if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isOpen) "Fold" else "Unfold")
            }
        }
    }

    LazyColumn(modifier.testTag("smartResults")) {
        // What was searched (SRCH-14): the topic, person or place, and the Hebrew and Greek words.
        item(key = "plan") {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    "${all.size} verse" + (if (all.size == 1) "" else "s") + " found in every version",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (r.lemmas.isNotEmpty()) {
                    Text("Hebrew and Greek words, wherever they're used:", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (l in r.lemmas.take(8)) {
                            AssistChip(onClick = { vm.openWordStudy(WordStudy(l.strong, version)) }, label = { Text("${l.xlit} · ${l.strong}") })
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.name?.let { n ->
                        AssistChip(onClick = { vm.openName(n.id); onOpened() }, label = { Text("${n.name} in Names & places") })
                    }
                    r.topic?.let { t ->
                        AssistChip(onClick = { vm.openTopic(t.id); onOpened() }, label = { Text("Nave's: ${t.title}") })
                    }
                    for (p in r.pages) {
                        val name = SketchTemplates.all.firstOrNull { it.id == p }?.name ?: continue
                        AssistChip(onClick = { if (vm.openReadyMade(p)) onOpened() }, label = { Text("Ready-made page: $name") })
                    }
                }
                r.name?.brief?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // The passages that matter most (TOP-5).
        val key = r.key.filter { it.id in texts }
        if (key.isNotEmpty()) {
            section("key", "Key passages", null, foldable = false)
            items(key, key = { "k${it.id}" }) { VerseRow(it.id, it.reasons) }
        }

        // Nave's subtopics, or a person's life in order (TOP-6, PPL-1).
        if (r.groups.isNotEmpty()) {
            val title = if (r.name != null) "The life of ${r.name.name}, step by step" else "By subject (Nave's Topical Bible)"
            item(key = "groups") {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            }
            r.groups.forEachIndexed { i, g ->
                val ids = g.ids.filter { it in texts }
                if (ids.isEmpty()) return@forEachIndexed
                section("g$i", g.label, ids.size, foldable = true)
                if (open["g$i"] == true) items(ids.take(MAX_GROUP), key = { "g$i-$it" }) { VerseRow(it, emptyList()) }
            }
        }

        // Reflections on the same thing (SRCH-42).
        if (r.reflections.isNotEmpty()) {
            section("refl", "Reflections", r.reflections.size, foldable = true)
            if (open["refl"] == true) items(r.reflections, key = { "r${it.start}-${it.title}" }) { ref ->
                Column(
                    Modifier.fillMaxWidth().clickable { vm.openReflection(ref.start); onOpened() }.padding(vertical = 8.dp)
                ) {
                    Text(ref.title, style = MaterialTheme.typography.bodyLarge)
                    Text(vm.hitLabel(VerseId.book(ref.start), VerseId.chapter(ref.start), VerseId.verse(ref.start)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
        }

        // Every verse found, in Bible order (TOP-10).
        if (all.isNotEmpty()) {
            section("all", "All verses found", all.size, foldable = true)
            if (open["all"] == true) {
                items(all.take(shownAll), key = { "a${it.id}" }) { v: SmartVerse -> VerseRow(v.id, v.reasons) }
                if (all.size > shownAll) item(key = "more") {
                    TextButton(onClick = { shownAll += ALL_PAGE }) { Text("Show more") }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.size(24.dp)) }
    }
}

private const val MAX_GROUP = 200
private const val ALL_PAGE = 300
