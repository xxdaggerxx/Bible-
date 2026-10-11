package com.biblestudy.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A file in the sync folder (Google Drive's hidden app folder, or a test's memory). */
data class RemoteFile(val id: String, val name: String)

/** Where devices meet (SYNC-1): a flat folder of files only this app sees. */
interface SyncStore {
    fun list(): List<RemoteFile>
    fun read(f: RemoteFile): ByteArray
    fun write(name: String, data: ByteArray)
    fun delete(f: RemoteFile)
}

/** What one sync did, for the Settings line and the tests. */
data class SyncResult(val received: Int, val sent: Int, val changedImages: Int = 0) {
    /** Something arrived from another device, so the screen needs reloading. */
    val changed get() = received > 0 || changedImages > 0
}

/**
 * Syncs the user's notes between their devices through a shared folder (SYNC-1).
 *
 * Every change to a synced row is noted in `sync_meta` by database triggers: the row's key, when it
 * changed and whether it's waiting to be sent (a delete leaves a "deleted" mark). A sync
 *  1. reads what other devices sent: their change files (`j-<device>-<n>.gz`) and any full copy
 *     (`s-<device>-<time>.gz`) that replaced older change files. For each row the newest change
 *     wins, so devices end up the same whatever order the files arrive in. A typed note changed on
 *     two devices before either synced keeps both texts rather than lose one.
 *  2. sends this device's waiting changes as one new change file, after any new pictures.
 *  3. now and then folds all change files into one full copy, so a new device has little to read.
 *
 * Panels, tabs, settings and saved layouts aren't synced: each device keeps its own view. Sketch
 * pages are numbered differently on each device, so rows on them are sent with the page's shared id.
 */
