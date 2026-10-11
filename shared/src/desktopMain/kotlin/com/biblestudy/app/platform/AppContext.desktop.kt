package com.biblestudy.app.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.source
import java.io.File
import java.util.concurrent.Executors

/**
 * The shared app on a computer: its data in the usual place for each system (Windows
 * %APPDATA%\Ink & Word, Mac ~/Library/Application Support/Ink & Word, Linux ~/.local/share/ink-and-word),
 * the bundled data from the app's resources, settings as small JSON files, and bundled SQLite.
 */
class DesktopAppContext(val home: Path = defaultHome()) : AppContext {
    override val filesDir: Path = (home / "files").also { fs.createDirectories(it) }
    override val cacheDir: Path = (home / "cache").also { fs.createDirectories(it) }
    override val externalFilesDir: Path get() = home
    override fun getDatabasePath(name: String): Path = home / "databases" / name
    override val assets: Assets = ResourceAssets
    private val prefs = HashMap<String, DesktopPrefs>()
    override fun prefs(name: String): Prefs = synchronized(prefs) { prefs.getOrPut(name) { DesktopPrefs(home / "settings" / "$name.json") } }
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

/** Settings kept as a small JSON file, written in the background (or at once when asked). */
class DesktopPrefs(private val file: Path) : Prefs {
    private val map = LinkedHashMap<String, Any?>()

    init {
        runCatching {
            val o = Json.parseToJsonElement(file.readText()) as JsonObject
            for ((k, v) in o) map[k] = fromJson(v)
        }
    }

    private fun fromJson(v: JsonElement): Any? = when (v) {
        is JsonArray -> v.map { (it as JsonPrimitive).content }.toSet()
        is JsonPrimitive -> when {
            v.isString -> v.content
            v.booleanOrNull != null -> v.booleanOrNull
            v.longOrNull != null -> v.longOrNull
            else -> v.doubleOrNull
        }
        else -> null
    }

    private fun get(key: String): Any? = synchronized(map) { map[key] }
    override fun getString(key: String, default: String?) = get(key) as? String ?: default
    override fun getInt(key: String, default: Int) = (get(key) as? Number)?.toInt() ?: default
    override fun getLong(key: String, default: Long) = (get(key) as? Number)?.toLong() ?: default
    override fun getFloat(key: String, default: Float) = (get(key) as? Number)?.toFloat() ?: default
    override fun getBoolean(key: String, default: Boolean) = get(key) as? Boolean ?: default
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>?) = get(key) as? Set<String> ?: default
    override fun contains(key: String) = synchronized(map) { map.containsKey(key) }
    override val all: Map<String, Any?> get() = synchronized(map) { LinkedHashMap(map) }

    override fun edit(now: Boolean, block: Prefs.Editor.() -> Unit) {
        val changes = ArrayList<(MutableMap<String, Any?>) -> Unit>()
        object : Prefs.Editor {
            override fun putString(key: String, value: String?) = apply { changes += { it[key] = value } }
            override fun putInt(key: String, value: Int) = apply { changes += { it[key] = value.toLong() } }
            override fun putLong(key: String, value: Long) = apply { changes += { it[key] = value } }
            override fun putFloat(key: String, value: Float) = apply { changes += { it[key] = value.toDouble() } }
            override fun putBoolean(key: String, value: Boolean) = apply { changes += { it[key] = value } }
            override fun putStringSet(key: String, value: Set<String>?) = apply { changes += { it[key] = value?.toSet() } }
            override fun remove(key: String) = apply { changes += { it.remove(key) } }
            override fun clear() = apply { changes.add(0) { it.clear() } }
        }.block()
        val json = synchronized(map) {
            changes.forEach { it(map) }
            map.entries.removeAll { it.value == null }
            JsonObject(map.mapValues { (_, v) ->
                when (v) {
                    is Set<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) })
                    is Boolean -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
            }).toString()
        }
        if (now) write(json) else writer.execute { write(json) }
    }

    /** Written to a new file first, then swapped in, so a crash never leaves half a file. */
    private fun write(json: String) = synchronized(this) {
        file.parent?.let { fs.createDirectories(it) }
        val tmp = file.parent!! / (file.name + ".new")
        tmp.writeText(json)
        fs.atomicMove(tmp, file)
    }

    companion object {
        /** Waits for settings still being written (before the app closes). */
        fun flush() { runCatching { writer.submit {}.get() } }

        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "settings").apply { isDaemon = true } }
    }
}
