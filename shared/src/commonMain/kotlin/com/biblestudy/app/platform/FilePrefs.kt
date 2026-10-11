package com.biblestudy.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okio.Path

/**
 * Settings kept as a small JSON file (on computers and iOS), written in the background or at
 * once when asked. Each write goes to a new file that is then swapped in, so a crash never leaves
 * half a file.
 */
class FilePrefs(private val file: Path) : Prefs {
    private val lock = Lock()
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

    private fun get(key: String): Any? = locked(lock) { map[key] }
    override fun getString(key: String, default: String?) = get(key) as? String ?: default
    override fun getInt(key: String, default: Int) = (get(key) as? Number)?.toInt() ?: default
    override fun getLong(key: String, default: Long) = (get(key) as? Number)?.toLong() ?: default
    override fun getFloat(key: String, default: Float) = (get(key) as? Number)?.toFloat() ?: default
    override fun getBoolean(key: String, default: Boolean) = get(key) as? Boolean ?: default
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>?) = get(key) as? Set<String> ?: default
    override fun contains(key: String) = locked(lock) { map.containsKey(key) }
    override val all: Map<String, Any?> get() = locked(lock) { LinkedHashMap(map) }

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
        val json = locked(lock) {
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
        if (now) write(json) else writer.launch { write(json) }
    }

    private val writeLock = Lock()
    private fun write(json: String) = locked(writeLock) {
        file.parent?.let { fs.createDirectories(it) }
        val tmp = file.parent!! / (file.name + ".new")
        tmp.writeText(json)
        fs.atomicMove(tmp, file)
    }

    companion object {
        /** One background writer for all settings files, so writes keep their order. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        /** Waits for settings still being written (before the app closes). */
        suspend fun flush() {
            writer.launch { }.join()
        }
    }
}
