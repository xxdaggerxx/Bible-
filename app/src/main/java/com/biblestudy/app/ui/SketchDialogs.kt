package com.biblestudy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biblestudy.app.data.NameEntry
import com.biblestudy.app.data.Passage
import com.biblestudy.app.data.RefLinks
import com.biblestudy.app.model.Paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Card backgrounds: verses pale gold, people and places pale blue (SKT-4). */
const val VERSE_CARD_BG = 0x40FFE082
const val NAME_CARD_BG = 0x4090CAF9

/**
 * A new sketch page: its name and paper, blank or started from a ready-made page (SKT-5);
 * it's linked to the passage being read (SKT-1, SKT-2).
 */
@Composable
fun NewSketchDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var paper by remember { mutableStateOf(Paper.BLANK) }
    var template by remember { mutableStateOf<SketchTemplate?>(null) }
    val p = vm.panels[vm.activePanel.coerceIn(0, vm.panels.lastIndex)]
    val link = vm.sketchOf(p.book)?.let { vm.refLabel(com.biblestudy.app.model.VerseId.of(it.linkBook, it.linkChapter, it.linkVerse)) }
        ?: vm.refLabel(com.biblestudy.app.model.VerseId.of(p.book, p.chapter, p.topVerse))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New sketch page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text(template?.name ?: "e.g. Timeline of the kings") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (pp in Paper.entries) FilterChip(selected = paper == pp, onClick = { paper = pp }, label = { Text(pp.label) })
                }
                Text("Start from", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Column(Modifier.selectableGroup()) {
                    for (t in listOf<SketchTemplate?>(null) + SketchTemplates.all) {
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = template == t, role = Role.RadioButton) {
                                template = t
                                if (t != null) paper = t.paper
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = template == t, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(t?.name ?: "A blank page", style = MaterialTheme.typography.bodyLarge)
                                if (t != null) Text(t.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                Text("Linked to $link: it opens from a marker beside that verse.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = vm.createSketch(name.ifBlank { template?.name ?: "" }, paper)
                template?.let { vm.placeOnSketch(s, it.items()) }
                onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A verse card (SKT-4): type a reference, see the verses, and add them as a card whose
 * reference is a link. Works on sketch pages and in the margins.
 */
@Composable
fun VerseCardDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    var ref by remember { mutableStateOf("") }
    val version = vm.activeVersion
    val passage = remember(ref) { RefLinks.find(ref.trim(), vm.bible.books).firstOrNull()?.passage }
    val verses by produceState(emptyList<Pair<Int, String>>(), passage, version) {
        value = passage?.let { withContext(Dispatchers.IO) { vm.passageVerses(it, version) } } ?: emptyList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verse card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ref, onValueChange = { ref = it }, singleLine = true,
                    label = { Text("Reference, e.g. John 3:16-18") }, modifier = Modifier.fillMaxWidth(),
                )
                when {
                    ref.isBlank() -> {}
                    passage == null -> Text("Type a book, chapter and verse.", style = MaterialTheme.typography.bodySmall)
                    verses.isEmpty() -> Text("Not found in the $version.", style = MaterialTheme.typography.bodySmall)
                    else -> Text(
                        verses.joinToString(" ") { it.second }, maxLines = 6, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("cardPreview"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = passage != null && verses.isNotEmpty(),
                onClick = {
                    val p = passage ?: return@TextButton
                    vm.insertCard(verseCardText(vm, p, version, verses), VERSE_CARD_BG)
                    onDismiss()
                },
            ) { Text("Add card") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "John 3:16 (KJV)" then the verses, numbered when there are several. */
fun verseCardText(vm: StudyViewModel, p: Passage, version: String, verses: List<Pair<Int, String>>): String {
    val body = if (verses.size == 1) verses.single().second
    else verses.joinToString(" ") { (id, t) -> "${com.biblestudy.app.model.VerseId.verse(id)} $t" }
    return "${vm.passageLabel(p)} ($version)\n$body"
}

/** A person or place card (SKT-4): pick from Names & places; the card says who or what it is. */
@Composable
fun NameCardDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val results by produceState(emptyList<NameEntry>(), q) {
        value = if (q.isBlank()) emptyList() else withContext(Dispatchers.IO) { vm.study.nameSearch(q, 50) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Person or place card") },
        text = {
            Column {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    label = { Text("Find a person or place") }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.id }) { n ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                vm.insertCard(nameCardText(vm, n), NAME_CARD_BG)
                                onDismiss()
                            }.padding(vertical = 8.dp).testTag("nameRow")
                        ) {
                            Text(n.name + if (n.place) "  (place)" else "", style = MaterialTheme.typography.bodyLarge)
                            if (n.brief.isNotBlank()) Text(n.brief, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Bethlehem" then what it is, and where it's first mentioned as a link. */
fun nameCardText(vm: StudyViewModel, n: NameEntry): String {
    val first = vm.study.nameVerses(n.id).firstOrNull()
    return buildString {
        append(n.name)
        if (n.brief.isNotBlank()) append("\n").append(n.brief)
        if (first != null) append("\nFirst mentioned: ").append(vm.refLabel(first))
    }
}
