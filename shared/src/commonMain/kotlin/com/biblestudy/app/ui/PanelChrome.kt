package com.biblestudy.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.runtime.key
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The menu behind a panel's view button (SPLIT-7): what the panel shows, how the tab's two panels
 * are arranged, opening it in a new tab, pinning a study view, and closing it.
 */
@Composable
fun PanelViewMenu(vm: StudyViewModel, slot: Slot, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val current = (slot as? Slot.Study)?.kind
        ViewChoices(
            vm, current, bible = true,
            disabled = { k -> k != current && k in vm.tab.studies && k != PaneKind.COMMENTARY },
            onBible = { onDismiss(); if (current != null) vm.setSlotView(slot, null) },
            onPick = { k -> onDismiss(); if (k != current) vm.setSlotView(slot, k) },
        )
        HorizontalDivider()
        if (vm.tab.shown < 2) {
            DropdownMenuItem(text = { Text("Add a panel beside") }, onClick = { onDismiss(); vm.addPanel() })
        } else if (!vm.phone) {
            // One item: switch to the other arrangement. (Phones choose by how they're held.)
            val stacked = vm.isStacked()
            DropdownMenuItem(
                text = { Text(if (stacked) "Side by side" else "Top and bottom") },
                onClick = { onDismiss(); vm.setStacked(!stacked) },
            )
        }
        DropdownMenuItem(text = { Text("Open in new tab") }, onClick = { onDismiss(); vm.openSlotInNewTab(slot) })
        if (current != null && !vm.tab.bibleHidden) {
            DropdownMenuItem(
                text = { Text(if (vm.tab.pinned != null) "Follow my reading" else "Keep on this passage") },
                onClick = { onDismiss(); vm.togglePin() },
            )
        }
        if (vm.tab.shown > 1 || vm.tabs.size > 1) {
            DropdownMenuItem(text = { Text(if (vm.tab.shown > 1) "Close panel" else "Close tab") }, onClick = { onDismiss(); vm.closeSlot(slot) })
        }
        // Tabs and layouts (TAB-1, NAV-2, SPLIT-6): here since the toolbar's panels button went (UI-5).
        HorizontalDivider()
        DropdownMenuItem(text = { Text("New tab") }, onClick = { onDismiss(); vm.newTab() })
        if (vm.tabs.size > 1 || vm.tab.shown > 1) DropdownMenuItem(
            text = { Text("Start fresh") },
            leadingIcon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
            onClick = { onDismiss(); vm.startFresh(); vm.message = "One panel again. Back (\u2190) undoes it." },
        )
        LayoutChoices(vm, onDismiss)
        HorizontalDivider()
        // Help about what this panel shows (DOC-2).
        val help = LocalHelp.current
        DropdownMenuItem(
            text = { Text("Help: ${current?.label ?: "panels"}") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
            onClick = { onDismiss(); help(HelpTopic.of(current)) },
        )
    }
}

/**
 * Saved layouts (SPLIT-6), folded under *Layouts*: open or delete one, or save what's open now.
 */
