package com.biblestudy.app.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biblestudy.app.data.NameEntry
import com.biblestudy.app.data.Passage
import com.biblestudy.app.model.VerseId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos

/** The Bible lands outline (assets/map/lands.bin, built by tools/build_map.py from Natural Earth). */
class LandsMap(val land: List<FloatArray>, val lakes: List<FloatArray>, val rivers: List<FloatArray>) {
    companion object {
        @Volatile private var cached: LandsMap? = null

        fun load(context: Context): LandsMap = cached ?: synchronized(this) {
            cached ?: run {
                val bytes = context.assets.open("map/lands.bin").use { it.readBytes() }
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                fun section() = List(b.int) { FloatArray(b.int * 2) { b.float } }
                LandsMap(section(), section(), section()).also { cached = it }
            }
        }
    }
}

private val SEA = Color(0xFFDCE9F2)
private val LAND = Color(0xFFF1EADB)
private val COAST = Color(0xFFB9AE97)
private val WATER = Color(0xFF8DB4D3)
private val PIN = Color(0xFFC62828)

/**
 * A place on a simple offline map (STD-10): the coast, lakes and rivers, a few well-known places
 * to find your way, and the place itself. Pinch or use the buttons to zoom; drag to move.
 */
@Composable
fun PlaceMap(vm: StudyViewModel, place: NameEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val map by produceState<LandsMap?>(null) { value = withContext(Dispatchers.IO) { LandsMap.load(context) } }
    val landmarks by produceState(emptyList<NameEntry>()) { value = withContext(Dispatchers.IO) { vm.study.landmarks() } }
    val lat0 = place.lat ?: return
    val lon0 = place.lon ?: return
    var span by remember(place.id) { mutableFloatStateOf(3f) } // degrees of latitude shown
    var cLat by remember(place.id) { mutableFloatStateOf(lat0.toFloat()) }
    var cLon by remember(place.id) { mutableFloatStateOf(lon0.toFloat()) }
    val measurer = rememberTextMeasurer()
    Box(modifier.clip(RoundedCornerShape(12.dp))) {
        Canvas(
            Modifier.fillMaxWidth().height(260.dp).testTag("placeMap")
                .semantics { contentDescription = "Map showing ${place.name}" }
                .pointerInput(place.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val perPx = span / size.height
                        span = (span / zoom).coerceIn(0.4f, 40f)
                        cLat = (cLat + pan.y * perPx).coerceIn(10f, 48f)
                        cLon = (cLon - pan.x * perPx / cos(Math.toRadians(cLat.toDouble())).toFloat()).coerceIn(-8f, 60f)
                    }
                }
        ) {
            drawRect(SEA)
            val m = map ?: return@Canvas
            val k = size.height / span
            val kx = k * cos(Math.toRadians(cLat.toDouble())).toFloat()
            fun x(lon: Float) = size.width / 2 + (lon - cLon) * kx
            fun y(lat: Float) = size.height / 2 - (lat - cLat) * k
            fun shape(pts: FloatArray, close: Boolean) = Path().apply {
                moveTo(x(pts[0]), y(pts[1]))
                for (i in 2 until pts.size step 2) lineTo(x(pts[i]), y(pts[i + 1]))
                if (close) close()
            }
            for (s in m.land) { val p = shape(s, true); drawPath(p, LAND); drawPath(p, COAST, style = Stroke(1.dp.toPx())) }
            for (s in m.lakes) drawPath(shape(s, true), WATER)
            for (s in m.rivers) drawPath(shape(s, false), WATER, style = Stroke(1.2.dp.toPx()))
            // The place first, then landmarks whose labels don't overlap one already drawn.
            val px = x(lon0.toFloat()); val py = y(lat0.toFloat())
            val main = measurer.measure(place.name, TextStyle(fontSize = 15.sp, color = PIN))
            val taken = arrayListOf(androidx.compose.ui.geometry.Rect(Offset(px + 9.dp.toPx(), py - 10.dp.toPx()), androidx.compose.ui.geometry.Size(main.size.width.toFloat(), main.size.height.toFloat())))
            val small = TextStyle(fontSize = 11.sp, color = Color(0xFF5F5A50))
            for (l in landmarks) {
                if (l.id == place.id) continue
                val lx = x(l.lon!!.toFloat()); val ly = y(l.lat!!.toFloat())
                if (lx < 0 || ly < 0 || lx > size.width || ly > size.height) continue
                drawCircle(Color(0xFF6D6658), 2.5.dp.toPx(), Offset(lx, ly))
                val t = measurer.measure(l.name, small)
                val r = androidx.compose.ui.geometry.Rect(Offset(lx + 4.dp.toPx(), ly - 7.dp.toPx()), androidx.compose.ui.geometry.Size(t.size.width.toFloat(), t.size.height.toFloat()))
                if (taken.any { it.overlaps(r) }) continue
                taken += r
                drawLabel(t, r.left, r.top)
            }
            drawCircle(Color.White, 7.dp.toPx(), Offset(px, py))
            drawCircle(PIN, 5.dp.toPx(), Offset(px, py))
            drawLabel(main, px + 9.dp.toPx(), py - 10.dp.toPx())
        }
        Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallFloatingActionButton(onClick = { span = (span / 1.6f).coerceAtLeast(0.4f) }) { Icon(Icons.Filled.Add, "Zoom in") }
            SmallFloatingActionButton(onClick = { span = (span * 1.6f).coerceAtMost(40f) }) { Icon(Icons.Filled.Remove, "Zoom out") }
        }
    }
}

