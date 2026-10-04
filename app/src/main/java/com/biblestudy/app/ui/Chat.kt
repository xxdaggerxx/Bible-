package com.biblestudy.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.biblestudy.app.data.ChatSource
import com.biblestudy.app.data.Passage
import com.biblestudy.app.data.RefLinks

/**
 * The AI chat panel (AI-1 to AI-7): ask a question, with passages you sent from the Bible; the
 * AI searches only your chosen sites and reports what they say, with the pages it used. Bible
 * references in an answer are links, as everywhere else in the app.
 */
@Composable
fun ChatPane(vm: StudyViewModel, modifier: Modifier) {
    val chat = vm.chat
    var shown by remember { mutableStateOf<Passage?>(null) }
    val panelIndex = vm.activePanel.coerceIn(0, vm.panels.lastIndex)
    val version = vm.studyPanel().version
    var question by remember { mutableStateOf("") }
    Column(modifier.testTag("chatPane")) {
        if (chat.apiKey.isBlank()) {
            KeyCard(vm)
            return@Column
        }
        val list = rememberLazyListState()
        LaunchedEffect(chat.entries.size, chat.busy) {
            val last = chat.entries.size + (if (chat.busy) 1 else 0)
            if (last > 0) list.animateScrollToItem(last - 1)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            if (chat.entries.isEmpty()) item {
                Text(
                    (if (chat.onlySites) "Ask a question. The AI searches only your chosen sites (${chat.sites.size}) and sums up what they say, with links to the pages. "
                    else "Ask a question. The AI searches the web, your chosen sites first, reads the best articles and sums them up, with links to the pages. ") +
                        "To ask about a passage, select it or hold a finger on a highlight, then tap Ask AI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            itemsIndexed(chat.entries) { i, e ->
                // The last reply can be asked for again; so can a question left without one.
                val retry = i == chat.entries.lastIndex && !chat.busy
                ChatBubble(
                    vm, e, onPassage = { shown = it }, onEdit = { chat.startEdit(i)?.let { question = it } },
                    onRetry = if (retry) ({ chat.retry() }) else null,
                )
            }
            if (chat.busy) item {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(if (chat.onlySites) "Searching your chosen sites…" else "Searching the web…", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // Passages waiting to be sent with the next question.
        if (chat.attached.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (p in chat.attached.toList()) {
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(p.label, maxLines = 1) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Don't send ${p.label}",
                                modifier = Modifier.size(18.dp).clickable { chat.attached.remove(p) })
                        },
                    )
                }
            }
        }
        // Editing an earlier question (AI-9).
        if (chat.editing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "Editing your question. Sending replaces it and the answers after it.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { chat.cancelEdit(); question = "" }) { Text("Cancel") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text(if (chat.attached.isEmpty()) "Ask about the Bible" else "Ask about these passages") },
                maxLines = 4,
                modifier = Modifier.weight(1f).testTag("chatInput"),
            )
            IconButton(
                onClick = { chat.ask(question); question = "" },
                enabled = !chat.busy && (question.isNotBlank() || chat.attached.isNotEmpty()),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
            Text(
                if (chat.onlySites) "Online. Answers come only from your chosen sites, and can still be wrong: check the verses." else "Online. Answers come only from the pages they cite, and can still be wrong: check the verses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (chat.entries.isNotEmpty()) TextButton(onClick = { chat.newChat() }, enabled = !chat.busy) { Text("New chat") }
        }
    }
    shown?.let { p ->
        Popup(alignment = Alignment.Center, onDismissRequest = { shown = null }, properties = PopupProperties(focusable = true)) {
            PassageCard(
                vm, p, version,
                onGoTo = { vm.openPassage(p, panelIndex, beside = false); shown = null },
                onOpenBeside = { vm.openPassage(p, panelIndex, beside = true); shown = null },
                onClose = { shown = null },
            )
        }
    }
}

/**
 * The chat in its own little window (AI-10), floating over the text above the chat bubble, so
 * you can keep reading and tapping verses while it's open. It can move into a panel beside the text.
 */
@Composable
fun ChatWindow(vm: StudyViewModel, modifier: Modifier) {
    androidx.compose.material3.Surface(
        modifier.imePadding().testTag("chatWindow"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI chat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.chatBeside() }) {
                    Icon(Icons.Filled.VerticalSplit, contentDescription = "Open the chat beside the text")
                }
                IconButton(onClick = { vm.chatWindow = false }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close the chat")
                }
            }
            ChatPane(vm, Modifier.weight(1f).padding(end = 8.dp))
        }
    }
}

