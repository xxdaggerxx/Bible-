package com.biblestudy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.biblestudy.app.model.SideButton
import com.biblestudy.app.model.Tool

@Composable
fun StudyToolbar(
    vm: StudyViewModel,
    onLayers: () -> Unit,
    onSearch: () -> Unit,
    onBookmarks: () -> Unit,
    onInsertImage: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onAbout: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Tools
            for (t in Tool.entries) {
                FilterChip(selected = vm.tool == t, onClick = { vm.tool = t }, label = { Text(t.label) })
            }
            Divider()

            // Colours and sizes for the current tool
            when (vm.tool) {
                Tool.PEN -> {
                    for (c in PEN_COLORS) Swatch(c, vm.penColor == c) { vm.penColor = c }
                    SizePicker(vm.penSize) { vm.penSize = it }
                }
                Tool.HIGHLIGHTER -> {
                    for (c in HIGHLIGHT_COLORS) Swatch(c, vm.highlightColor == c) { vm.highlightColor = c }
                    SizePicker(vm.highlightSize) { vm.highlightSize = it }
                    FilterChip(
                        selected = vm.snapHighlights,
                        onClick = { vm.snapHighlights = !vm.snapHighlights },
                        label = { Text("Snap to words") },
                    )
                }
                Tool.ERASER -> {
                    FilterChip(selected = !vm.partialEraser, onClick = { vm.partialEraser = false }, label = { Text("Whole strokes") })
                    FilterChip(selected = vm.partialEraser, onClick = { vm.partialEraser = true }, label = { Text("Partial") })
                }
                Tool.LASSO -> Text("Draw a loop around ink to select it, then drag it or use the bar", style = MaterialTheme.typography.bodySmall)
                Tool.SELECT -> Text("Drag an image to move it, its corner dot to resize", style = MaterialTheme.typography.bodySmall)
            }
            Divider()

            IconButton(onClick = vm::undo, enabled = vm.canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = vm::redo, enabled = vm.canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
            AssistChip(
                onClick = onLayers,
                label = { Text(vm.activeLayer()?.name ?: "Layers") },
                leadingIcon = { Icon(Icons.Filled.Layers, contentDescription = "Layers") },
            )
            IconButton(onClick = onInsertImage) { Icon(Icons.Filled.Image, contentDescription = "Insert image into margin") }
            Divider()

            FilterChip(selected = vm.marginLeft, onClick = { vm.marginLeft = !vm.marginLeft }, label = { Text("Left margin") })
            FilterChip(selected = vm.marginRight, onClick = { vm.marginRight = !vm.marginRight }, label = { Text("Right margin") })
            // Panels: more Bible panels (up to three on large screens, ADP-3) and the study pane (SPLIT-2).
            var panelsMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { panelsMenu = true }) {
                    Icon(Icons.Filled.VerticalSplit, contentDescription = "Panels")
                }
                DropdownMenu(expanded = panelsMenu, onDismissRequest = { panelsMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Add a Bible panel") },
                        enabled = vm.panels.size < vm.maxPanels,
                        onClick = { vm.addPanel(); panelsMenu = false },
                    )
                    if (vm.panels.size > 1) {
                        DropdownMenuItem(text = { Text("Close other panels") }, onClick = {
                            val keep = vm.panels[vm.activePanel.coerceIn(0, vm.panels.lastIndex)]
                            while (vm.panels.size > 1) vm.closePanel(vm.panels.indexOfFirst { it !== keep })
                            panelsMenu = false
                        })
                    }
                    HorizontalDivider()
                    for (k in PaneKind.entries) {
                        DropdownMenuItem(
                            text = { Text("Beside the text: ${k.label}" + if (vm.sidePane == k) "  \u2713" else "") },
                            onClick = { vm.togglePane(k); panelsMenu = false },
                        )
                    }
                }
            }
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
            IconButton(onClick = onBookmarks) { Icon(Icons.Filled.Bookmarks, contentDescription = "Bookmarks") }

            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (t in PageTheme.entries) {
                        DropdownMenuItem(
                            text = { Text("Theme: ${t.label}" + if (vm.theme == t) "  \u2713" else "") },
                            onClick = { vm.theme = t; menu = false },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Section headings" + if (vm.showHeadings) "  \u2713" else "") },
                        onClick = { vm.showHeadings = !vm.showHeadings; menu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Highlights in every version" + if (vm.highlightsAllVersions) "  \u2713" else "") },
                        onClick = { vm.highlightsAllVersions = !vm.highlightsAllVersions; menu = false },
                    )
                    for (sp in LineSpacing.entries) {
                        DropdownMenuItem(
                            text = { Text("Line spacing: ${sp.label}" + if (vm.lineSpacing == sp) "  \u2713" else "") },
                            onClick = { vm.lineSpacing = sp; menu = false },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Draw with finger" + if (vm.fingerDraw) "  \u2713" else "") },
                        onClick = { vm.fingerDraw = !vm.fingerDraw; menu = false },
                    )
                    for (b in SideButton.entries) {
                        DropdownMenuItem(
                            text = { Text("Pen button: ${b.label}" + if (vm.sideButton == b) "  \u2713" else "") },
                            onClick = { vm.sideButton = b; menu = false },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Back up my notes\u2026") }, onClick = { menu = false; onBackup() })
                    DropdownMenuItem(text = { Text("Restore from backup\u2026") }, onClick = { menu = false; onRestore() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("About & credits") }, onClick = { menu = false; onAbout() })
                }
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun Divider() {
    VerticalDivider(Modifier.height(28.dp).padding(horizontal = 2.dp))
}

@Composable
private fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun SizePicker(current: Int, onPick: (Int) -> Unit) {
    listOf("S", "M", "L").forEachIndexed { i, label ->
        FilterChip(selected = current == i, onClick = { onPick(i) }, label = { Text(label) })
    }
}
