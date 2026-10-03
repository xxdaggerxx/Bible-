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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
        // The views under a few headings (SPLIT-7), the Bible first.
        for ((g, group) in PaneKind.groups.withIndex()) {
            val (heading, kinds) = group
            Text(
                heading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = if (g == 0) 8.dp else 12.dp, bottom = 2.dp),
            )
            if (g == 0) DropdownMenuItem(
                text = { Text("Bible") },
                onClick = { onDismiss(); if (current != null) vm.setSlotView(slot, null) },
                leadingIcon = if (current == null) { { Icon(Icons.Filled.Check, contentDescription = null) } } else null,
            )
            for (k in kinds) {
                if (k == PaneKind.CHAT && !vm.chat.enabled && current != k) continue
                val elsewhere = k != current && k in vm.tab.studies && k != PaneKind.COMMENTARY
                DropdownMenuItem(
                    text = { Text(k.label) },
                    enabled = !elsewhere,
                    onClick = { onDismiss(); if (k != current) vm.setSlotView(slot, k) },
                    leadingIcon = if (k == current) { { Icon(Icons.Filled.Check, contentDescription = null) } } else null,
                )
            }
        }
        HorizontalDivider()
        if (vm.tab.shown < 2) {
            DropdownMenuItem(text = { Text("Add a panel beside") }, onClick = { onDismiss(); vm.addPanel() })
        } else {
            // One item: switch to the other arrangement.
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
    }
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