@Composable
private fun LayoutChoices(vm: StudyViewModel, onDismiss: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    DropdownMenuItem(
        text = { Text("Layouts") }, onClick = { open = !open },
        leadingIcon = { Icon(Icons.Outlined.SpaceDashboard, contentDescription = null) },
        trailingIcon = { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null) },
        modifier = Modifier.testTag("folder:Layouts"),
    )
    if (open) {
        val inset = PaddingValues(start = 56.dp, end = 12.dp)
        for (w in vm.workspaces) DropdownMenuItem(
            text = { Text(w.name) }, contentPadding = inset,
            onClick = { onDismiss(); vm.openWorkspace(w) },
            trailingIcon = {
                IconButton(onClick = { vm.deleteWorkspace(w) }) { Icon(Icons.Filled.Close, contentDescription = "Delete layout ${w.name}") }
            },
        )
        DropdownMenuItem(text = { Text("Save this layout\u2026") }, contentPadding = inset, onClick = { naming = true })
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save this layout") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text("e.g. Gospels side by side") },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.saveWorkspace(name); naming = false; onDismiss() }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The views to choose from in a menu (SPLIT-7): the Bible, Sketch pages, My notes, Search and AI
 * chat each on a line with an icon, then *Study*, which folds open to its views and starts open
 * when it holds [current]. A view no longer in the menu ([PaneKind.offMenu]) is listed while shown.
 */
@Composable
fun ViewChoices(
    vm: StudyViewModel,
    current: PaneKind?,
    bible: Boolean,
    disabled: (PaneKind) -> Boolean = { false },
    onBible: () -> Unit = {},
    onPick: (PaneKind) -> Unit,
) {
    val check: @Composable () -> Unit = { Icon(Icons.Filled.Check, contentDescription = "Showing") }
    if (bible) DropdownMenuItem(
        text = { Text("Bible") }, onClick = onBible,
        leadingIcon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
        trailingIcon = if (current == null) check else null,
    )
    val kinds = if (current != null && current in PaneKind.offMenu) listOf(current) + PaneKind.main else PaneKind.main
    for (k in kinds) {
        if (k == PaneKind.CHAT && !vm.chat.enabled && current != k) continue
        DropdownMenuItem(
            text = { Text(k.label) }, enabled = !disabled(k), onClick = { onPick(k) },
            leadingIcon = { Icon(viewIcon(k), contentDescription = null) },
            trailingIcon = if (k == current) check else null,
        )
    }
    var open by remember { mutableStateOf(PaneKind.folders.indexOfFirst { current in it.second }) }
    for ((i, folder) in PaneKind.folders.withIndex()) {
        val (heading, kinds) = folder
        val here = kinds.firstOrNull { it == current }
        DropdownMenuItem(
            text = {
                Text(
                    if (here != null && open != i) "$heading \u00b7 ${here.label}" else heading,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            onClick = { open = if (open == i) -1 else i },
            modifier = Modifier.testTag("folder:$heading"),
            leadingIcon = { Icon(Icons.Filled.School, contentDescription = null) },
            trailingIcon = { Icon(if (open == i) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (open == i) "Fold" else "Open") },
        )
        if (open == i) for (k in kinds) DropdownMenuItem(
            text = { Text(k.label) }, enabled = !disabled(k), onClick = { onPick(k) },
            contentPadding = PaddingValues(start = 56.dp, end = 12.dp),
            trailingIcon = if (k == current) check else null,
        )
    }
}

private fun viewIcon(k: PaneKind): ImageVector = when (k) {
    PaneKind.SKETCHES -> Icons.Filled.Draw
    PaneKind.NOTES -> Icons.Filled.EditNote
    PaneKind.HIGHLIGHTS -> Icons.Filled.BorderColor
    PaneKind.SEARCH -> Icons.Filled.Search
    PaneKind.CHAT -> Icons.AutoMirrored.Filled.Chat
    else -> Icons.Filled.MenuBook
}

/** The button that opens [PanelViewMenu]. */
@Composable
fun PanelViewButton(vm: StudyViewModel, slot: Slot, onOpen: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { onOpen(); menu = true }) {
            Icon(Icons.Outlined.SpaceDashboard, contentDescription = "Panel view")
        }
        PanelViewMenu(vm, slot, menu) { menu = false }
    }
}

/**
 * The tabs (TAB-1, TAB-2), shown once there are two or more. Tap a tab to bring it to the front;
 * hold a finger on it to rename, move or close it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabStrip(vm: StudyViewModel) {
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).testTag("tabs")
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        vm.tabs.forEachIndexed { i, t ->
            val front = i == vm.activeTab
            // Hold a finger on a tab: let go for its menu, or drag it along the strip to move it (TAB-2).
            var dragX by remember(t) { mutableStateOf(0f) }
            key(t) { Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(dragX.toInt(), 0) }.zIndex(if (dragX != 0f) 1f else 0f)) {
                Text(
                    vm.tabLabel(t),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (front) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (front) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { vm.selectTab(i) }
                        .pointerInput(t) {
                            var moved = 0f
                            val step = 96.dp.toPx()
                            detectDragGesturesAfterLongPress(
                                onDragStart = { moved = 0f; dragX = 0f },
                                onDrag = { change, d ->
                                    change.consume()
                                    moved += kotlin.math.abs(d.x)
                                    dragX += d.x
                                    val at = vm.tabs.indexOf(t)
                                    if (dragX > step && at < vm.tabs.lastIndex) { vm.moveTab(at, 1); dragX -= step }
                                    if (dragX < -step && at > 0) { vm.moveTab(at, -1); dragX += step }
                                },
                                onDragEnd = { if (moved < 8f) menuFor = vm.tabs.indexOf(t); dragX = 0f },
                                onDragCancel = { dragX = 0f },
                            )
                        }
                        .semantics { contentDescription = "Tab ${i + 1}: ${vm.tabLabel(t)}" }
                        .widthIn(max = 220.dp)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                DropdownMenu(expanded = menuFor == i, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("Rename…") }, onClick = { menuFor = null; renaming = i })
                    DropdownMenuItem(text = { Text("Move left") }, enabled = i > 0, onClick = { menuFor = null; vm.moveTab(i, -1) })
                    DropdownMenuItem(text = { Text("Move right") }, enabled = i < vm.tabs.lastIndex, onClick = { menuFor = null; vm.moveTab(i, 1) })
                    DropdownMenuItem(text = { Text("Close tab") }, onClick = { menuFor = null; vm.closeTab(i) })
                }
            } }
        }
        IconButton(onClick = { vm.newTab() }) { Icon(Icons.Filled.Add, contentDescription = "New tab") }
    }
    renaming?.let { i ->
        var name by remember(i) { mutableStateOf(vm.tabs.getOrNull(i)?.name ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this tab") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text(vm.tabs.getOrNull(i)?.let { t -> t.name ?: vm.tabLabel(t) } ?: "") },
                )
            },
            confirmButton = { TextButton(onClick = { vm.renameTab(i, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
