package com.biblestudy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
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
 * A new sketch page: its name and paper, and whether it's linked to the passage being read (its
 * badge then shows beside that verse) or stands on its own (SKT-1, SKT-2). The ready-made pages are
 * already in My notes, so new pages start blank.
 */
@Composable
fun NewSketchDialog(vm: StudyViewModel, onDismiss: () -> Unit, open: (com.biblestudy.app.model.Sketch) -> Unit = { vm.openSketch(it) }) {
    var name by remember { mutableStateOf("") }
    var paper by remember { mutableStateOf(Paper.BLANK) }
    val here = remember { vm.sketchLinkHere() }
    var linked by remember { mutableStateOf(here != null) }
    val label = here?.let { vm.refLabel(com.biblestudy.app.model.VerseId.of(it.first, it.second, it.third)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New sketch page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text("e.g. Timeline of the kings") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (pp in Paper.entries) FilterChip(selected = paper == pp, onClick = { paper = pp }, label = { Text(pp.label) })
                }
                if (label != null) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = linked, role = Role.Switch) { linked = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Link to $label")
                            Text(
                                if (linked) "It opens from a marker beside that verse, and from My notes."
                                else "It stands on its own and opens from My notes \u2192 Sketch pages.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Switch(checked = linked, onCheckedChange = null)
                    }
                } else {
                    Text("It stands on its own and opens from My notes \u2192 Sketch pages.", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Ready-made pages (the feasts, the tabernacle, the kings, Paul's journeys and more) are already in My notes \u2192 Sketch pages.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val made = vm.createSketch(name, paper, link = if (linked) here else null, open = false)
                onDismiss()
                open(made)
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
        value = passage?.let { background { vm.passageVerses(it, version) } } ?: emptyList()
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
        value = if (q.isBlank()) emptyList() else background { vm.study.nameSearch(q, 50) }
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