class SyncEngine(
    private val user: UserDb,
    private val store: SyncStore,
    /** This install's id: change files carry it, so a device skips its own. */
    private val device: String,
    private val imagesDir: File,
    /** A local number for a sketch page that arrived from another device. */
    private val newSketchId: (SQLiteDatabase) -> Long = { db -> Sync.maxSketchId(db) + 1 },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val db get() = user.writableDatabase
    private val columns = HashMap<String, Set<String>>()

    fun sync(): SyncResult {
        Sync.seed(db)
        val files = store.list()
        val received = pull(files)
        val images = fetchImages(files)
        val sent = push(files)
        compact()
        return SyncResult(received, sent, images)
    }

    // ---------- reading other devices' changes ----------

    private fun pull(files: List<RemoteFile>): Int {
        val seen = Sync.seen(db).toMutableSet()
        var received = 0
        // A full copy first: it stands in for change files that may since have been removed.
        for (s in files.filter { it.name.startsWith("s-") && it.name !in seen && !mine(it) }.sortedBy { stamp(it) }) {
            val bytes = runCatching { store.read(s) }.getOrNull() ?: continue
            val (includes, n) = apply(bytes)
            received += n
            Sync.markSeen(db, includes + s.name)
            seen += includes; seen += s.name
        }
        for (j in files.filter { it.name.startsWith("j-") && it.name !in seen && !mine(it) }.sortedBy { stamp(it) }) {
            // A change file can vanish while being read (another device folded it into a full copy);
            // that copy is read next time.
            val bytes = runCatching { store.read(j) }.getOrNull() ?: continue
            received += apply(bytes).second
            Sync.markSeen(db, listOf(j.name))
        }
        // Forget files that are gone.
        val names = files.map { it.name }.toSet()
        Sync.forgetSeen(db, seen.filter { it !in names })
        return received
    }

    private fun mine(f: RemoteFile) = f.name.startsWith("j-$device-") || f.name.startsWith("s-$device-")

    /** Sorts change files oldest first: "j-<device>-<n>.gz" sorts by device, then number. */
    private fun stamp(f: RemoteFile): String {
        val parts = f.name.removeSuffix(".gz").split('-')
        return parts.getOrNull(1).orEmpty() + "-" + (parts.getOrNull(2)?.padStart(20, '0') ?: "")
    }

    /** Applies a change file or full copy. Returns the change files a full copy includes and how many rows changed. */
    private fun apply(bytes: ByteArray): Pair<List<String>, Int> {
        val includes = ArrayList<String>()
        var changed = 0
        val db = db
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO sync_state(k, v) VALUES('applying', '1')") // the triggers stand aside
            JsonReader(InputStreamReader(GZIPInputStream(ByteArrayInputStream(bytes)), Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "includes" -> { r.beginArray(); while (r.hasNext()) includes += r.nextString(); r.endArray() }
                    "rows" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            val row = readRow(r)
                            // One bad row (say, from a newer version of the app) mustn't stop the rest.
                            if (runCatching { applyRow(db, row) }.getOrDefault(false)) changed++
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
                r.endObject()
            }
            db.execSQL("DELETE FROM sync_state WHERE k = 'applying'")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return includes to changed
    }

    private class Row(val table: String, val key: String, val at: Long, val dev: String, val deleted: Boolean, val values: Map<String, Any?>)

    private fun readRow(r: JsonReader): Row {
        var t = ""; var k = ""; var at = 0L; var dev = ""; var del = false
        val values = LinkedHashMap<String, Any?>()
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "t" -> t = r.nextString()
            "k" -> k = r.nextString()
            "at" -> at = r.nextLong()
            "dev" -> dev = r.nextString()
            "del" -> del = r.nextBoolean()
            "v" -> {
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    values[name] = when (r.peek()) {
                        JsonToken.NULL -> { r.nextNull(); null }
                        JsonToken.NUMBER -> r.nextString().let { s -> if (s.any { it == '.' || it == 'e' || it == 'E' }) s.toDouble() else s.toLong() }
                        JsonToken.BEGIN_OBJECT -> {
                            // A blob (ink points), in base64.
                            r.beginObject(); var b: ByteArray? = null
                            while (r.hasNext()) if (r.nextName() == "b64") b = Base64.decode(r.nextString(), Base64.NO_WRAP) else r.skipValue()
                            r.endObject(); b
                        }
                        else -> r.nextString()
                    }
                }
                r.endObject()
            }
            else -> r.skipValue()
        }
        r.endObject()
        return Row(t, k, at, dev, del, values)
    }

    /** Applies one row if it's newer than what's here. Returns whether anything changed. */
    private fun applyRow(db: SQLiteDatabase, sent: Row): Boolean {
        val table = Sync.TABLES.firstOrNull { it.name == sent.table } ?: return false
        // This device's own reading stats, coming back (from a full copy): kept as '' here.
        val row = if (table.perDevice && sent.key.startsWith("$device|")) {
            Row(sent.table, sent.key.removePrefix(device), sent.at, sent.dev, sent.deleted, sent.values + ("dev" to ""))
        } else sent
        val meta = Sync.meta(db, table.name, row.key)
        // A typed note changed here and on another device before either synced: keep both texts.
        if (table.name == "notes" && meta != null && meta.pending && row.at > meta.base && !row.deleted) {
            val here = Sync.noteText(db, row.key)
            val there = (row.values["text"] as? String).orEmpty()
            if (here != null && here != there && !here.contains(there)) {
                val values = ContentValues()
                for ((col, v) in row.values) if (v is Long) values.put(col, v) else values.put(col, v?.toString())
                values.put("text", if (there.contains(here)) there else "$there\n\n$here")
                Sync.writeRow(db, table, row.key, values, newSketchId)
                // Waiting to be sent, now based on the version that arrived.
                Sync.setMeta(db, table.name, row.key, maxOf(row.at, meta.at) + 1, device, pending = true, deleted = false, base = row.at)
                return true
            }
        }
        if (meta != null) {
            val mine = meta.dev.ifEmpty { device }
            if (meta.at > row.at || (meta.at == row.at && mine >= row.dev)) return false
        }
        if (row.deleted) {
            val files = if (table.name == "images") Sync.imageFiles(db, table, row.key) else emptyList()
            val n = Sync.deleteRow(db, table, row.key)
            Sync.setMeta(db, table.name, row.key, row.at, row.dev, pending = false, deleted = true)
            for (f in files) if (!Sync.imageInUse(db, f)) File(imagesDir, f).delete()
            return n > 0
        }
        val values = ContentValues()
        val known = columns.getOrPut(table.name) { Sync.columns(db, table.name) }
        for ((col, v) in row.values) {
            if (table.name == "sketches" && col == "id") continue // each device numbers its own pages
            if (col !in known) continue // a column from a newer version of the app
            val local = if (col == "book" && v is String && v.startsWith("s:")) {
                Sync.sketchIdOf(db, v.removePrefix("s:"))?.let { (Sync.SKETCH_BOOK + it).toLong() } ?: return false // the page was deleted here
            } else v
            when (local) {
                null -> values.putNull(col)
                is Long -> values.put(col, local)
                is Double -> values.put(col, local)
                is ByteArray -> values.put(col, local)
                else -> values.put(col, local.toString())
            }
        }
        Sync.writeRow(db, table, row.key, values, newSketchId)
        Sync.setMeta(db, table.name, row.key, row.at, row.dev, pending = false, deleted = false)
        return true
    }

    /** Pictures in notes that arrived without their file: fetched when the file is there. */
    private fun fetchImages(files: List<RemoteFile>): Int {
        val byName = files.filter { it.name.startsWith("img-") }.associateBy { it.name.removePrefix("img-") }
        var n = 0
        for (f in Sync.imageNames(db)) {
            val local = File(imagesDir, f)
            if (local.exists()) continue
            val remote = byName[f] ?: continue
            val bytes = runCatching { store.read(remote) }.getOrNull() ?: continue
            imagesDir.mkdirs()
            val tmp = File(imagesDir, "$f.part")
            tmp.writeBytes(bytes)
            tmp.renameTo(local)
            n++
        }
        return n
    }

    // ---------- sending this device's changes ----------

    private fun push(files: List<RemoteFile>): Int {
        val waiting = Sync.waiting(db)
        if (waiting.isEmpty()) return 0
        // Pictures first, so another device finds the file when the row arrives.
        val there = files.map { it.name }.toSet()
        for (f in Sync.imagesOf(db, waiting)) {
            if ("img-$f" in there) continue
            val local = File(imagesDir, f)
            if (local.exists()) store.write("img-$f", local.readBytes())
        }
        val n = Sync.nextNumber(db)
        val name = "j-$device-$n.gz"
        store.write(name, encode(waiting, includes = emptyList()))
        Sync.markSeen(db, listOf(name))
        Sync.sent(db, waiting)
        return waiting.size
    }

    /** Waiting rows (or every row, for a full copy) as gzipped JSON. */
    private fun encode(keys: List<Sync.Key>, includes: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        JsonWriter(OutputStreamWriter(GZIPOutputStream(out), Charsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("device").value(device)
            w.name("includes").beginArray(); includes.forEach { w.value(it) }; w.endArray()
            w.name("rows").beginArray()
            // Pages and layers before what's on them.
            for (t in Sync.TABLES) for (k in keys) if (k.table == t.name) writeRow(w, t, k)
            w.endArray()
            w.endObject()
        }
        return out.toByteArray()
    }

    private fun writeRow(w: JsonWriter, t: Sync.Table, k: Sync.Key) {
        val db = db
        val cols: Map<String, Any?>? = if (k.deleted) null else Sync.readRow(db, t, k.key)
        w.beginObject()
        w.name("t").value(t.name)
        w.name("k").value(if (t.perDevice && k.key.startsWith("|")) device + k.key else k.key)
        w.name("at").value(k.at)
        w.name("dev").value(k.dev.ifEmpty { device })
        w.name("del").value(cols == null)
        if (cols != null) {
            w.name("v").beginObject()
            for ((c, v0) in cols) {
                val v = if (t.perDevice && c == "dev" && v0 == "") device else v0
                w.name(c)
                when (v) {
                    null -> w.nullValue()
                    is Long -> w.value(v)
                    is Double -> w.value(v)
                    is ByteArray -> { w.beginObject(); w.name("b64").value(Base64.encodeToString(v, Base64.NO_WRAP)); w.endObject() }
                    is Int -> w.value(v.toLong())
                    else -> w.value(v.toString())
                }
            }
            w.endObject()
        }
        w.endObject()
    }

    // ---------- folding change files into one full copy ----------

    private fun compact() {
        val files = store.list()
        val journals = files.filter { it.name.startsWith("j-") }
        if (journals.size < COMPACT_AFTER) return
        val seen = Sync.seen(db)
        // Only files this device has read are in the copy; the others stay for the next one.
        val included = journals.filter { it.name in seen }
        if (included.isEmpty()) return
        val name = "s-$device-${now()}.gz"
        store.write(name, encode(Sync.everything(db), included.map { it.name }))
        Sync.markSeen(db, listOf(name))
        for (f in included) runCatching { store.delete(f) }
        // Older full copies this device has read (one it hasn't may hold change files it never saw).
        for (f in files.filter { it.name.startsWith("s-") && it.name in seen }) runCatching { store.delete(f) }
    }

    companion object {
        /** Change files kept before they're folded into a full copy. */
        const val COMPACT_AFTER = 30
    }
}

