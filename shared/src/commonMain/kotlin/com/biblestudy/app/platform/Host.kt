package com.biblestudy.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import okio.Sink
import okio.Source

/**
 * A file the user picked or a place they chose to save to, as the platform names it (on
 * Android a content:// address; on computers and iOS a file path or URL).
 */
data class PickedFile(val id: String)

/**
 * What the shared app needs from the operating system beyond files and settings ([AppContext]):
 * files the user picks, a chosen backup folder, pictures, the network coming back and Google
 * sign-in for sync. Each platform's app shell provides one.
 */
interface Host {
    val ctx: AppContext

    /** The screen's smallest width in dp when the app starts (phones are under 600). */
    val smallestWidthDp: Int

    /** Calls [onAvailable] (on any thread) whenever the internet comes back. */
    fun watchNetwork(onAvailable: () -> Unit)

    /** Opens a file the user picked. */
    fun open(file: PickedFile): Source

    /** Opens a place the user chose to save to. */
    fun create(file: PickedFile): Sink

    /** The file's name as the user sees it, if known. */
    fun displayName(file: PickedFile): String?

    /** Whether the file is a picture. */
    fun isImage(file: PickedFile): Boolean

    /** A picture on the clipboard, if there is one. */
    fun clipboardImage(): PickedFile?

    /** A file for the camera to save a photo into. */
    fun newCameraFile(): PickedFile

    /** A folder the user chose for automatic backups (DATA-6). */
    fun folderName(folder: PickedFile): String
    fun writeInFolder(folder: PickedFile, name: String): Sink
    /** File names in the folder that start with [prefix]; [delete] removes one by name. */
    fun listFolder(folder: PickedFile, prefix: String): List<String>
    fun deleteInFolder(folder: PickedFile, name: String)

    /** Google Drive sign-in for sync (SYNC-1). */
    val drive: DriveSignIn
}

/** Asking Google for access to the app's Drive folder. */
interface DriveSignIn {
    sealed class Result {
        class Token(val token: String) : Result()
        /** Google wants to show its screen: [request] is what the platform needs to show it. */
        class NeedsSignIn(val request: Any) : Result()
        class Failed(val reason: String) : Result()
    }

    suspend fun authorize(): Result

    /** The token from Google's screen, when it comes back with [data]. */
    fun tokenFrom(data: Any?): String?
}

/** Pictures in notes (MRG-7): decoding for the screen, and re-encoding what the user adds. */
expect object Pictures {
    /** Decodes [bytes], scaled down so its longer side is under about [maxSide] pixels. */
    fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap?

    /**
     * Reads a picture and re-encodes it no larger than [maxSide] on its longer side: PNG if it has
     * transparency, else JPEG. Returns the bytes, whether it's a PNG, and height / width.
     */
    fun prepare(bytes: ByteArray, maxSide: Int): Triple<ByteArray, Boolean, Float>?
}
