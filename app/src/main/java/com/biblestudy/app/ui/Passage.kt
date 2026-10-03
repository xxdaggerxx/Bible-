package com.biblestudy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.biblestudy.app.data.Passage
import com.biblestudy.app.model.VerseId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The small pop-over a Bible hyperlink opens (LINK-2): the passage's verses in the version being
 * read, with buttons to go there or open it beside the current passage. It sits just below the
 * tapped link (or above it near the bottom of the panel); tapping outside closes it.
 */
@Composable
fun PassagePopover(vm: StudyViewModel, pop: PassagePop, onDismiss: () -> Unit) {
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    val position = object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val ax = anchorBounds.left + pop.anchor.x.toInt()
            val ay = anchorBounds.top + pop.anchor.y.toInt()
            val x = (ax - popupContentSize.width / 2).coerceIn(anchorBounds.left + gap, (anchorBounds.right - popupContentSize.width - gap).coerceAtLeast(anchorBounds.left + gap))
            val below = ay + gap
            val y = if (below + popupContentSize.height <= anchorBounds.bottom - gap) below
            else (ay - gap - popupContentSize.height).coerceAtLeast(anchorBounds.top + gap)
            return IntOffset(x, y)
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        PassageCard(
            vm, pop.passage, vm.panels.getOrNull(pop.panel)?.version ?: vm.activeVersion,
            onGoTo = { vm.openPassage(pop.passage, pop.panel, beside = false) },
            onOpenBeside = { vm.openPassage(pop.passage, pop.panel, beside = true) },
            onClose = onDismiss,
        )
    }
}

/** A passage's reference, verses and actions. Used by the page pop-over and for links in notes. */
@Composable
fun PassageCard(
    vm: StudyViewModel,
    passage: Passage,
    version: String,
    onGoTo: () -> Unit,
    onOpenBeside: () -> Unit,
    onClose: () -> Unit,
) {
    val verses by produceState<List<Pair<Int, String>>?>(null, passage, version) {
        value = background { vm.passageVerses(passage, version) }
    }
    Surface(
        Modifier.widthIn(max = 460.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${vm.passageLabel(passage)} ($version)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close passage") }
            }
            val list = verses
            Column(
                Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(end = 8.dp)
            ) {
                when {
                    list == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                    list.isEmpty() -> Text("This passage isn't in the $version.", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        val numberStyle = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        var chapter = VerseId.chapter(list.first().first)
                        Text(
                            buildAnnotatedString {
                                list.forEachIndexed { i, (id, text) ->
                                    val c = VerseId.chapter(id)
                                    if (c != chapter) { append("\n\n"); chapter = c }
                                    else if (i > 0) append(" ")
                                    withStyle(numberStyle) { append(if (i == 0 || VerseId.verse(id) == 1) "$c:${VerseId.verse(id)} " else "${VerseId.verse(id)} ") }
                                    append(text)
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (list.size >= StudyViewModel.PASSAGE_LIMIT) {
                            Text("… Go to the passage to read the rest.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onOpenBeside) { Text("Open beside") }
                FilledTonalButton(onClick = onGoTo) { Text("Go to") }
            }
        }
    }
}