/** The synced tables and the bookkeeping the [SyncEngine] keeps in the notes database. */
object Sync {
    /**
     * A synced table: rows are matched across devices by [keys]; [hasBook] rows may sit on a sketch
     * page. [perDevice] tables (reading stats) have a `dev` column, '' for this device's own rows,
     * which is sent as the device's id; each device only adds to its own rows.
     */
    class Table(val name: String, val keys: List<String>, val hasBook: Boolean, val perDevice: Boolean = false)

    /** In the order they're applied: layers and sketch pages before what's on them. */
    val TABLES = listOf(
        Table("layers", listOf("id"), false),
        Table("sketches", listOf("uid"), false),
        Table("strokes", listOf("id"), true),
        Table("highlights", listOf("id"), true),
        Table("images", listOf("id"), true),
        Table("texts", listOf("id"), true),
        Table("notes", listOf("book", "chapter", "verse"), false),
        Table("bookmarks", listOf("id"), true),
        Table("tags", listOf("item", "tag"), false),
        Table("meanings", listOf("color"), false),
        Table("reading_days", listOf("dev", "day"), false, perDevice = true),
        Table("reading_chapters", listOf("dev", "book", "chapter"), false, perDevice = true),
    )

    const val SKETCH_BOOK = 1000

    /** Milliseconds since 1970, in SQLite. */
    private const val NOW = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

