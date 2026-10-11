package com.biblestudy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.biblestudy.app.platform.LocalCopies
import com.biblestudy.app.platform.PickedFile
import com.biblestudy.app.platform.onMain
import com.biblestudy.app.platform.readBytes
import com.biblestudy.app.platform.topViewController
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeZIP
import platform.darwin.NSObject

/** The kinds of file iOS offers for [mimeTypes]. */
private fun typesFor(mimeTypes: List<String>): List<UTType> = mimeTypes.mapNotNull { m ->
    when {
        m.startsWith("image/") -> UTTypeImage
        m == "application/zip" -> UTTypeZIP
        else -> UTType.typeWithMIMEType(m)
    }
}.distinct().ifEmpty { listOf(UTTypeData) }

/** Shows the Files picker; the chosen files come back as copies the app may read. */
private class OpenPicker(private val onPicked: (List<PickedFile>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    fun show(types: List<UTType>, multiple: Boolean) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
        picker.allowsMultipleSelection = multiple
        picker.delegate = this
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPicked(didPickDocumentsAtURLs.mapNotNull { (it as? NSURL)?.path?.let { p -> PickedFile(LocalCopies.keep(p)) } })
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onPicked(emptyList())
}

@Composable
actual fun rememberOpenFiles(multiple: Boolean, onPicked: (List<PickedFile>) -> Unit): (mimeTypes: List<String>) -> Unit {
    val picker = remember { OpenPicker { onPicked(it) } }
    return { types -> picker.show(typesFor(types), multiple) }
}

/**
 * Saving: the app writes to a temporary file named [suggestedName], then iOS asks where to keep
 * it (see IosHost.create).
 */
@Composable
actual fun rememberSaveFile(mime: String, onPicked: (PickedFile?) -> Unit): (suggestedName: String) -> Unit = { name ->
    onPicked(PickedFile(LocalCopies.saveTarget(name)))
}

/** The photo library; the chosen photo comes back as a file. */
private class PhotoPicker(private val onPicked: (PickedFile?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    fun show() {
        val config = PHPickerConfiguration().apply { filter = PHPickerFilter.imagesFilter; selectionLimit = 1 }
        val picker = PHPickerViewController(configuration = config)
        picker.delegate = this
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult ?: return onPicked(null)
        result.itemProvider.loadFileRepresentationForTypeIdentifier(UTTypeImage.identifier) { url, _ ->
            // The file is only there during this call: keep a copy.
            val copy = url?.path?.let { runCatching { LocalCopies.keep(it) }.getOrNull() }
            onMain { onPicked(copy?.let { PickedFile(it) }) }
        }
    }
}

@Composable
actual fun rememberPickImage(onPicked: (PickedFile?) -> Unit): () -> Unit {
    val picker = remember { PhotoPicker { onPicked(it) } }
    return { picker.show() }
}

@Composable
actual fun rememberTakePhoto(onTaken: (Boolean) -> Unit): (into: PickedFile) -> Unit = {
    throw UnsupportedOperationException("The camera isn't available in Ink & Word on iPad and iPhone yet")
}

/** Automatic backups go to the app's own folder in Files; other folders aren't offered yet. */
@Composable
actual fun rememberPickFolder(onPicked: (PickedFile?) -> Unit): () -> Unit = { onPicked(null) }

@Composable
actual fun rememberDriveSignIn(onResult: (Any?) -> Unit): (request: Any) -> Unit = { onResult(null) }

/** The share sheet (over the screen's middle on an iPad). */
@Composable
actual fun rememberShareText(): (text: String) -> Unit = { text ->
    val top = topViewController()
    if (top != null) {
        val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        sheet.popoverPresentationController?.sourceView = top.view
        top.presentViewController(sheet, animated = true, completion = null)
    }
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

/** Gentium Book Plus, from the app's bundle (as on Android and computers). */
actual val bibleFont: FontFamily = runCatching {
    fun font(name: String, weight: FontWeight) =
        Font(name, ((NSBundle.mainBundle.resourcePath ?: "").toPath() / "font" / "$name.ttf").readBytes(), weight)
    FontFamily(font("gentium_book_plus_regular", FontWeight.Normal), font("gentium_book_plus_bold", FontWeight.Bold))
}.getOrDefault(FontFamily.Serif)

/** No camera yet: the photo library, Files, or a copied picture. */
actual val imageSourcesHere: List<ImageSource> = listOf(ImageSource.GALLERY, ImageSource.FILES, ImageSource.CLIPBOARD)
