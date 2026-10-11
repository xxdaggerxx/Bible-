package com.biblestudy.app.platform

import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The computer's side of [Host]: picked files and folders are paths. */
class DesktopHost(override val ctx: DesktopAppContext = DesktopAppContext()) : Host {
    /** Computers are laid out like a large tablet. */
    override val smallestWidthDp: Int get() = 800

    /** Checks every half minute whether the internet is back. */
    override fun watchNetwork(onAvailable: () -> Unit) {
        Thread({
            var was = true
            while (true) {
                Thread.sleep(30_000)
                val now = runCatching { java.net.InetAddress.getByName("www.googleapis.com"); true }.getOrDefault(false)
                if (now && !was) runCatching(onAvailable)
                was = now
            }
        }, "network").apply { isDaemon = true }.start()
    }

    private fun path(f: PickedFile) = f.id.toPath()

    override fun open(file: PickedFile): Source = fs.source(path(file))
    override fun create(file: PickedFile): Sink = path(file).let { p -> p.parent?.let { fs.createDirectories(it) }; fs.sink(p) }
    override fun displayName(file: PickedFile): String? = path(file).name
    override fun isImage(file: PickedFile): Boolean =
        path(file).name.lowercase().substringAfterLast('.') in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

    /** A picture on the clipboard (copied from a browser or a screenshot), saved as a PNG to read. */
    override fun clipboardImage(): PickedFile? = runCatching {
        val clip = Toolkit.getDefaultToolkit().systemClipboard
        when {
            clip.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
                val img = clip.getData(DataFlavor.imageFlavor) as java.awt.Image
                val b = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                b.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
                val out = File(ctx.cacheDir.toFile(), "clipboard.png")
                ImageIO.write(b, "png", out)
                PickedFile(out.absolutePath)
            }
            clip.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) ->
                (clip.getData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().firstOrNull()
                    ?.let { PickedFile(it.absolutePath) }?.takeIf { isImage(it) }
            else -> null
        }
    }.getOrNull()

    override fun newCameraFile(): PickedFile = PickedFile((ctx.cacheDir / "photo.jpg").toString())

    override fun folderName(folder: PickedFile): String = path(folder).name.ifEmpty { folder.id }
    override fun writeInFolder(folder: PickedFile, name: String): Sink = fs.sink(path(folder) / name)
    override fun listFolder(folder: PickedFile, prefix: String): List<String> =
        runCatching { fs.list(path(folder)).map { it.name }.filter { it.startsWith(prefix) } }.getOrDefault(emptyList())
    override fun deleteInFolder(folder: PickedFile, name: String) { runCatching { fs.delete(path(folder) / name) } }

    override val drive: DriveSignIn by lazy { DesktopDriveSignIn(ctx.prefs("driveAuth")) }
}
