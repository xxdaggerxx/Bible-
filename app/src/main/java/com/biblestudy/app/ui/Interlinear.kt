package com.biblestudy.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biblestudy.app.data.OriginalWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The verse in Hebrew or Greek, word by word (STD-4, BIB-9): each word with how it's said, what it
 * means here and what kind of word it is. Hebrew reads right to left. Tap a word for its full
 * grammar and a word study.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun OriginalVerse(vm: StudyViewModel, verseId: Int, version: String) {
    val words by produceState<List<OriginalWord>?>(null, verseId) {
        value = withContext(Dispatchers.IO) { vm.study.original(verseId) }
    }
    var picked by remember(verseId) { mutableIntStateOf(-1) }
    val ws = words ?: return
    if (ws.isEmpty()) {
        Text("No Hebrew or Greek text for this verse.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        return
    }
    val hebrew = ws.first().hebrew
    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("originalVerse")) {
        CompositionLocalProvider(LocalLayoutDirection provides if (hebrew) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ws.forEachIndexed { i, w -> WordCard(w, i == picked) { picked = if (picked == i) -1 else i } }
            }
        }
        ws.getOrNull(picked)?.let { w ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${w.word}  ${w.xlit}  — ${w.gloss}", style = MaterialTheme.typography.titleSmall)
                        Text(Grammar.describe(w.grammar), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("grammar"))
                        when (w.edition) {
                            "m" -> Text("In the modern Greek editions; not in the text the KJV was translated from.", style = MaterialTheme.typography.bodySmall)
                            "k" -> Text("In the text the KJV was translated from; not in the modern Greek editions.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (w.strong.isNotEmpty()) {
                        FilledTonalButton(onClick = { vm.wordStudy = WordStudy(w.strong, version, w.gloss, verseId) }) { Text("Word study") }
                    }
                }
            }
        }
        Text(
            if (hebrew) "Hebrew, read right to left. Tap a word for its grammar and a word study."
            else "Greek. Tap a word for its grammar and a word study.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "From STEPBible.org (TAHOT, TAGNT), CC BY 4.0.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun WordCard(w: OriginalWord, selected: Boolean, onClick: () -> Unit) {
    val muted = w.edition.isNotEmpty()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (muted) MaterialTheme.colorScheme.outline.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.widthIn(min = 64.dp).clickable(onClick = onClick)
            .semantics { contentDescription = "${w.xlit}: ${w.gloss}" }.testTag("originalWord"),
    ) {
        // Only the order of the cards runs right to left; the English inside reads normally.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(w.word, fontSize = 24.sp, textAlign = TextAlign.Center)
            Text(w.xlit, fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.outline)
            Text(w.gloss, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 140.dp))
            Text(Grammar.short(w.grammar), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
        }
    }
}
