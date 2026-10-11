package com.biblestudy.app.platform

import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * The shared app on iPad and iPhone: its data in the app's Application Support folder, Backups in
 * its Documents folder (shown in the Files app, under On My iPad → Ink & Word), the bundled data
 * from the app's bundle, settings as small JSON files ([FilePrefs]), and bundled SQLite.
 */
class IosAppContext : AppContext {
    private fun dir(kind: ULong): Path =
        (NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String).toPath()

    private val home: Path = (dir(NSApplicationSupportDirectory) / "Ink & Word").also { fs.createDirectories(it) }
    override val filesDir: Path = (home / "files").also { fs.createDirectories(it) }
    override val cacheDir: Path = (dir(NSCachesDirectory) / "Ink & Word").also { fs.createDirectories(it) }
    override val externalFilesDir: Path = dir(NSDocumentDirectory).also { fs.createDirectories(it) }
    override fun getDatabasePath(name: String): Path = home / "databases" / name
    override val assets: Assets = BundleAssets
    private val prefs = SyncMap<String, FilePrefs>()
    private val prefsLock = Lock()
    override fun prefs(name: String): Prefs = locked(prefsLock) {
        prefs[name] ?: FilePrefs(home / "settings" / "$name.json").also { prefs[name] = it }
    }
    override val sql: SqlDriver by lazy { BundledSqlDriver() }
}

/** The data bundled with the app, in an assets folder in the app's bundle. */
object BundleAssets : Assets {
    private val root: Path by lazy { (NSBundle.mainBundle.resourcePath ?: "").toPath() / "assets" }

    override fun open(path: String): Source {
        val p = root / path
        if (!fs.exists(p)) throw okio.FileNotFoundException(path)
        return fs.source(p)
    }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    override fun list(path: String): List<String> =
        (NSFileManager.defaultManager.contentsOfDirectoryAtPath((root / path).toString(), null) ?: emptyList<Any>()).map { it as String }
}
