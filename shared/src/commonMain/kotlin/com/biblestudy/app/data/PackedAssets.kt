package com.biblestudy.app.data

import com.biblestudy.app.platform.AppContext
import com.biblestudy.app.platform.fs
import com.biblestudy.app.platform.mkdirs
import com.biblestudy.app.platform.renameTo
import com.biblestudy.app.platform.xz
import okio.FileNotFoundException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.buffer
import okio.use

/**
 * The bundled databases ship packed as xz (`bibles/kjv.db` as `bibles/kjv.db.xz`) to keep the app
 * small; the build packs them (packAssets in app/build.gradle.kts). Each is unpacked once, to app
 * storage, the first time it's needed.
 */
object PackedAssets {
    /** Writes asset [path] to [dest], unpacking it if it's packed. Writes to a temporary file first. */
    fun copy(context: AppContext, path: String, dest: Path) {
        dest.parent?.mkdirs()
        val tmp = (dest.toString() + ".tmp").toPath()
        val packed: Source? = try { context.assets.open("$path.xz") } catch (_: FileNotFoundException) { null }
        val input = if (packed != null) xz(packed) else context.assets.open(path)
        input.use { src -> fs.sink(tmp).buffer().use { it.writeAll(src) } }
        tmp.renameTo(dest)
    }
}
