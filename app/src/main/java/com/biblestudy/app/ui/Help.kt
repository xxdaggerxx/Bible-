package com.biblestudy.app.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** One section of the Help guide: its heading and its lines (paragraphs and "- " bullets). */
class HelpSection(val title: String, val lines: List<String>) {
    val text: String by lazy { (listOf(title) + lines).joinToString("\n") }
}

/**
 * The Help guide (assets/help/guide.md): a short Markdown file, one "## " section per feature,
 * written to be read on the tablet. Kept up to date with every release.
 */
object HelpGuide {
    fun load(context: Context): Pair<String, List<HelpSection>> {
        val text = context.assets.open("help/guide.md").bufferedReader().use { it.readText() }
        return parse(text)
    }

    /** The guide's introduction and its sections. */
    fun parse(text: String): Pair<String, List<HelpSection>> {
        val intro = StringBuilder()
        val sections = ArrayList<HelpSection>()
        var title: String? = null
        var lines = ArrayList<String>()
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("# ") -> {}
                line.startsWith("## ") -> {
                    title?.let { sections += HelpSection(it, lines) }
                    title = line.removePrefix("## ").trim()
                    lines = ArrayList()
                }
                title == null -> if (line.isNotBlank()) intro.append(line).append(' ')
                line.isNotBlank() -> lines += line
            }
        }
        title?.let { sections += HelpSection(it, lines) }
        return intro.toString().trim() to sections
    }

    /** **bold** and *italic*; a backslash keeps the next character as it is. */
    fun inline(s: String): AnnotatedString = buildAnnotatedString {
        var bold = false
        var italic = false
        val run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            withStyle(SpanStyle(fontWeight = if (bold) FontWeight.SemiBold else null, fontStyle = if (italic) FontStyle.Italic else null)) {
                append(run.toString())
            }
            run.clear()
        }
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '\\' && i + 1 < s.length -> { run.append(s[i + 1]); i += 2 }
                s.startsWith("**", i) -> { flush(); bold = !bold; i += 2 }
                s[i] == '*' -> { flush(); italic = !italic; i++ }
                else -> { run.append(s[i]); i++ }
            }
        }
        flush()
    }
}

/** Help (⋮ → Help): the guide's sections, searchable; tap a heading to open it. */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val (intro, sections) = remember { HelpGuide.load(context) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<HelpSection?>(null) }
    val listState = rememberLazyListState()
    BigDialog(onDismiss) {
        Column {
            val shown = open
            DialogTitle(shown?.title ?: "Help", onDismiss, leading = shown?.let {
                {
                    androidx.compose.material3.IconButton(onClick = { open = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All topics")
                    }
                }
            })
            if (shown != null) {
                LazyColumn(Modifier.weight(1f).testTag("helpSection")) {
                    items(shown.lines) { line -> HelpLine(line) }
                }
                return@Column
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search help, e.g. layers, Greek, backup") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("helpSearch"),
            )
            val q = query.trim()
            val matches = if (q.isEmpty()) sections else sections.filter { s ->
                q.split(' ').filter { it.isNotBlank() }.all { s.text.contains(it, ignoreCase = true) }
            }
            LazyColumn(Modifier.weight(1f), state = listState) {
                if (q.isEmpty()) item { Text(HelpGuide.inline(intro), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp)) }
                if (matches.isEmpty()) item { Text("Nothing in Help matches “$q”.", color = MaterialTheme.colorScheme.outline) }
                items(matches, key = { it.title }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { open = s }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium)
                            // While searching, the first matching line shows what was found.
                            if (q.isNotEmpty()) {
                                val hit = s.lines.firstOrNull { l -> q.split(' ').any { it.isNotBlank() && l.contains(it, ignoreCase = true) } }
                                if (hit != null) Text(
                                    HelpGuide.inline(hit.removePrefix("- ")), maxLines = 2,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HelpLine(line: String) {
    val bullet = line.trimStart().startsWith("- ")
    val indent = (line.length - line.trimStart().length) / 2
    val body = line.trimStart().removePrefix("- ")
    if (bullet) {
        Row(Modifier.padding(start = (8 + indent * 20).dp, top = 4.dp, bottom = 4.dp)) {
            Text("•  ", style = MaterialTheme.typography.bodyLarge)
            Text(HelpGuide.inline(body), style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        Text(HelpGuide.inline(body), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
    }
}
