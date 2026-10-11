package com.biblestudy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.biblestudy.app.platform.PickedFile

/*
 * What screens need from the platform: system file pickers, the camera, sharing text, the back
 * button and the Bible text's typeface. Each platform supplies them (Android: its activity results).
 */

/** Opens the system picker for files of the given MIME types (star/star for any); calls [onPicked] with what was chosen. */
@Composable
expect fun rememberOpenFiles(multiple: Boolean = false, onPicked: (List<PickedFile>) -> Unit): (mimeTypes: List<String>) -> Unit

/** Asks where to save a file of [mime], suggesting a name; calls [onPicked] with the place, or null. */
@Composable
expect fun rememberSaveFile(mime: String, onPicked: (PickedFile?) -> Unit): (suggestedName: String) -> Unit

/** The system's photo picker. */
@Composable
expect fun rememberPickImage(onPicked: (PickedFile?) -> Unit): () -> Unit

/** Takes a photo into a file; [onTaken] says whether it worked. Throws if there's no camera. */
@Composable
expect fun rememberTakePhoto(onTaken: (Boolean) -> Unit): (into: PickedFile) -> Unit

/** Lets the user choose a folder (for automatic backups). */
@Composable
expect fun rememberPickFolder(onPicked: (PickedFile?) -> Unit): () -> Unit

/** Shows Google's sign-in screen for sync, given what DriveSignIn asked for; [onResult] gets its answer. */
@Composable
expect fun rememberDriveSignIn(onResult: (Any?) -> Unit): (request: Any) -> Unit

/** Offers text to other apps (the system share sheet). */
@Composable
expect fun rememberShareText(): (text: String) -> Unit

/** Handles the system Back gesture or button while [enabled]. */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)

/** The screen's smallest width in dp (phones are under 600). */
@Composable
expect fun smallestScreenWidthDp(): Int

/** Gentium Book Plus, the Bible text's typeface (READ-3). */
expect val bibleFont: FontFamily

/** The app's files, bundled data and settings, for screens that don't hold the view model. */
val LocalAppContext = androidx.compose.runtime.staticCompositionLocalOf<com.biblestudy.app.platform.AppContext> {
    error("No AppContext provided")
}
