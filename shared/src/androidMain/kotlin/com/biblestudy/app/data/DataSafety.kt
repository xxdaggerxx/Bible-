package com.biblestudy.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Keeps the user's notes safe across updates (DATA-8). The first time a new version starts, before
 * it opens (and perhaps upgrades) the notes database, it saves the notes and pictures as an ordinary
 * backup zip, "bible-study-before-<version>.zip", in the app's Backups folder. It can be restored
 * with Restore from backup like any other. The newest [KEEP] are kept.
 */
object DataSafety {
    const val KEEP = 3
    const val PREFIX = "bible-study-before-"

    /** Where the zips go: the same folder as the automatic backups (Android/data/<app>/files/Backups). */
    fun backupDir(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "Backups")

    /**
     * Saves the notes if this is the first start of version [versionCode]. Returns the zip saved, or
     * null if none was needed (no notes yet, or this version has run before). Call before the
     * notes database is opened.
     */
    fun beforeOpening(context: Context, versionCode: Int, versionName: String, dir: File = backupDir(context)): File? {
        val prefs = context.getSharedPreferences("data_safety", Context.MODE_PRIVATE)
        if (prefs.getInt("openedBy", 0) == versionCode) return null
        val db = context.getDatabasePath(UserDb.NAME)
        val saved = if (db.exists()) {
            runCatching { save(db, File(context.filesDir, "images"), dir, "$PREFIX$versionName.zip") }
                .getOrElse { return null } // try again next start
        } else null
        prefs.edit().putInt("openedBy", versionCode).commit()
        return saved
    }

    /** Writes [db] and the pictures in [images] as a backup zip named [name] in [dir]. */
    fun save(db: File, images: File, dir: File, name: String): File {
        dir.mkdirs()
        val out = File(dir, name)
        val tmp = File(dir, "$name.part")
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            // Everything still in the write-ahead log goes into the main file first.
            sql.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                zip.setLevel(java.util.zip.Deflater.BEST_SPEED) // it runs as the app starts
                zip.putNextEntry(ZipEntry("userdata.db"))
                db.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                images.listFiles()?.filter { it.isFile }?.forEach { f ->
                    zip.putNextEntry(ZipEntry("images/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        return out
    }

    /**
     * Android deletes a database it finds damaged. Before it does, the damaged file is copied to
     * the Backups folder ("bible-study-damaged-<time>.db"), so what can be read from it isn't lost.
     */
    fun keepDamaged(context: Context, path: String) {
        runCatching {
            val f = File(path)
            if (f.exists()) f.copyTo(File(backupDir(context).apply { mkdirs() }, "bible-study-damaged-${System.currentTimeMillis()}.db"))
        }
    }
}
