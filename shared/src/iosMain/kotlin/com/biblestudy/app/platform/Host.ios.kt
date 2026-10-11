package com.biblestudy.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.Buffer
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.Timeout
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iPad and iPhone's side of [Host]. Picked files are copies in the app's own folders (the file
 * pickers hand over copies), so their ids are paths. A file to save is written to a temporary
 * file first; when it's finished, iOS asks where to put it.
 */
class IosHost(override val ctx: IosAppContext = IosAppContext()) : Host {
    @OptIn(ExperimentalForeignApi::class)
    override val smallestWidthDp: Int
        get() = UIScreen.mainScreen.bounds.useContents { minOf(size.width, size.height).toInt() }

    /** Checks every half minute whether the internet is back. */
    override fun watchNetwork(onAvailable: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var was = true
            while (true) {
                delay(30_000)
                val now = runCatching { httpRequest("https://www.googleapis.com/generate_204", connectTimeoutMs = 5_000, readTimeoutMs = 5_000); true }.getOrDefault(false)
                if (now && !was) runCatching(onAvailable)
                was = now
            }
        }
    }

    private fun path(f: PickedFile) = f.id.toPath()

    override fun open(file: PickedFile): Source = fs.source(path(file))

    /** Written to the temporary file, then offered to save where the user chooses. */
    override fun create(file: PickedFile): Sink {
        val p = path(file)
        p.parent?.let { fs.createDirectories(it) }
        val out = fs.sink(p)
        return object : Sink by out {
            private var closed = false
            override fun write(source: Buffer, byteCount: Long) = out.write(source, byteCount)
            override fun timeout(): Timeout = out.timeout()
            override fun close() {
                out.close()
                if (closed) return
                closed = true
                if (p.toString().startsWith((ctx.cacheDir / "save").toString())) onMain { exportFile(p.toString()) }
            }
        }
    }

    override fun displayName(file: PickedFile): String? = path(file).name
    override fun isImage(file: PickedFile): Boolean =
        path(file).name.lowercase().substringAfterLast('.') in setOf("png", "jpg", "jpeg", "gif", "heic", "heif", "webp", "bmp", "tif", "tiff")

    /** A picture on the clipboard, saved as a PNG to read. */
    override fun clipboardImage(): PickedFile? {
        val image = UIPasteboard.generalPasteboard.image ?: return null
        val png = UIImagePNGRepresentation(image) ?: return null
        val p = ctx.cacheDir / "clipboard.png"
        p.writeBytes(png.toByteArray())
        return PickedFile(p.toString())
    }

    override fun newCameraFile(): PickedFile = PickedFile((ctx.cacheDir / "camera" / "photo-${nowMillis()}.jpg").toString())

    // Automatic backups go to the app's Documents folder (see AppContext); other folders aren't offered on iOS.
    override fun folderName(folder: PickedFile): String = path(folder).name
    override fun writeInFolder(folder: PickedFile, name: String): Sink = fs.sink(path(folder) / name)
    override fun listFolder(folder: PickedFile, prefix: String): List<String> =
        runCatching { fs.list(path(folder)).map { it.name }.filter { it.startsWith(prefix) } }.getOrDefault(emptyList())
    override fun deleteInFolder(folder: PickedFile, name: String) { runCatching { fs.delete(path(folder) / name) } }

    /** Sync needs Google sign-in set up for iOS, which isn't done yet. */
    override val drive: DriveSignIn = object : DriveSignIn {
        override suspend fun authorize() = DriveSignIn.Result.Failed("sync with Google Drive isn't available on iPad and iPhone yet")
        override fun tokenFrom(data: Any?): String? = null
    }

    private fun exportFile(path: String) {
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(path)), asCopy = true)
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

/** Runs [block] on the main thread. */
fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { block() }

/** The screen shown now, to show a picker or share sheet over it. */
fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

/** Copies of picked files, and temporary files to save from, in the app's cache folder. */
object LocalCopies {
    private val root by lazy { IosAppContext().cacheDir }

    /** Copies the file at [path] (which iOS may remove) into the app's cache; returns the copy's path. */
    fun keep(path: String): String {
        val src = path.toPath()
        val dir = root / "picked" / randomHex(8)
        fs.createDirectories(dir)
        val dst = dir / src.name
        fs.copy(src, dst)
        return dst.toString()
    }

    /** A temporary file named [name] to write, then save where the user chooses. */
    fun saveTarget(name: String): String {
        val dir = root / "save"
        runCatching { fs.deleteRecursively(dir) } // earlier ones have been saved
        fs.createDirectories(dir)
        return (dir / name).toString()
    }
}
