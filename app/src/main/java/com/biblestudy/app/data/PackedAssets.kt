package com.biblestudy.app.data

import android.content.Context
import java.io.File
import java.io.FileNotFoundException

/**
 * The bundled databases ship packed as xz (`bibles/kjv.db` as `bibles/kjv.db.xz`) to keep the app
 * small; the build packs them (packAssets in app/build.gradle.kts). Each is unpacked once, to app
 * storage, the first time it's needed.
 */
object PackedAssets {
    /** Writes asset [path] to [dest], unpacking it if it's packed. Writes to a temporary file first. */
    fun copy(context: Context, path: String, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        val packed = try { context.assets.open("$path.xz") } catch (_: FileNotFoundException) { null }
        if (packed != null) {
            packed.use { raw ->
                org.tukaani.xz.XZInputStream(raw.buffered(1 shl 16)).use { xz -> tmp.outputStream().use { xz.copyTo(it, 1 shl 16) } }
            }
        } else {
            context.assets.open(path).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        }
        tmp.renameTo(dest)
    }
}
