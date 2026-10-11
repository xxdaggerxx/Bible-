package com.biblestudy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biblestudy.app.data.ChapterGlance
import com.biblestudy.app.data.Glance
import com.biblestudy.app.data.NameEntry
import com.biblestudy.app.model.Sketch
import com.biblestudy.app.model.VerseId

/**
 * Chapter at a glance (STD-22): a slim bar under a Bible panel's header with what's happening in
 * the chapter being read. Tapping it opens the card: who and where (from Names & places), where the
 * chapter fits in the Bible's story, and its key verse.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlanceBar(vm: StudyViewModel, index: Int) {
    val panel = vm.panels.getOrNull(index) ?: return
    if (!vm.showGlance || Sketch.isSketch(panel.book)) return
    val b = panel.book
    val c = panel.chapter
    val card by produceState<ChapterGlance?>(null, b, c) { value = background { Glance.get(vm.getApplication(), b, c) } }
    val g = card ?: return
    val open = vm.glanceOpen
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { vm.glanceOpen = !open }
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("glance$index"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    "At a glance",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp, end = 10.dp),
                )
                Text(
                    g.what,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (open) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Fold the chapter card" else "Open the chapter card",
                )
            }
            if (open) GlanceDetails(vm, panel.version, b, c, g)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlanceDetails(vm: StudyViewModel, version: String, b: Int, c: Int, g: ChapterGlance) {
    val names by produceState(emptyList<NameEntry>(), b, c) { value = background { vm.study.namesInChapter(b, c, 12) } }
    val key = VerseId.of(b, c, g.keyVerse)
    val keyText by produceState("", key, version) {
        value = background { vm.text(version).verseText(key) ?: vm.bible.verseText(key) ?: "" }
    }
    Column(Modifier.padding(start = 24.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val people = names.filter { !it.place }.take(4)
        val places = names.filter { it.place }.take(3)
        for ((label, list) in listOf("Who" to people, "Where" to places)) {
            if (list.isEmpty()) continue
            FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp).align(Alignment.CenterVertically))
                for (n in list) SuggestionChip(onClick = { vm.openName(n.id) }, label = { Text(n.name) })
            }
        }
        Row {
            Text("Where it fits", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(110.dp))
            Text(g.fits, style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.clickable { vm.openVerse(b, c, g.keyVerse) }.testTag("glanceKey")) {
            Text("Key verse", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(110.dp))
            Column {
                Text(vm.refLabel(key), style = MaterialTheme.typography.bodyMedium, color = LINK_COLOR)
                Text(keyText, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            "Written by AI from the Bible and its commentaries. Hide these cards in Settings → Reading.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
