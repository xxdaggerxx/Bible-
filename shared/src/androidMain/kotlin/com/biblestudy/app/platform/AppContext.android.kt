package com.biblestudy.app.platform

import android.content.Context
import android.content.SharedPreferences
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.source

/** The shared app's view of Android: the app's folders, assets, SharedPreferences and SQLite. */
class AndroidAppContext(val context: Context) : AppContext {
    override val filesDir: Path get() = context.filesDir.toOkioPath()
    override val cacheDir: Path get() = context.cacheDir.toOkioPath()
    override val externalFilesDir: Path get() = (context.getExternalFilesDir(null) ?: context.filesDir).toOkioPath()
    override fun getDatabasePath(name: String): Path = context.getDatabasePath(name).toOkioPath()
    override val assets: Assets = object : Assets {
        override fun open(path: String): Source =
            try { context.assets.open(path).source() } catch (e: java.io.FileNotFoundException) { throw okio.FileNotFoundException(e.message) }
        override fun list(path: String): List<String> = context.assets.list(path)?.toList().orEmpty()
    }
    override fun prefs(name: String): Prefs = AndroidPrefs(context.getSharedPreferences(name, Context.MODE_PRIVATE))
    override val sql: SqlDriver by lazy { AndroidSqlDriver(context) }
}

class AndroidPrefs(val p: SharedPreferences) : Prefs {
    override fun getString(key: String, default: String?) = p.getString(key, default)
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun getLong(key: String, default: Long) = p.getLong(key, default)
    override fun getFloat(key: String, default: Float) = p.getFloat(key, default)
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? = p.getStringSet(key, default)
    override fun contains(key: String) = p.contains(key)
    override val all: Map<String, Any?> get() = p.all
    override fun edit(now: Boolean, block: Prefs.Editor.() -> Unit) {
        val e = p.edit()
        object : Prefs.Editor {
            override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
            override fun putInt(key: String, value: Int) = apply { e.putInt(key, value) }
            override fun putLong(key: String, value: Long) = apply { e.putLong(key, value) }
            override fun putFloat(key: String, value: Float) = apply { e.putFloat(key, value) }
            override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(key, value) }
            override fun putStringSet(key: String, value: Set<String>?) = apply { e.putStringSet(key, value) }
            override fun remove(key: String) = apply { e.remove(key) }
            override fun clear() = apply { e.clear() }
        }.block()
        if (now) e.commit() else e.apply()
    }
}
