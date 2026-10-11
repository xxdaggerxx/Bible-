package com.biblestudy.app.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Thrown for a missing key or a value of the wrong kind, as org.json does. */
class JSONException(message: String) : RuntimeException(message)

/**
 * JSON objects with the calls the app used from Android's org.json, on every platform (built on
 * kotlinx.serialization). Keys keep the order they were put in; [toString] writes compact JSON
 * that org.json (and earlier versions of the app) read, and it reads what they wrote.
 */
class JSONObject() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(json: String) : this() {
        val e = parse(json)
        if (e !is JsonObject) throw JSONException("Not a JSON object")
        for ((k, v) in e) map[k] = fromElement(v)
    }

    fun put(key: String, value: Any?): JSONObject {
        if (value == null) map.remove(key) else map[key] = norm(value)
        return this
    }

    fun has(key: String) = map.containsKey(key)
    fun isNull(key: String) = map[key].let { it == null || it == NULL }
    fun remove(key: String): Any? = map.remove(key)
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun length() = map.size
    fun opt(key: String): Any? = map[key]

    fun get(key: String): Any = map[key] ?: throw JSONException("No value for $key")
    fun getString(key: String): String = str(get(key)) ?: throw JSONException("$key is not a string")
    fun getInt(key: String): Int = num(get(key))?.toInt() ?: throw JSONException("$key is not a number")
    fun getLong(key: String): Long = long(get(key)) ?: throw JSONException("$key is not a number")
    fun getDouble(key: String): Double = num(get(key)) ?: throw JSONException("$key is not a number")
    fun getBoolean(key: String): Boolean = bool(get(key)) ?: throw JSONException("$key is not a boolean")
    fun getJSONObject(key: String): JSONObject = get(key) as? JSONObject ?: throw JSONException("$key is not an object")
    fun getJSONArray(key: String): JSONArray = get(key) as? JSONArray ?: throw JSONException("$key is not an array")

    fun optString(key: String, default: String = ""): String = map[key]?.takeIf { it != NULL }?.let { str(it) } ?: default
    fun optInt(key: String, default: Int = 0): Int = map[key]?.let { num(it)?.toInt() } ?: default
    fun optLong(key: String, default: Long = 0L): Long = map[key]?.let { long(it) } ?: default
    fun optDouble(key: String, default: Double = Double.NaN): Double = map[key]?.let { num(it) } ?: default
    fun optBoolean(key: String, default: Boolean = false): Boolean = map[key]?.let { bool(it) } ?: default
    fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray

    fun toElement(): JsonElement = JsonObject(map.mapValues { toElement(it.value) })
    override fun toString(): String = toElement().toString()

    companion object {
        /** JSON null, as a value. */
        val NULL: Any = JsonNull
    }
}

class JSONArray() {
    private val list = ArrayList<Any?>()

    constructor(json: String) : this() {
        val e = parse(json)
        if (e !is JsonArray) throw JSONException("Not a JSON array")
        for (v in e) list += fromElement(v)
    }

    constructor(items: Collection<*>) : this() {
        for (v in items) list += norm(v)
    }

    fun put(value: Any?): JSONArray { list += norm(value) ?: JSONObject.NULL; return this }
    fun length() = list.size
    fun opt(i: Int): Any? = list.getOrNull(i)
    fun isNull(i: Int) = list.getOrNull(i).let { it == null || it == JSONObject.NULL }
    fun remove(i: Int): Any? = if (i in list.indices) list.removeAt(i) else null

    fun get(i: Int): Any = list.getOrNull(i) ?: throw JSONException("No value at $i")
    fun getString(i: Int): String = str(get(i)) ?: throw JSONException("$i is not a string")
    fun getInt(i: Int): Int = num(get(i))?.toInt() ?: throw JSONException("$i is not a number")
    fun getLong(i: Int): Long = long(get(i)) ?: throw JSONException("$i is not a number")
    fun getDouble(i: Int): Double = num(get(i)) ?: throw JSONException("$i is not a number")
    fun getBoolean(i: Int): Boolean = bool(get(i)) ?: throw JSONException("$i is not a boolean")
    fun getJSONObject(i: Int): JSONObject = get(i) as? JSONObject ?: throw JSONException("$i is not an object")
    fun getJSONArray(i: Int): JSONArray = get(i) as? JSONArray ?: throw JSONException("$i is not an array")

    fun optString(i: Int, default: String = ""): String = list.getOrNull(i)?.takeIf { it != JSONObject.NULL }?.let { str(it) } ?: default
    fun optInt(i: Int, default: Int = 0): Int = list.getOrNull(i)?.let { num(it)?.toInt() } ?: default
    fun optLong(i: Int, default: Long = 0L): Long = list.getOrNull(i)?.let { long(it) } ?: default
    fun optDouble(i: Int, default: Double = Double.NaN): Double = list.getOrNull(i)?.let { num(it) } ?: default
    fun optBoolean(i: Int, default: Boolean = false): Boolean = list.getOrNull(i)?.let { bool(it) } ?: default
    fun optJSONObject(i: Int): JSONObject? = list.getOrNull(i) as? JSONObject
    fun optJSONArray(i: Int): JSONArray? = list.getOrNull(i) as? JSONArray

    fun toElement(): JsonElement = JsonArray(list.map { toElement(it) })
    override fun toString(): String = toElement().toString()
}

private val lenient = Json { isLenient = true }

private fun parse(json: String): JsonElement =
    try { lenient.parseToJsonElement(json) } catch (e: Exception) { throw JSONException(e.message ?: "Bad JSON") }

/** A parsed value as the app sees it: String, Long, Double, Boolean, JSONObject, JSONArray or NULL. */
private fun fromElement(e: JsonElement): Any? = when (e) {
    is JsonNull -> JSONObject.NULL
    is JsonObject -> JSONObject().also { o -> for ((k, v) in e) o.put(k, fromElement(v)) }
    is JsonArray -> JSONArray().also { a -> for (v in e) a.put(fromElement(v)) }
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull
        e.longOrNull != null -> e.longOrNull
        else -> e.doubleOrNull ?: e.content
    }
}

private fun norm(v: Any?): Any? = when (v) {
    null -> null
    is Int -> v.toLong()
    is Short -> v.toLong()
    is Byte -> v.toLong()
    is Float -> v.toDouble()
    is Long, is Double, is Boolean, is String, is JSONObject, is JSONArray -> v
    JSONObject.NULL -> v
    is Collection<*> -> JSONArray(v)
    else -> v.toString()
}

private fun toElement(v: Any?): JsonElement = when (v) {
    null, JSONObject.NULL -> JsonNull
    is JSONObject -> v.toElement()
    is JSONArray -> v.toElement()
    is Boolean -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    // Whole numbers are written without ".0", as org.json does.
    is Double -> if (v.isFinite() && v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}

private fun str(v: Any): String? = when (v) {
    JSONObject.NULL -> "null"
    is String -> v
    is Long, is Double, is Boolean -> v.toString()
    else -> null
}

private fun num(v: Any): Double? = when (v) {
    is Long -> v.toDouble()
    is Double -> v
    is String -> v.toDoubleOrNull()
    else -> null
}

private fun long(v: Any): Long? = when (v) {
    is Long -> v
    is Double -> v.toLong()
    is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong()
    else -> null
}

private fun bool(v: Any): Boolean? = when (v) {
    is Boolean -> v
    is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
    else -> null
}
