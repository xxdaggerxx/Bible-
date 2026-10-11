package com.biblestudy.app.ui

import android.content.Intent
import android.content.IntentSender
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import android.net.Uri
import com.biblestudy.app.platform.PickedFile
import com.biblestudy.app.shared.R

private fun Uri.picked() = PickedFile(toString())

@Composable
actual fun rememberOpenFiles(multiple: Boolean, onPicked: (List<PickedFile>) -> Unit): (mimeTypes: List<String>) -> Unit {
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onPicked(uris.map { it.picked() }) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onPicked(listOfNotNull(uri?.picked())) }
    return { types -> (if (multiple) many else one).launch(types.toTypedArray()) }
}

@Composable
actual fun rememberSaveFile(mime: String, onPicked: (PickedFile?) -> Unit): (suggestedName: String) -> Unit {
    val l = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri -> onPicked(uri?.picked()) }
    return { name -> l.launch(name) }
}

@Composable
actual fun rememberPickImage(onPicked: (PickedFile?) -> Unit): () -> Unit {
    val l = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> onPicked(uri?.picked()) }
    return { l.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
actual fun rememberTakePhoto(onTaken: (Boolean) -> Unit): (into: PickedFile) -> Unit {
    val l = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> onTaken(ok) }
    return { into -> l.launch(Uri.parse(into.id)) }
}

@Composable
actual fun rememberPickFolder(onPicked: (PickedFile?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Keep access to the folder after the app restarts.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        onPicked(uri?.picked())
    }
    return { l.launch(null) }
}

@Composable
actual fun rememberDriveSignIn(onResult: (Any?) -> Unit): (request: Any) -> Unit {
    val l = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        onResult(if (r.resultCode == android.app.Activity.RESULT_OK) r.data else null)
    }
    return { request -> l.launch(IntentSenderRequest.Builder(request as IntentSender).build()) }
}

@Composable
actual fun rememberShareText(): (text: String) -> Unit {
    val context = LocalContext.current
    return { text ->
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }
}

@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled = enabled, onBack = onBack)

@Composable
actual fun smallestScreenWidthDp(): Int = LocalConfiguration.current.smallestScreenWidthDp

actual val bibleFont: FontFamily = FontFamily(
    Font(R.font.gentium_book_plus_regular),
    Font(R.font.gentium_book_plus_bold, FontWeight.Bold),
)
