package com.biblestudy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biblestudy.app.data.RefLinks
import com.biblestudy.app.model.BookInfo
import com.biblestudy.app.model.MarginText
import kotlin.math.roundToInt

/** Text colours offered for text boxes. */
val TEXT_BOX_COLORS = listOf(0xFF222222.toInt(), 0xFF1E4FA8.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt())
/** Background tints for text boxes (0 = none). */
val TEXT_BOX_BACKGROUNDS = listOf(0, 0x40FFE082, 0x4090CAF9, 0x40A5D6A7)

/** A text box's text with every Bible reference styled as a link (detected as you type, MRG-12). */
fun linkedText(text: String, books: List<BookInfo>): AnnotatedString = buildAnnotatedString {
    append(text)
    for (l in RefLinks.find(text, books)) {
        addStyle(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline), l.start, l.end)
    }
}

/** Shows references as links while typing; the text itself is unchanged. */
class RefLinkTransformation(private val books: List<BookInfo>) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(linkedText(text.text, books), OffsetMapping.Identity)
}

/** A text box's own text with its links and its highlights (HL-11). */
fun StudyViewModel.markedText(t: MarginText): AnnotatedString {
    val base = linkedText(t.text, bible.books)
    val marks = t.markList()
    if (marks.isEmpty()) return base
    return buildAnnotatedString {
        append(base)
        for (m in marks) {
            val a = m.start.coerceIn(0, t.text.length); val b = m.end.coerceIn(a, t.text.length)
            if (b > a) addStyle(highlightSpan(m.color, m.underline, 1f), a, b)
        }
    }
}

/**
 * Lays out a text box's text at page scale (cached until the box changes). A verse card (SKT-6) is
 * laid out from the Bible's verses, with their highlights, and redone when those change.
 */
fun StudyViewModel.textLayout(measurer: TextMeasurer, t: MarginText): TextLayoutResult {
    val spec = cardSpecCached(t)
    val stamp = if (spec != null) cardStamp() else 0
    val key = textLayoutKeys[t.id]
    textLayouts[t.id]?.let { if (key == t && textLayoutStamps[t.id] == stamp) return it }
    val inner = (t.w - 2 * ReaderController.TEXT_PAD).coerceAtLeast(20f).roundToInt()
    val shown = if (spec != null) {
        ensureCardHighlights(spec)
        cardText(t, spec).also { cardTexts[t.id] = it }.text
    } else {
        cardTexts.remove(t.id)
        markedText(t)
    }
    val r = measurer.measure(
        shown,
        TextStyle(fontSize = t.size.sp, color = Color(t.color)),
        constraints = Constraints(maxWidth = inner),
    )
    textLayouts[t.id] = r
    textLayoutKeys[t.id] = t
    textLayoutStamps[t.id] = stamp
    textHeights[t.id] = r.size.height + 2 * ReaderController.TEXT_PAD
    return r
}

/** Draws a text box on the page at [r] (page units). The box being typed in shows only its frame. */
fun DrawScope.drawTextBox(vm: StudyViewModel, measurer: TextMeasurer, t: MarginText, x: Float, y: Float, selected: Boolean, editing: Boolean) {
    val layout = vm.textLayout(measurer, t)
    val h = layout.size.height + 2 * ReaderController.TEXT_PAD
    if (t.background != 0) {
        drawRoundRect(Color(t.background), topLeft = Offset(x, y), size = Size(t.w, h), cornerRadius = CornerRadius(8f))
    }
    if (selected || editing) {
        drawRoundRect(
            Color(0xFF1E88E5), topLeft = Offset(x - 2f, y - 2f), size = Size(t.w + 4f, h + 4f),
            cornerRadius = CornerRadius(8f), style = Stroke(width = 2f),
        )
        // Corner handle for widening the box with the Select tool.
        drawCircle(Color(0xFF1E88E5), radius = 9f, center = Offset(x + t.w, y + h))
    }
    if (!editing) drawText(layout, topLeft = Offset(x + ReaderController.TEXT_PAD, y + ReaderController.TEXT_PAD))
}

/**
 * Typing in a text box, in place: a text field laid over the box at the page's zoom. Its text is
 * saved when typing ends (Done, a tap elsewhere, or leaving the page).
 */
@Composable
fun TextBoxEditor(vm: StudyViewModel, ctl: ReaderController, page: PlacedPage, t: MarginText) {
    val before = remember(t.id) { t }
    var value by remember(t.id) { mutableStateOf(t.text) }
    val latest by rememberUpdatedState(value)
    DisposableEffect(t.id) { onDispose { vm.finishTextEdit(before, latest) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(t.id) { runCatching { focus.requestFocus() } }

    val panel = ctl.panel
    val r = ctl.textRect(page.geo, t)
    val density = LocalDensity.current
    val left = (r.left + ctl.drawerShift(t.region)) * panel.zoom + panel.panX
    val top = (page.top + r.top) * panel.zoom + panel.panY
    val pad = with(density) { (ReaderController.TEXT_PAD * panel.zoom).toDp() }
    val fontSize = with(density) { (t.size * panel.zoom).toSp() }
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        textStyle = TextStyle(fontSize = fontSize, color = Color(t.color)),
        visualTransformation = remember { RefLinkTransformation(vm.bible.books) },
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .width(with(density) { (r.width * panel.zoom).toDp() })
            .padding(pad)
            .focusRequester(focus)
            .testTag("textBoxEditor"),
    )
}

/** The bar for a selected text box (UI-4): edit, size, colour, background, delete. */
@Composable
fun TextBoxBar(vm: StudyViewModel, ctl: ReaderController, t: MarginText, modifier: Modifier = Modifier) {
    val editing = vm.editingText == t.id
    Surface(modifier, shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val spec = vm.cardSpecCached(t)
            if (spec != null) {
                // A verse card (SKT-6): choose its version; its words come from the Bible.
                val context = androidx.compose.ui.platform.LocalContext.current
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                for (v in com.biblestudy.app.data.BibleRepository.ALL) {
                    androidx.compose.material3.FilterChip(
                        selected = v.code == spec.version,
                        onClick = { vm.cardSavedText(spec, v.code)?.let { vm.restyleText(t, t.copy(text = it)) } ?: run { vm.message = "Not in the ${v.code}." } },
                        label = { Text(v.code) },
                    )
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(t.text)); vm.message = "Copied." }) { Text("Copy") }
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, t.text)
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, null)) }
                }) { Text("Share") }
            } else if (editing) {
                TextButton(onClick = { vm.editingText = null }) { Text("Done") }
            } else {
                TextButton(onClick = { vm.editingText = t.id }) { Text("Edit") }
            }
            TextButton(onClick = { vm.restyleText(t, t.copy(size = (t.size - 3f).coerceAtLeast(12f))) }) { Text("A−") }
            TextButton(onClick = { vm.restyleText(t, t.copy(size = (t.size + 3f).coerceAtMost(48f))) }) { Text("A+") }
            if (spec == null) for (c in TEXT_BOX_COLORS) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(Color(c))
                        .border(if (c == t.color) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .semantics { contentDescription = "Text colour" }
                        .clickable { vm.restyleText(t, t.copy(color = c)) }
                )
            }
            for (b in TEXT_BOX_BACKGROUNDS) {
                Box(
                    Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (b == 0) Color.White else Color(b))
                        .border(if (b == t.background) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .semantics { contentDescription = if (b == 0) "No background" else "Background" }
                        .clickable { vm.restyleText(t, t.copy(background = b)) }
                )
            }
            TextButton(onClick = { vm.deleteText(t); ctl.selectedTextId = null }) { Text("Delete") }
        }
    }
}
