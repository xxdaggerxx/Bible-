package com.biblestudy.app.data

import com.biblestudy.app.platform.*
import okio.Path
import okio.Path.Companion.toPath
import okio.use

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
    fun backupDir(context: AppContext): Path = context.externalFilesDir / "Backups"

    /**
     * Saves the notes if this is the first start of version [versionCode]. Returns the zip saved, or
     * null if none was needed (no notes yet, or this version has run before). Call before the
     * notes database is opened.
     */
    fun beforeOpening(context: AppContext, versionCode: Int, versionName: String, dir: Path = backupDir(context)): Path? {
        val prefs = context.prefs("data_safety")
        if (prefs.getInt("openedBy", 0) == versionCode) return null
        val db = context.getDatabasePath(UserDb.NAME)
        val saved = if (db.exists()) {
            runCatching { save(context, db, context.filesDir / "images", dir, "$PREFIX$versionName.zip") }
                .getOrElse { return null } // try again next start
        } else null
        prefs.edit(now = true) { putInt("openedBy", versionCode) }
        return saved
    }

    /** Writes [db] and the pictures in [images] as a backup zip named [name] in [dir]. */
    fun save(context: AppContext, db: Path, images: Path, dir: Path, name: String): Path {
        dir.mkdirs()
        val out = dir / name
        val tmp = dir / "$name.part"
        context.sql.open(db.toString()).use { sql ->
            // Everything still in the write-ahead log goes into the main file first.
            sql.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            tmp.sink().use { sink ->
                val zip = Zip.Writer(sink, level = 1) // it runs as the app starts
                zip.putEntry("userdata.db", fs.source(db))
                images.listFiles()?.filter { it.isFile }?.forEach { f -> zip.putEntry("images/${f.name}", fs.source(f)) }
                zip.finish()
            }
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        dir.listFiles()?.filter { it.name.startsWith(PREFIX) && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        return out
    }

    /**
     * Android deletes a database it finds damaged. Before it does, the damaged file is copied to
     * the Backups folder ("bible-study-damaged-<time>.db"), so what can be read from it isn't lost.
     */
    fun keepDamaged(context: AppContext, path: String) {
        runCatching {
            val f = path.toPath()
            if (f.exists()) f.copyTo(backupDir(context).mkdirs() / "bible-study-damaged-${nowMillis()}.db")
        }
    }
}