private fun DrawScope.drawLabel(t: androidx.compose.ui.text.TextLayoutResult, x: Float, y: Float) = drawText(t, topLeft = Offset(x, y))

/**
 * Names and places (STD-10, STD-11): look up any person or place. Without a search it lists the
 * people and places in the verse being read, then the rest of the chapter.
 */
@Composable
fun NamesPane(vm: StudyViewModel, modifier: Modifier) {
    val panel = vm.panels[vm.activePanel.coerceIn(0, vm.panels.lastIndex)]
    val t = vm.paneVerse?.takeIf { it.book == panel.book && it.chapter == panel.chapter }
    val verse = t?.verse ?: panel.topVerse
    val id = VerseId.of(panel.book, panel.chapter, verse)
    val open = vm.nameOpen
    if (open != null) {
        NameView(vm, open, modifier)
        return
    }
    var query by remember { mutableStateOf("") }
    val here by produceState(emptyList<NameEntry>() to emptyList<NameEntry>(), id) {
        value = withContext(Dispatchers.IO) { vm.study.namesInVerse(id) to vm.study.namesInChapter(panel.book, panel.chapter) }
    }
    val results by produceState(emptyList<NameEntry>(), query) {
        value = if (query.isBlank()) emptyList() else withContext(Dispatchers.IO) { vm.study.nameSearch(query) }
    }
    Column(modifier) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Find a person or place") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            if (query.isBlank()) {
                val (inVerse, inChapter) = here
                if (inVerse.isNotEmpty()) {
                    item { Heading("In ${vm.refLabel(id)}") }
                    items(inVerse, key = { "v${it.id}" }) { NameRow(it) { vm.nameOpen = it.id } }
                }
                val rest = inChapter.filter { c -> inVerse.none { it.id == c.id } }
                if (rest.isNotEmpty()) {
                    item { Heading("In ${vm.bible.book(panel.book).name} ${panel.chapter}") }
                    items(rest, key = { "c${it.id}" }) { NameRow(it) { vm.nameOpen = it.id } }
                }
            } else {
                if (results.isEmpty()) item { Text("Nothing found.", modifier = Modifier.padding(top = 8.dp)) }
                items(results, key = { it.id }) { NameRow(it) { vm.nameOpen = it.id } }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun NameRow(n: NameEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp).testTag("nameRow")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (n.place) "place" else "person", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        if (n.brief.isNotBlank()) Text(n.brief, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider()
}

/** One person or place: who or what, family, the map, and every verse. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameView(vm: StudyViewModel, id: Long, modifier: Modifier) {
    val entry by produceState<Pair<NameEntry, List<Int>>?>(null, id) {
        value = withContext(Dispatchers.IO) { vm.study.nameById(id)?.let { it to vm.study.nameVerses(id) } }
    }
    var shown by remember { mutableStateOf<Passage?>(null) }
    val version = vm.panels[vm.activePanel.coerceIn(0, vm.panels.lastIndex)].version
    PassagePopupHost(vm, shown, version, onDismiss = { shown = null })
    val (n, verses) = entry ?: run { Text("Loading…", modifier); return }
    LazyColumn(modifier.testTag("nameView")) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.nameOpen = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the list") }
                Column(Modifier.weight(1f)) {
                    Text(n.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOf(if (n.place) "Place" else "Person", n.area).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (n.brief.isNotBlank()) Text(n.brief, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
            val family = if (n.place) listOf("Founded by" to n.parents, "People who lived there" to n.children)
            else listOf("Parents" to n.parents, "Brothers and sisters" to n.siblings, "Married to" to n.partners, "Children" to n.children)
            if (!n.place && family.any { NameEntry.ids(it.second).isNotEmpty() }) {
                OutlinedButton(onClick = { vm.familyTree = n.uid }, modifier = Modifier.padding(bottom = 8.dp)) { Text("Family tree") }
            }
            if (n.place && n.lat != null) PlaceMap(vm, n, Modifier.padding(vertical = 8.dp))
            // References written out in the article ("Genesis 35:19") become links too.
            val article = remember(n.id) { linkPlainRefs(n.article, vm.bible.books) }
            StudyText(article, onPassage = { shown = it })
            // Family, or for a place its founder and people who lived there (each a link).
            for ((label, field) in family) {
                val ids = NameEntry.ids(field)
                if (ids.isEmpty()) continue
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (uid in ids) {
                        SuggestionChip(onClick = { vm.openNameUid(uid) }, label = { Text(NameEntry.label(uid)) })
                    }
                }
            }
            Text(
                "Mentioned in ${verses.size} verse${if (verses.size == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        items(verses, key = { it }) { v ->
            Text(
                vm.refLabel(v), color = LINK_COLOR,
                modifier = Modifier.fillMaxWidth().clickable { shown = Passage(VerseId.book(v), VerseId.chapter(v), VerseId.verse(v), VerseId.chapter(v), VerseId.verse(v)) }
                    .padding(vertical = 6.dp),
            )
        }
        item {
            Text(
                "From STEPBible.org (TIPNR), CC BY 4.0. Map: Natural Earth.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

/** The people and places in a verse, as chips that open them in the study pane (verse window). */
@Composable
fun NamesInVerse(vm: StudyViewModel, verseId: Int, onOpen: () -> Unit) {
    val names by produceState(emptyList<NameEntry>(), verseId) { value = withContext(Dispatchers.IO) { vm.study.namesInVerse(verseId) } }
    if (names.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("People and places:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(120.dp))
        for (n in names) SuggestionChip(onClick = { vm.openName(n.id); onOpen() }, label = { Text(n.name) })
    }
}

/** Writes plain references in [text] ("Ruth 1:1-2") as study links, leaving existing ones alone. */
fun linkPlainRefs(text: String, books: List<com.biblestudy.app.model.BookInfo>): String {
    val out = StringBuilder()
    var pos = 0
    // Only outside existing [[...]] links.
    val parts = Regex("\\[\\[[^]]*]]").findAll(text).map { it.range }.toList()
    fun plain(from: Int, to: Int) {
        val chunk = text.substring(from, to)
        var p = 0
        for (l in com.biblestudy.app.data.RefLinks.find(chunk, books)) {
            out.append(chunk, p, l.start)
            out.append("[[${l.passage.startId}-${l.passage.endId}|${chunk.substring(l.start, l.end)}]]")
            p = l.end
        }
        out.append(chunk.substring(p))
    }
    for (r in parts) {
        plain(pos, r.first)
        out.append(text, r.first, r.last + 1)
        pos = r.last + 1
    }
    plain(pos, text.length)
    return out.toString()
}
