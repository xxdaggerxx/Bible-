package com.biblestudy.app.platform

/**
 * Small saved settings, by name (on Android, its SharedPreferences under the same names, so
 * settings carry over).
 */
interface Prefs {
    fun getString(key: String, default: String?): String?
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getFloat(key: String, default: Float): Float
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    val all: Map<String, Any?>

    /** Changes several settings at once; written in the background unless [now]. */
    fun edit(now: Boolean = false, block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putStringSet(key: String, value: Set<String>?): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
    }
}