    private fun keyExpr(t: Table, row: String) = t.keys.joinToString(" || '|' || ") { "$row.$it" }

    /** The change log and the triggers that fill it (version 10 of the notes database). */
    fun createTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_meta(tbl TEXT NOT NULL, k TEXT NOT NULL, at INTEGER NOT NULL, dev TEXT NOT NULL, " +
                "pending INTEGER NOT NULL, deleted INTEGER NOT NULL, base INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(tbl, k))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS sync_meta_pending ON sync_meta(pending)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_state(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_seen(name TEXT PRIMARY KEY)")
        val quiet = "NOT EXISTS (SELECT 1 FROM sync_state WHERE k = 'applying')"
        for (t in TABLES) {
            for ((event, row, deleted) in listOf(Triple("INSERT", "NEW", 0), Triple("UPDATE", "NEW", 0), Triple("DELETE", "OLD", 1))) {
                // [base]: when the version this change was made from arrived (or was sent), to tell
                // a change made at the same time on another device from an old one.
                val key = keyExpr(t, row)
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS sync_${t.name}_${event.lowercase()} AFTER $event ON ${t.name} WHEN $quiet BEGIN " +
                        "INSERT OR REPLACE INTO sync_meta(tbl, k, at, dev, pending, deleted, base) " +
                        "VALUES('${t.name}', $key, $NOW, '', 1, $deleted, " +
                        "COALESCE((SELECT CASE WHEN pending = 1 THEN base ELSE at END FROM sync_meta WHERE tbl = '${t.name}' AND k = $key), 0)); END"
                )
            }
        }
    }

    /**
     * Rows from before sync was turned on (or from before version 10) are noted as waiting, dated
     * long ago so that a newer change from another device wins.
     */
    fun seed(db: SQLiteDatabase) {
        val done = db.rawQuery("SELECT v FROM sync_state WHERE k = 'seeded11'", null).use { it.moveToFirst() }
        if (done) return
        db.beginTransaction()
        try {
            for (t in TABLES) {
                db.execSQL(
                    "INSERT OR IGNORE INTO sync_meta(tbl, k, at, dev, pending, deleted) " +
                        "SELECT '${t.name}', ${keyExpr(t, t.name)}, 1, '', 1, 0 FROM ${t.name}"
                )
            }
            db.execSQL("INSERT OR REPLACE INTO sync_state(k, v) VALUES('seeded11', '1')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Notes everything here as newly changed (after restoring a backup), so the restored notes win
     * over older copies on other devices.
     */
    fun markAllChanged(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM sync_state WHERE k = 'seeded11'")
        db.execSQL("DELETE FROM sync_meta")
        seed(db)
        db.execSQL("UPDATE sync_meta SET at = $NOW")
    }

    /** Dates rows changed since [since] long ago: the ready-made pages each device makes for itself. */
    fun markAsOriginal(db: SQLiteDatabase, since: Long) {
        db.execSQL(
            "UPDATE sync_meta SET at = 1 WHERE at >= ? AND tbl IN ('sketches', 'strokes', 'texts', 'images')",
            arrayOf(since),
        )
    }

    class Meta(val at: Long, val dev: String, val pending: Boolean, val base: Long)

    fun meta(db: SQLiteDatabase, table: String, key: String): Meta? =
        db.rawQuery("SELECT at, dev, pending, base FROM sync_meta WHERE tbl = ? AND k = ?", arrayOf(table, key)).use { c ->
            if (c.moveToFirst()) Meta(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getLong(3)) else null
        }

    fun setMeta(db: SQLiteDatabase, table: String, key: String, at: Long, dev: String, pending: Boolean, deleted: Boolean, base: Long = at) {
        db.execSQL(
            "INSERT OR REPLACE INTO sync_meta(tbl, k, at, dev, pending, deleted, base) VALUES(?, ?, ?, ?, ?, ?, ?)",
            arrayOf(table, key, at, dev, if (pending) 1 else 0, if (deleted) 1 else 0, base),
        )
    }

    /** A row waiting to be sent (or, for a full copy, any row). */
    data class Key(val table: String, val key: String, val at: Long, val dev: String, val deleted: Boolean)

    private fun keys(db: SQLiteDatabase, where: String): List<Key> =
        db.rawQuery("SELECT tbl, k, at, dev, deleted FROM sync_meta $where", null).use { c ->
            buildList { while (c.moveToNext()) add(Key(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4) == 1)) }
        }

    fun waiting(db: SQLiteDatabase) = keys(db, "WHERE pending = 1")
    fun everything(db: SQLiteDatabase) = keys(db, "")

    /** Marks [keys] sent, unless they changed again while sending. */
    fun sent(db: SQLiteDatabase, keys: List<Key>) {
        db.beginTransaction()
        try {
            for (k in keys) db.execSQL("UPDATE sync_meta SET pending = 0 WHERE tbl = ? AND k = ? AND at = ?", arrayOf(k.table, k.key, k.at))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** How many rows are waiting to be sent. */
    fun waitingCount(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM sync_meta WHERE pending = 1", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun nextNumber(db: SQLiteDatabase): Long {
        val n = db.rawQuery("SELECT v FROM sync_state WHERE k = 'next'", null).use { if (it.moveToFirst()) it.getString(0).toLong() else 1L }
        db.execSQL("INSERT OR REPLACE INTO sync_state(k, v) VALUES('next', ?)", arrayOf((n + 1).toString()))
        return n
    }

    fun seen(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sync_seen", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    fun markSeen(db: SQLiteDatabase, names: List<String>) {
        for (n in names) db.execSQL("INSERT OR IGNORE INTO sync_seen(name) VALUES(?)", arrayOf(n))
    }

    fun forgetSeen(db: SQLiteDatabase, names: List<String>) {
        for (n in names) db.execSQL("DELETE FROM sync_seen WHERE name = ?", arrayOf(n))
    }

    private fun where(t: Table) = t.keys.joinToString(" AND ") { "$it = ?" }

    /** The key's parts, as SQL arguments. Text keys (tags) may contain '|' only in the last part. */
    private fun args(t: Table, key: String): Array<String> = key.split('|', limit = t.keys.size).toTypedArray()

    /** A row's columns; a sketch page's number is given as its shared id ("s:<uid>"). */
    fun readRow(db: SQLiteDatabase, t: Table, key: String): Map<String, Any?>? =
        db.rawQuery("SELECT * FROM ${t.name} WHERE ${where(t)}", args(t, key)).use { c ->
            if (!c.moveToFirst()) return null
            val out = LinkedHashMap<String, Any?>()
            for (i in 0 until c.columnCount) {
                val name = c.getColumnName(i)
                out[name] = when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                    else -> c.getString(i)
                }
            }
            if (t.hasBook) {
                val book = (out["book"] as? Long) ?: 0L
                if (book >= SKETCH_BOOK) out["book"] = "s:" + (sketchUid(db, book - SKETCH_BOOK) ?: return null)
            }
            if (t.name == "sketches") out.remove("id")
            out
        }

    /** Writes a row from another device: a new sketch page gets the next free local number. */
    fun writeRow(db: SQLiteDatabase, t: Table, key: String, values: ContentValues, newSketchId: (SQLiteDatabase) -> Long) {
        if (t.name == "sketches") {
            val id = sketchIdOf(db, values.getAsString("uid")) ?: newSketchId(db)
            values.put("id", id)
        }
        db.insertWithOnConflict(t.name, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteRow(db: SQLiteDatabase, t: Table, key: String): Int {
        if (t.name == "sketches") {
            // Everything on the page goes too (its rows are also deleted one by one, as on the other device).
            val id = sketchIdOf(db, key) ?: return 0
            for (on in listOf("strokes", "images", "texts")) db.delete(on, "book = ?", arrayOf((SKETCH_BOOK + id).toString()))
        }
        return db.delete(t.name, where(t), args(t, key))
    }

    fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }

    fun sketchIdOf(db: SQLiteDatabase, uid: String?): Long? {
        if (uid.isNullOrEmpty()) return null
        return db.rawQuery("SELECT id FROM sketches WHERE uid = ?", arrayOf(uid)).use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun sketchUid(db: SQLiteDatabase, id: Long): String? =
        db.rawQuery("SELECT uid FROM sketches WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }

    fun maxSketchId(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT MAX(id) FROM sketches", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun noteText(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT text FROM notes WHERE book = ? AND chapter = ? AND verse = ?", key.split('|').toTypedArray()).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun imageNames(db: SQLiteDatabase): List<String> =
        db.rawQuery("SELECT DISTINCT file FROM images", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun imageFiles(db: SQLiteDatabase, t: Table, key: String): List<String> =
        db.rawQuery("SELECT file FROM images WHERE ${where(t)}", args(t, key)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun imageInUse(db: SQLiteDatabase, file: String): Boolean =
        db.rawQuery("SELECT 1 FROM images WHERE file = ? LIMIT 1", arrayOf(file)).use { it.moveToFirst() }

    /** The picture files of waiting picture rows. */
    fun imagesOf(db: SQLiteDatabase, keys: List<Key>): List<String> {
        val t = TABLES.first { it.name == "images" }
        return keys.filter { it.table == "images" && !it.deleted }.flatMap { imageFiles(db, t, it.key) }.distinct()
    }
}
