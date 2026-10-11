package com.biblestudy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.biblestudy.app.platform.PickedFile
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser

/** File dialogs on computers: picked files are paths. */
private fun fileDialog(save: Boolean, name: String? = null, multiple: Boolean = false, extensions: List<String> = emptyList()): List<File> {
    val d = FileDialog(null as Frame?, if (save) "Save" else "Open", if (save) FileDialog.SAVE else FileDialog.LOAD)
    d.isMultipleMode = multiple
    if (name != null) d.file = name
    if (extensions.isNotEmpty()) d.setFilenameFilter { _, n -> extensions.any { n.lowercase().endsWith(it) } }
    d.isVisible = true
    return d.files.toList()
}

private fun extensionsFor(mimeTypes: List<String>): List<String> = mimeTypes.flatMap { m ->
    when {
        m.startsWith("image/") -> listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")
        m == "application/zip" -> listOf(".zip")
        else -> emptyList()
    }
}.let { if (mimeTypes.any { it == "*/*" || it == "application/octet-stream" }) emptyList() else it }

@Composable
actual fun rememberOpenFiles(multiple: Boolean, onPicked: (List<PickedFile>) -> Unit): (mimeTypes: List<String>) -> Unit = { types ->
    onPicked(fileDialog(save = false, multiple = multiple, extensions = extensionsFor(types)).map { PickedFile(it.absolutePath) })
}

@Composable
actual fun rememberSaveFile(mime: String, onPicked: (PickedFile?) -> Unit): (suggestedName: String) -> Unit = { name ->
    onPicked(fileDialog(save = true, name = name).firstOrNull()?.let { PickedFile(it.absolutePath) })
}

@Composable
actual fun rememberPickImage(onPicked: (PickedFile?) -> Unit): () -> Unit = {
    onPicked(fileDialog(save = false, extensions = extensionsFor(listOf("image/*"))).firstOrNull()?.let { PickedFile(it.absolutePath) })
}

@Composable
actual fun rememberTakePhoto(onTaken: (Boolean) -> Unit): (into: PickedFile) -> Unit = {
    throw UnsupportedOperationException("No camera on this computer")
}

@Composable
actual fun rememberPickFolder(onPicked: (PickedFile?) -> Unit): () -> Unit = {
    val c = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Folder for automatic backups" }
    onPicked(if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) PickedFile(c.selectedFile.absolutePath) else null)
}

@Composable
actual fun rememberDriveSignIn(onResult: (Any?) -> Unit): (request: Any) -> Unit = { request ->
    // The desktop sign-in runs in the browser and hands back its result itself (see DesktopDriveSignIn).
    onResult(request)
}

/** Computers have no share sheet: the text goes to the clipboard. */
@Composable
actual fun rememberShareText(): (text: String) -> Unit = { text ->
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun smallestScreenWidthDp(): Int {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    return (minOf(size.width, size.height) / density).toInt()
}

actual val bibleFont: FontFamily = FontFamily(
    Font("font/gentium_book_plus_regular.ttf"),
    Font("font/gentium_book_plus_bold.ttf", FontWeight.Bold),
)
