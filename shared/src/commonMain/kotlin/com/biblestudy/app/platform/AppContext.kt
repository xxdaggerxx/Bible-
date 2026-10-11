package com.biblestudy.app.platform

import okio.Path
import okio.Source
import okio.buffer
import okio.use

/**
 * What the shared app needs from the platform it runs on: its folders, the bundled data, saved
 * settings and the database. Each app shell makes one (on Android from its Context).
 */
interface AppContext {
    /** The app's private files: notes pictures, imported Bibles, unpacked data. */
    val filesDir: Path
    val cacheDir: Path
    /** Where Backups go when no folder is chosen (on Android, Android/data/<app>/files). */
    val externalFilesDir: Path
    fun getDatabasePath(name: String): Path
    val assets: Assets
    fun prefs(name: String): Prefs
    val sql: SqlDriver
}

/** The data bundled with the app (Android's assets). */
interface Assets {
    /** Opens a bundled file; throws okio.FileNotFoundException if there's none. */
    fun open(path: String): Source
    fun list(path: String): List<String>
}

/** A bundled text file's contents. */
fun Assets.readText(path: String): String = open(path).buffer().use { it.readUtf8() }

/** A bundled text file's lines, as a reader gives them (no empty line after the last). */
fun Assets.lineSequence(path: String): Sequence<String> =
    readText(path).lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }.asSequence()
