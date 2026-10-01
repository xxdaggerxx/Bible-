package com.biblestudy.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biblestudy.app.data.NameEntry
import com.biblestudy.app.data.StudyRepository
import com.biblestudy.app.model.Drawn
import com.biblestudy.app.model.DrawnBox
import com.biblestudy.app.model.DrawnLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A person in a family tree: [uid] is their TIPNR id. Positions are in page units. */
data class TreeNode(val uid: String, val label: String, val x: Float, val y: Float, val w: Float, val h: Float, val kind: TreeKind) {
    val cx get() = x + w / 2
    val bottom get() = y + h
}

enum class TreeKind(val background: Int) {
    SELF(0x80FFD54F.toInt()), SPOUSE(0x40F48FB1), KIN(NAME_CARD_BG)
}

/**
 * A family tree (STD-16): grandparents, parents, the person with their brothers and sisters,
 * whom they married, and their children, joined by lines. Rows that don't fit wrap onto more lines.
 */
class FamilyTree(
    val name: String,
    val nodes: List<TreeNode>,
    val lines: List<List<Pair<Float, Float>>>,
    /** Parents side by side, joined by a short line between their boxes. */
    val couples: List<Pair<TreeNode, TreeNode>>,
    val width: Float,
    val height: Float,
) {

    /** The tree as text boxes and ink for a sketch page, with a title and a key. */
    fun drawing(title: Boolean): List<Drawn> {
        val out = ArrayList<Drawn>()
        if (title) out += DrawnBox(0f, 0f, width, "Family of $name", size = 30f)
        val top = if (title) TITLE_H else 0f
        for (l in lines) out += DrawnLine(l.map { (x, y) -> x to y + top })
        // Text boxes hug the name, centred where the lines meet.
        fun hug(n: TreeNode) = minOf(n.w, n.label.length * TEXT * 0.55f + 28f)
        for (n in nodes) out += DrawnBox(n.cx - hug(n) / 2, n.y + top, hug(n), n.label, size = TEXT, background = n.kind.background)
        for ((a, b) in couples) {
            val y = a.y + top + NODE_H / 2
            out += DrawnLine(listOf(a.cx + hug(a) / 2 to y, b.cx - hug(b) / 2 to y))
        }
        out += DrawnBox(0f, height + top + 16f, width, KEY, size = 14f, color = 0xFF777777.toInt())
        return out
    }

    companion object {
        const val WIDTH = 1200f
        private const val NODE_W = 170f
        private const val GAP = 14f
        private const val ROW_GAP = 64f
        const val TEXT = 18f
        private const val NODE_H = TEXT * 1.35f + 16f
        private const val TITLE_H = 70f
        const val KEY = "Gold: the person · Pink: married to · Blue: family. From STEPBible.org (TIPNR), CC BY 4.0."

        private fun label(uid: String) = NameEntry.label(uid).let { if (it.startsWith("Unnamed")) "(unnamed)" else it }

        /** Lays out the family of the person with TIPNR id [uid]; null if there's no such person. */
        fun build(study: StudyRepository, uid: String): FamilyTree? {
            val me = study.nameByUid(uid) ?: return null
            val nodes = ArrayList<TreeNode>()
            val lines = ArrayList<List<Pair<Float, Float>>>()
            val couples = ArrayList<Pair<TreeNode, TreeNode>>()
            var y = 0f

            // Rows wrap: each line of a row is centred.
            fun row(people: List<Pair<String, TreeKind>>): List<List<TreeNode>> {
                if (people.isEmpty()) return emptyList()
                val perLine = ((WIDTH + GAP) / (NODE_W + GAP)).toInt()
                val out = people.chunked(perLine).map { chunk ->
                    val w = chunk.size * NODE_W + (chunk.size - 1) * GAP
                    var x = (WIDTH - w) / 2
                    val line = chunk.map { (u, k) -> TreeNode(u, label(u), x, y, NODE_W, NODE_H, k).also { x += NODE_W + GAP } }
                    y += NODE_H + ROW_GAP
                    line
                }
                nodes += out.flatten()
                return out
            }

            // Joins [from] (side by side, e.g. two parents) to every node of [to] with a bus line.
            fun connect(from: List<TreeNode>, to: List<List<TreeNode>>, lift: Float = 0f) {
                if (from.isEmpty() || to.isEmpty()) return
                val sx = from.map { it.cx }.average().toFloat()
                val sy = from.maxOf { it.bottom }
                // Two parents: a short line in the gap between them, and the family hangs from its middle.
                if (from.size > 1) couples += from.first() to from.last()
                val startY = if (from.size > 1) sy - NODE_H / 2 else sy
                val trunkX = (to.flatten().minOf { it.x } - 10f).coerceAtLeast(2f)
                to.forEachIndexed { i, line ->
                    val busY = line.first().y - ROW_GAP / 2 - lift
                    if (i == 0) {
                        lines += listOf(sx to startY, sx to busY)
                        lines += listOf(minOf(sx, line.first().cx) to busY, maxOf(sx, line.last().cx) to busY)
                    } else {
                        val prevBus = to[i - 1].first().y - ROW_GAP / 2 - lift
                        lines += listOf(trunkX to prevBus, trunkX to busY, line.last().cx to busY)
                    }
                    for (n in line) lines += listOf(n.cx to busY, n.cx to n.y)
                }
            }

            val parents = NameEntry.ids(me.parents)
            // Grandparents, grouped above the parent they belong to.
            val grand = parents.map { p -> p to (study.nameByUid(p)?.let { NameEntry.ids(it.parents) } ?: emptyList()) }
            val grandRows = row(grand.flatMap { (_, gs) -> gs.map { it to TreeKind.KIN } })
            val parentRows = row(parents.map { it to TreeKind.KIN })
            if (grandRows.isNotEmpty()) {
                val all = grandRows.flatten()
                var i = 0
                grand.forEachIndexed { pi, (p, gs) ->
                    val group = all.subList(i, i + gs.size); i += gs.size
                    val parent = parentRows.flatten().firstOrNull { it.uid == p } ?: return@forEachIndexed
                    connect(group, listOf(listOf(parent)), lift = pi * 8f)
                }
            }
            val siblings = NameEntry.ids(me.siblings).filter { it != uid }
            val selfRows = row(listOf(uid to TreeKind.SELF) + siblings.map { it to TreeKind.KIN })
            connect(parentRows.flatten(), selfRows)
            val self = nodes.first { it.kind == TreeKind.SELF }
            val spouseRows = row(NameEntry.ids(me.partners).map { it to TreeKind.SPOUSE })
            connect(listOf(self), spouseRows)
            val childRows = row(NameEntry.ids(me.children).map { it to TreeKind.KIN })
            connect(if (spouseRows.size == 1) spouseRows.first() else listOf(self), childRows)
            return FamilyTree(me.name, nodes, lines, couples, WIDTH, (y - ROW_GAP).coerceAtLeast(NODE_H))
        }
    }
}

