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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.EditNote
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
import com.biblestudy.app.model.TextFont
import com.biblestudy.app.model.Tool

@Composable
fun StudyToolbar(
    vm: StudyViewModel,
    onLayers: () -> Unit,
    onSearch: () -> Unit,
    onNotes: () -> Unit,
    onInsertImage: (ImageSource) -> Unit,
    onBackup: () -> Unit,
    onExport: (pdf: Boolean) -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onAbout: () -> Unit,
) {
    // Kept short so it fits without scrolling (UI-3): tools, one button for the tool's colour and
    // size, undo/redo, layers, Insert, panels, search, my notes and a small menu. Everything
    // else lives in Settings.
    Surface(tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Read mode (PEN-4): a lock that stops the pen marking the page.
            IconToggleButton(
                checked = vm.readMode,
                onCheckedChange = {
                    vm.readMode = it
                    vm.message = if (it) "Read mode: the pen scrolls the page and won't mark it." else "Read mode off."
                },
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    if (vm.readMode) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (vm.readMode) "Read mode on" else "Read mode off",
                )
            }
            for (t in Tool.entries) {
                IconToggleButton(
                    checked = vm.tool == t && !vm.readMode,
                    enabled = !vm.readMode,
                    onCheckedChange = { vm.tool = t },
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) { Icon(t.icon(), contentDescription = t.label) }
            }
            if (!vm.readMode) ToolOptions(vm)
            Divider()
            IconButton(onClick = vm::undo, enabled = vm.canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = vm::redo, enabled = vm.canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
            Divider()
            AssistChip(
                onClick = onLayers,
                label = { Text(vm.activeLayer()?.name ?: "Layers", maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.Layers, contentDescription = "Layers") },
            )
            // Insert: pictures (MRG-7); text boxes and shapes join here (UI-1).
            var insertMenu by remember { mutableStateOf(false) }
            var insertDialog by remember { mutableStateOf<String?>(null) }
            when (insertDialog) {
                "sketch" -> NewSketchDialog(vm) { insertDialog = null }
                "verse" -> VerseCardDialog(vm) { insertDialog = null }
                "name" -> NameCardDialog(vm) { insertDialog = null }
            }
            Box {
                IconButton(onClick = { insertMenu = true }) { Icon(Icons.Filled.AddBox, contentDescription = "Insert") }
                DropdownMenu(expanded = insertMenu, onDismissRequest = { insertMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Text box") },
                        onClick = { insertMenu = false; vm.insertTextBox() },
                    )
                    // Cards are filled-in text boxes (SKT-4); sketch pages are new pages (SKT-1).
                    DropdownMenuItem(text = { Text("Verse card\u2026") }, onClick = { insertMenu = false; insertDialog = "verse" })
                    DropdownMenuItem(text = { Text("Person or place card\u2026") }, onClick = { insertMenu = false; insertDialog = "name" })
                    DropdownMenuItem(text = { Text("Sketch page\u2026") }, onClick = { insertMenu = false; insertDialog = "sketch" })
                    HorizontalDivider()
                    for (src in ImageSource.entries) {
                        DropdownMenuItem(
                            text = { Text("Picture: " + src.label.replaceFirstChar { it.lowercase() }) },
                            onClick = { insertMenu = false; onInsertImage(src) },
                        )
                    }
                }
            }
            // Panels: more Bible panels (ADP-3), the study pane (SPLIT-2) and the margins.
            var panelsMenu by remember { mutableStateOf(false) }
            var naming by remember { mutableStateOf(false) }
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
                        TextButton(onClick = { vm.saveWorkspace(name); naming = false }, enabled = name.isNotBlank()) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
                )
            }
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
                    HorizontalDivider()
                    // Saved layouts (SPLIT-6).
                    for (w in vm.workspaces) {
                        DropdownMenuItem(
                            text = { Text("Layout: ${w.name}") },
                            onClick = { vm.openWorkspace(w); panelsMenu = false },
                            trailingIcon = {
                                IconButton(onClick = { vm.deleteWorkspace(w) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Delete layout ${w.name}")
                                }
                            },
                        )
                    }
                    DropdownMenuItem(text = { Text("Save this layout\u2026") }, onClick = { panelsMenu = false; naming = true })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Left margin" + if (vm.marginLeft) "  \u2713" else "") },
                        onClick = { vm.marginLeft = !vm.marginLeft; panelsMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Right margin" + if (vm.marginRight) "  \u2713" else "") },
                        onClick = { vm.marginRight = !vm.marginRight; panelsMenu = false },
                    )
                }
            }
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
            IconButton(onClick = onNotes) { Icon(Icons.Filled.EditNote, contentDescription = "My notes") }

            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        onClick = { menu = false; onSettings() },
                    )
                    DropdownMenuItem(text = { Text("Reading stats") }, onClick = { menu = false; onStats() })
                    DropdownMenuItem(text = { Text("Export chapter as PDF\u2026") }, onClick = { menu = false; onExport(true) })
                    DropdownMenuItem(text = { Text("Export chapter as picture\u2026") }, onClick = { menu = false; onExport(false) })
                    DropdownMenuItem(text = { Text("Back up my notes\u2026") }, onClick = { menu = false; onBackup() })
                    DropdownMenuItem(text = { Text("About & credits") }, onClick = { menu = false; onAbout() })
                }
            }
        }
    }
}