/** Asks for the Claude API key the first time (AI-5). */
@Composable
private fun KeyCard(vm: StudyViewModel) {
    var key by remember { mutableStateOf("") }
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI chat needs your Claude API key", style = MaterialTheme.typography.titleMedium)
        Text(
            "The chat is online: your question, and any passages you send, go to Anthropic, who search your chosen sites. " +
                "Each question costs a few cents on your Claude account. Get a key at console.anthropic.com. It's kept on this tablet only, " +
                "and isn't in your backups.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it }, singleLine = true,
            label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("chatKey"),
        )
        Button(onClick = { vm.chat.changeKey(key) }, enabled = key.isNotBlank()) { Text("Save key") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChatBubble(vm: StudyViewModel, e: ChatEntry, onPassage: (Passage) -> Unit, onEdit: () -> Unit, onRetry: (() -> Unit)? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalAlignment = if (e.user) Alignment.End else Alignment.Start,
    ) {
        // Hold a finger on the words to select and copy part of them (AI-9).
        SelectionContainer {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .background(
                        if (e.user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (e.user) {
                    for (p in e.passages) {
                        Text(p.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(p.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (e.text.isNotBlank()) Text(e.text, style = MaterialTheme.typography.bodyLarge)
                } else if (e.note != null) {
                    Text(e.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("chatNote"))
                } else {
                    val answer = remember(e.text) { answerText(vm, e.text, onPassage) }
                    Text(answer, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("chatAnswer"))
                    // The verses the answer names, together (AI-8).
                    val verses = remember(e.text) { RefLinks.find(e.text, vm.bible.books).distinctBy { vm.passageLabel(it.passage) } }
                    Text("Verses", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    if (verses.isEmpty()) {
                        Text("The sources didn't name any verses for this.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    } else {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("chatVerses")) {
                            for (v in verses) AssistChip(onClick = { onPassage(v.passage) }, label = { Text(vm.passageLabel(v.passage)) })
                        }
                    }
                    Text("Sources", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    e.sources.forEachIndexed { i, s ->
                        SourceRow(i + 1, s) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url))) }
                                .onFailure { vm.message = "No browser is available." }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (e.note == null || e.user) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(copyText(e))); vm.message = "Copied." }) { Text("Copy") }
                if (e.user) TextButton(onClick = onEdit, enabled = !vm.chat.busy) { Text("Edit") }
            }
            // Ask the last question again (AI-11): a clear button when it failed or found nothing.
            if (onRetry != null) {
                if (e.note != null || e.user) androidx.compose.material3.FilledTonalButton(onClick = onRetry, enabled = !vm.chat.busy, modifier = Modifier.padding(top = 4.dp).testTag("chatRetry")) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Try again", Modifier.padding(start = 6.dp))
                }
                else TextButton(onClick = onRetry, enabled = !vm.chat.busy, modifier = Modifier.testTag("chatRetry")) { Text("Try again") }
            }
        }
    }
}

/** A message as copied: the passages and question, or the answer with its sources listed. */
internal fun copyText(e: ChatEntry): String = buildString {
    for (p in e.passages) append(p.label).append(": ").append(p.text).append("\n")
    append(e.text)
    if (e.sources.isNotEmpty()) {
        append("\n\nSources:\n")
        e.sources.forEachIndexed { i, s -> append("[${i + 1}] ${s.title}: ${s.url}\n") }
    }
}.trim()

@Composable
private fun SourceRow(n: Int, s: ChatSource, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 3.dp)) {
        Text("[$n] ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Column {
            Text(s.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(s.site, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * An answer as shown: simple Markdown (headings, lists, **bold**), with Bible references made
 * into links that open the passage pop-over.
 */
internal fun answerText(vm: StudyViewModel, md: String, onPassage: (Passage) -> Unit): AnnotatedString {
    val body = buildAnnotatedString {
        md.lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            val line = raw.trimEnd()
            when {
                line.startsWith("#") -> {
                    val h = line.trimStart('#').trim()
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(HelpGuide.inline(h)); pop()
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                    { append("• "); append(HelpGuide.inline(line.trimStart().drop(2))) }
                else -> append(HelpGuide.inline(line))
            }
        }
    }
    val links = RefLinks.find(body.text, vm.bible.books)
    if (links.isEmpty()) return body
    val style = TextLinkStyles(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        append(body)
        for ((i, l) in links.withIndex()) addLink(LinkAnnotation.Clickable("ref$i", style) { onPassage(l.passage) }, l.start, l.end)
    }
}
