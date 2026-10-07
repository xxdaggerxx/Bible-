package com.biblestudy.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.biblestudy.app.data.BibleRepository
import com.biblestudy.app.data.Passage
import com.biblestudy.app.data.RefLinks
import com.biblestudy.app.model.MarginText
import com.biblestudy.app.model.VerseId

/**
 * Live verse cards (SKT-6). A verse card is a text box whose first line is a reference and a
 * version, e.g. "John 3:16–18 (KJV)". Its verses are read from the Bible, not from the box's
 * saved text, so a card works like the page: each verse opens the verse window, words of Jesus
 * can be red, and its highlights are the Bible's own highlights on those verses.
 */
data class CardSpec(val passage: Passage, val version: String)

/** One verse on a card: where its words are on the card and where they are in the chapter's text. */
data class CardVerse(val id: Int, val cardStart: Int, val cardEnd: Int, val chapterStart: Int, val text: String)

/** A card's text, ready to lay out, with its verses. [headerEnd] ends the reference line. */
class CardText(val text: AnnotatedString, val headerEnd: Int, val verses: List<CardVerse>) {
    fun verseAt(offset: Int): CardVerse? = verses.firstOrNull { offset >= it.cardStart && offset <= it.cardEnd }
}

private val CARD_HEADER = Regex("^(.+) \\(([A-Za-z0-9]{2,8})\\)$")

/** The passage and version a text box shows, if it's a verse card. */
fun StudyViewModel.cardSpec(t: MarginText): CardSpec? = cardSpecOf(t.text)

/** The passage and version a text box's words make it show, if they're a verse card's. */
fun StudyViewModel.cardSpecOf(body: String): CardSpec? {
    val first = body.substringBefore('\n').trim()
    val m = CARD_HEADER.find(first) ?: return null
    val version = m.groupValues[2].uppercase()
    if (BibleRepository.ALL.none { it.code == version }) return null
    val link = RefLinks.find(m.groupValues[1], bible.books).singleOrNull() ?: return null
    // The whole label must be the reference, so an ordinary note that starts with one isn't a card.
    if (link.start != 0 || link.end < m.groupValues[1].trimEnd().length) return null
    return CardSpec(link.passage, version)
}

/** A card's saved text in [version]: its header and verses, as made by Insert → Verse card. */
fun StudyViewModel.cardSavedText(spec: CardSpec, version: String): String? {
    val verses = passageVerses(spec.passage, version)
    if (verses.isEmpty()) return null
    return verseCardText(this, spec.passage, version, verses)
}

/**
 * The card's text as shown: the reference as a link, then the verses (numbered when there are
 * several) with words of Jesus in red and the Bible's highlights on them (HL-10, SKT-6).
 */
fun StudyViewModel.cardText(t: MarginText, spec: CardSpec): CardText {
    val version = spec.version
    val p = spec.passage
    val verses = cardVerses(p, version)
    val header = "${passageLabel(p)} ($version)"
    // An online Bible's chapter not on the tablet (yet, or any more): show the verses saved with the card.
    if (verses.isEmpty()) {
        val saved = t.text.substringAfter('\n', "").trim()
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)) { append(header) }
            if (saved.isNotEmpty()) { append("\n"); append(saved) }
        }
        return CardText(text, header.length, emptyList())
    }
    val out = ArrayList<CardVerse>()
    var headerEnd = 0
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)) { append(header) }
        headerEnd = length
        append("\n")
        val numbered = verses.size > 1
        verses.forEachIndexed { i, (id, vText) ->
            if (i > 0) append(" ")
            if (numbered) {
                withStyle(SpanStyle(fontSize = 0.7.em, fontWeight = FontWeight.Bold, color = Color(0xFFA07B45), baselineShift = BaselineShift(0.35f))) {
                    append(VerseId.verse(id).toString())
                }
                append(" ")
            }
            val start = length
            append(vText)
            val book = VerseId.book(id); val ch = VerseId.chapter(id); val v = VerseId.verse(id)
            val chapterStart = (verseSpan(version, book, ch, v)?.first ?: 0) + v.toString().length + 1
            out += CardVerse(id, start, length, chapterStart, vText)
            if (redLetters) {
                study.redLetters(version, book, ch, mapOf(v to vText))[v]?.forEach { r ->
                    addStyle(RED_LETTER, start + r.first, start + r.last + 1)
                }
            }
            // The Bible's highlights on this verse, in this version: exactly their words.
            val visible = visibleLayerIds().toSet()
            for (h in highlightsFor(version, book, ch)) {
                if (h.layerId !in visible) continue
                val a = (h.start - chapterStart).coerceAtLeast(0)
                val b = (h.end - chapterStart).coerceAtMost(vText.length)
                if (b <= a) continue
                addStyle(highlightSpan(h.color, h.underline, 1f), start + a, start + b)
            }
            // …and those made in other versions, over the whole verse, a shade lighter.
            for (x in crossHighlights(version, book, ch)) {
                if (x.source.layerId !in visible || v !in x.fromVerse..x.toVerse) continue
                addStyle(highlightSpan(x.source.color, x.source.underline, 0.6f), start, start + vText.length)
            }
        }
    }
    return CardText(text, headerEnd, out)
}

/**
 * A card's verses. The ESV and NLT keep only 500 verses, so they keep the verses of every card
 * on the tablet ([StudyViewModel.keepCardVerses]). For other online Bibles each chapter is asked
 * for at most once a session (and again after [StudyViewModel.retryOnline]), not on every redraw.
 */
fun StudyViewModel.cardVerses(p: Passage, version: String): List<Pair<Int, String>> {
    val repo = text(version)
    val online = repo.online ?: return passageVerses(p, version)
    if (online.limited) {
        // The ESV and NLT keep a card's verses on the tablet for it, fetched by themselves.
        if (cardFetches.add("keep $version")) keepCardVerses(version)
        return repo.savedVersesBetween(p.startId, p.endId, StudyViewModel.PASSAGE_LIMIT)
    }
    for (ch in p.chapter..minOf(p.endChapter, p.chapter + 3)) {
        if (online.isSaved(p.book, ch) || cardFetches.add("$version ${p.book} $ch")) repo.ensure(p.book, ch)
    }
    return repo.savedVersesBetween(p.startId, p.endId, StudyViewModel.PASSAGE_LIMIT)
}

/** A highlight or underline as a text style, [strength] lighter for highlights from other versions. */
fun highlightSpan(color: Int, underline: Boolean, strength: Float): SpanStyle =
    if (underline) SpanStyle(textDecoration = TextDecoration.Underline, color = Color(color))
    else SpanStyle(background = Color(color).copy(alpha = HIGHLIGHT_ALPHA * strength))