/** The colour and size of the current tool, behind one button (UI-3). */
@Composable
private fun ToolOptions(vm: StudyViewModel) {
    var open by remember { mutableStateOf(false) }
    val tool = vm.tool
    if (tool == Tool.LASSO || tool == Tool.SELECT) return
    Box {
        val color = when (tool) {
            Tool.PEN -> Color(vm.penColor)
            Tool.HIGHLIGHTER -> Color(vm.highlightColor)
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        IconButton(onClick = { open = true }) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(color)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .semantics { contentDescription = "${tool.label} colour and size" },
                contentAlignment = Alignment.Center,
            ) {
                if (tool == Tool.ERASER) Text(if (vm.partialEraser) "P" else "W", style = MaterialTheme.typography.labelSmall)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tool) {
                    Tool.PEN -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in PEN_COLORS) Swatch(c, vm.penColor == c) { vm.penColor = c }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SizePicker(vm.penSize) { vm.penSize = it } }
                    }
                    Tool.HIGHLIGHTER -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in HIGHLIGHT_COLORS) Swatch(c, vm.highlightColor == c) { vm.highlightColor = c }
                        }
                        vm.meanings[vm.highlightColor]?.let { Text("This colour means: $it", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !vm.underlineMode, onClick = { vm.underlineMode = false }, label = { Text("Highlight") })
                            FilterChip(selected = vm.underlineMode, onClick = { vm.underlineMode = true; vm.snapHighlights = true }, label = { Text("Underline") })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SizePicker(vm.highlightSize) { vm.highlightSize = it }
                            FilterChip(
                                selected = vm.snapHighlights,
                                onClick = { vm.snapHighlights = !vm.snapHighlights },
                                label = { Text("Snap to words") },
                            )
                        }
                    }
                    Tool.ERASER -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !vm.partialEraser, onClick = { vm.partialEraser = false }, label = { Text("Whole strokes") })
                        FilterChip(selected = vm.partialEraser, onClick = { vm.partialEraser = true }, label = { Text("Partial") })
                    }
                    else -> {}
                }
            }
        }
    }
}

/** Toolbar icon for each tool. */
private fun Tool.icon(): ImageVector = when (this) {
    Tool.PEN -> Icons.Filled.Draw
    Tool.HIGHLIGHTER -> Icons.Filled.BorderColor
    Tool.ERASER -> EraserIcon
    Tool.LASSO -> Icons.Filled.Gesture
    Tool.SELECT -> Icons.Filled.PanTool
}

/** A simple eraser (Material has none): a tilted block with a band. */
private val EraserIcon: ImageVector by lazy {
    ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(15.1f, 3.6f); lineTo(20.4f, 8.9f); quadTo(21.2f, 9.7f, 20.4f, 10.5f)
            lineTo(11.4f, 19.5f); lineTo(19f, 19.5f); lineTo(19f, 21f); lineTo(8.4f, 21f)
            lineTo(3.6f, 16.2f); quadTo(2.8f, 15.4f, 3.6f, 14.6f); lineTo(13.5f, 3.6f); quadTo(14.3f, 2.8f, 15.1f, 3.6f); close()
            moveTo(5.2f, 15.4f); lineTo(9f, 19.2f); lineTo(10.2f, 18.1f); lineTo(6.4f, 14.3f); close()
        }
    }.build()
}

/** Where a picture for the margin comes from (MRG-7). */
enum class ImageSource(val label: String) {
    GALLERY("From the gallery"), CAMERA("Take a photo"), FILES("From files"), CLIPBOARD("Paste from clipboard")
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
