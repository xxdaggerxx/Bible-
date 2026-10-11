package com.biblestudy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import com.biblestudy.app.platform.PickedFile

// TODO(iOS): UIDocumentPickerViewController, PHPickerViewController, UIImagePickerController,
// UIActivityViewController and the bundled Gentium font.

@Composable
actual fun rememberOpenFiles(multiple: Boolean, onPicked: (List<PickedFile>) -> Unit): (mimeTypes: List<String>) -> Unit = { onPicked(emptyList()) }

@Composable
actual fun rememberSaveFile(mime: String, onPicked: (PickedFile?) -> Unit): (suggestedName: String) -> Unit = { onPicked(null) }

@Composable
actual fun rememberPickImage(onPicked: (PickedFile?) -> Unit): () -> Unit = { onPicked(null) }

@Composable
actual fun rememberTakePhoto(onTaken: (Boolean) -> Unit): (into: PickedFile) -> Unit = { throw UnsupportedOperationException("Camera not available yet") }

@Composable
actual fun rememberPickFolder(onPicked: (PickedFile?) -> Unit): () -> Unit = { onPicked(null) }

@Composable
actual fun rememberDriveSignIn(onResult: (Any?) -> Unit): (request: Any) -> Unit = { onResult(it) }

@Composable
actual fun rememberShareText(): (text: String) -> Unit = { }

@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun smallestScreenWidthDp(): Int {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    return (minOf(size.width, size.height) / density).toInt()
}

actual val bibleFont: FontFamily = FontFamily.Serif
