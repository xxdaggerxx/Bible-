package com.biblestudy.app.platform

import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.source
import java.io.File

/**
 * The shared app on a computer: its data in the usual place for each system (Windows
 * %APPDATA%\Ink & Word, Mac ~/Library/Application Support/Ink & Word, Linux ~/.local/share/ink-and-word),
 * the bundled data from the app's resources, settings as small JSON files ([FilePrefs]), and bundled SQLite.
 */
class DesktopAppContext(val home: Path = defaultHome()) : AppContext {
    override val filesDir: Path = (home / "files").also { fs.createDirectories(it) }
    override val cacheDir: Path = (home / "cache").also { fs.createDirectories(it) }
    override val externalFilesDir: Path get() = home
    override fun getDatabasePath(name: String): Path = home / "databases" / name
    override val assets: Assets = ResourceAssets
    private val prefs = HashMap<String, FilePrefs>()
    override fun prefs(name: String): Prefs = synchronized(prefs) { prefs.getOrPut(name) { FilePrefs(home / "settings" / "$name.json") } }
    override val sql: SqlDriver by lazy { BundledSqlDriver() }

    companion object {
        fun defaultHome(): Path {
            val os = System.getProperty("os.name").lowercase()
            val user = System.getProperty("user.home")
            val dir = when {
                os.contains("win") -> File(System.getenv("APPDATA") ?: "$user\\AppData\\Roaming", "Ink & Word")
                os.contains("mac") -> File(user, "Library/Application Support/Ink & Word")
                else -> File(System.getenv("XDG_DATA_HOME")?.ifEmpty { null } ?: "$user/.local/share", "ink-and-word")
            }
            return dir.absolutePath.toPath().also { fs.createDirectories(it) }
        }
    }
}

/** The data bundled with the app, under assets/ in its resources (assets/index.txt lists it). */
object ResourceAssets : Assets {
    private val loader = ResourceAssets::class.java.classLoader

    override fun open(path: String): Source =
        (loader.getResourceAsStream("assets/$path") ?: throw okio.FileNotFoundException(path)).source()

    private val index: List<String> by lazy {
        loader.getResourceAsStream("assets/index.txt")?.bufferedReader()?.readLines()?.filter { it.isNotBlank() }.orEmpty()
    }

    override fun list(path: String): List<String> {
        val prefix = if (path.isEmpty()) "" else path.trimEnd('/') + "/"
        return index.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
    }
}