/**
 * A family tree in a window (STD-16). Tap anyone to see their family; *Copy to sketch page*
 * draws it onto the sketch page being viewed, or a new one.
 */
@Composable
fun FamilyTreeDialog(vm: StudyViewModel, startUid: String, onDismiss: () -> Unit) {
    var uid by remember { mutableStateOf(startUid) }
    val tree by produceState<FamilyTree?>(null, uid) { value = withContext(Dispatchers.IO) { FamilyTree.build(vm.study, uid) } }
    val scope = rememberCoroutineScope()
    BigDialog(onDismiss) {
        Column {
            DialogTitle(tree?.let { "Family of ${it.name}" } ?: "Family tree", onDismiss)
            Text(
                "Tap anyone to see their family.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            val t = tree
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                if (t != null) TreeView(t) { n ->
                    scope.launch {
                        val found = withContext(Dispatchers.IO) { vm.study.nameByUid(n.uid) }
                        if (found != null) uid = n.uid else vm.message = "No entry for ${n.label}."
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(FamilyTree.KEY.substringBefore(". From"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.openNameUid(uid); onDismiss() }) { Text("About ${t?.name ?: ""}") }
                Button(enabled = t != null, onClick = {
                    val tr = t ?: return@Button
                    vm.copyTreeToSketch(tr)
                    onDismiss()
                }) { Text("Copy to sketch page") }
            }
        }
    }
}

@Composable
private fun TreeView(t: FamilyTree, onTap: (TreeNode) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val availPx = with(density) { maxWidth.toPx() }
        val scale = (availPx / t.width).coerceAtMost(1.6f)
        val line = MaterialTheme.colorScheme.outline
        Box(Modifier.size(with(density) { (t.width * scale).toDp() }, with(density) { (t.height * scale).toDp() }).testTag("familyTree")) {
            Canvas(Modifier.matchParentSize()) {
                for ((a, b) in t.couples) {
                    val y = (a.y + a.h / 2) * scale
                    drawLine(line, Offset((a.x + a.w) * scale, y), Offset(b.x * scale, y), strokeWidth = 2f)
                }
                for (l in t.lines) for (i in 1 until l.size) {
                    drawLine(line, Offset(l[i - 1].first * scale, l[i - 1].second * scale), Offset(l[i].first * scale, l[i].second * scale), strokeWidth = 2f)
                }
            }
            for (n in t.nodes) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(n.kind.background).compositeOverSurface(),
                    border = BorderStroke(if (n.kind == TreeKind.SELF) 2.dp else 1.dp, line),
                    modifier = Modifier
                        .offset(with(density) { (n.x * scale).toDp() }, with(density) { (n.y * scale).toDp() })
                        .size(with(density) { (n.w * scale).toDp() }, with(density) { (n.h * scale).toDp() })
                        .clickable { onTap(n) }
                        .testTag("treeNode"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            n.label, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = (FamilyTree.TEXT * scale / density.fontScale / density.density).coerceIn(10f, 20f).sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Color.compositeOverSurface(): Color {
    val s = MaterialTheme.colorScheme.surface
    val a = alpha
    return Color(red * a + s.red * (1 - a), green * a + s.green * (1 - a), blue * a + s.blue * (1 - a))
}
