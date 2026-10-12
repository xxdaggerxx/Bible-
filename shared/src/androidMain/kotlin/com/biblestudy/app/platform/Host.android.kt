package com.biblestudy.app.platform

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import okio.Sink
import okio.Source
import okio.sink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Android's side of [Host]: picked files are content:// addresses, folders are document trees. */
class AndroidHost(val context: Context) : Host {
    override val ctx = AndroidAppContext(context)
    override val smallestWidthDp: Int get() = context.resources.configuration.smallestScreenWidthDp

    override fun watchNetwork(onAvailable: () -> Unit) {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onAvailable()
            })
        }
    }

    private fun uri(f: PickedFile) = Uri.parse(f.id)

    override fun open(file: PickedFile): Source =
        (context.contentResolver.openInputStream(uri(file)) ?: throw okio.IOException("Couldn't read file")).source()

    override fun create(file: PickedFile): Sink =
        (context.contentResolver.openOutputStream(uri(file)) ?: throw okio.IOException("Couldn't write file")).sink()

    override fun displayName(file: PickedFile): String? = runCatching {
        context.contentResolver.query(uri(file), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri(file).lastPathSegment?.substringAfterLast('/')

    override fun isImage(file: PickedFile): Boolean = context.contentResolver.getType(uri(file))?.startsWith("image/") == true

    override fun clipboardImage(): PickedFile? {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val u = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return null
        val image = clip.description.hasMimeType("image/*") || context.contentResolver.getType(u)?.startsWith("image/") == true
        return if (image) PickedFile(u.toString()) else null
    }

    /** A file for the camera to save a photo into, shared with it through the app's FileProvider. */
    override fun newCameraFile(): PickedFile {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // earlier photos have already been copied
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        return PickedFile(androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file).toString())
    }

    override fun folderName(folder: PickedFile): String =
        uri(folder).lastPathSegment?.substringAfterLast(':')?.ifEmpty { null } ?: "Chosen folder"

    override fun writeInFolder(folder: PickedFile, name: String): Sink {
        val tree = uri(folder)
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(context.contentResolver, dir, "application/zip", name)
            ?: throw okio.IOException("Couldn't create the backup file")
        return create(PickedFile(doc.toString()))
    }

    /** Document ids by display name, for [listFolder] and [deleteInFolder]. */
    private fun children(tree: Uri): List<Pair<String, String>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) found += c.getString(0) to c.getString(1) }
        return found
    }

    override fun listFolder(folder: PickedFile, prefix: String): List<String> =
        children(uri(folder)).map { it.second }.filter { it.startsWith(prefix) }

    override fun deleteInFolder(folder: PickedFile, name: String) {
        val tree = uri(folder)
        children(tree).filter { it.second == name }.forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    override val drive: DriveSignIn by lazy { DriveAuth(context) }
}

actual object Pictures {
    actual fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    actual fun prepare(bytes: ByteArray, maxSide: Int): Triple<ByteArray, Boolean, Float>? = try {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val scale = maxSide.toFloat() / max(w, h).toFloat()
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val png = bmp.hasAlpha()
        val out = ByteArrayOutputStream()
        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, out)
        Triple(out.toByteArray(), png, bmp.height.toFloat() / bmp.width.toFloat())
    } catch (e: Exception) {
        null
    }
}
