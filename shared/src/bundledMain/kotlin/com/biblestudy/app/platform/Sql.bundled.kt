package com.biblestudy.app.platform

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import okio.Path.Companion.toPath

actual open class SqlException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * SQLite bundled with the app, on computers and iOS, with the same behaviour the app relies on
 * from Android's: nested transactions that commit only if every level succeeded, one thread at a
 * time, insert returning the new row's id (or -1), and so on.
 */
class BundledSqlDriver : SqlDriver {
    private val driver = BundledSQLiteDriver()

    override fun open(path: String, readOnly: Boolean, create: Boolean): SqlDb {
        val flags = (if (readOnly) SQLITE_OPEN_READONLY else SQLITE_OPEN_READWRITE) or
            (if (create) SQLITE_OPEN_CREATE else 0) or SQLITE_OPEN_FULLMUTEX
        val conn = try { driver.open(path, flags) } catch (e: Exception) { throw SqlException("Couldn't open $path: ${e.message}", e) }
        return BundledSqlDb(conn, path)
    }

    override fun openHelper(path: String, version: Int, helper: SqlOpenHelper): SqlHelperHandle {
        path.toPath().parent?.let { fs.createDirectories(it) }
        val db = open(path, create = true) as BundledSqlDb
        db.execSQL("PRAGMA journal_mode=WAL")
        val old = db.version
        if (old != version) {
            if (old > version) throw SqlException("Can't downgrade $path from version $old to $version")
            db.beginTransaction()
            try {
                if (old == 0) helper.onCreate(db) else helper.onUpgrade(db, old, version)
                db.version = version
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return object : SqlHelperHandle {
            override val writable: SqlDb get() = db
            override val readable: SqlDb get() = db
            override fun close() = db.close()
        }
    }
}

class BundledSqlDb(private val conn: SQLiteConnection, override val path: String) : SqlDb {
    private val lock = reentrantLock()
    private var open = true
    override val isOpen: Boolean get() = open

    // Transactions, as Android does them: nested, and committed only if every level succeeded.
    private var depth = 0
    private var allGood = true
    private var thisGood = ArrayList<Boolean>()

    private inline fun <T> run(block: () -> T): T = lock.withLock {
        try { block() } catch (e: SqlException) { throw e } catch (e: Exception) { throw SqlException(e.message, e) }
    }

    private fun SQLiteStatement.bind(args: Array<out Any?>?) {
        args?.forEachIndexed { i, a ->
            val n = i + 1
            when (a) {
                null -> bindNull(n)
                is String -> bindText(n, a)
                is Long -> bindLong(n, a)
                is Int -> bindLong(n, a.toLong())
                is Short -> bindLong(n, a.toLong())
                is Byte -> bindLong(n, a.toLong())
                is Boolean -> bindLong(n, if (a) 1 else 0)
                is Double -> bindDouble(n, a)
                is Float -> bindDouble(n, a.toDouble())
                is ByteArray -> bindBlob(n, a)
                else -> bindText(n, a.toString())
            }
        }
    }

    override fun execSQL(sql: String) = execSQL(sql, emptyArray())

    override fun execSQL(sql: String, args: Array<out Any?>) = run {
        conn.prepare(sql).use { st -> st.bind(args); while (st.step()) Unit }
    }

    override fun rawQuery(sql: String, args: Array<out String?>?): SqlCursor = run {
        conn.prepare(sql).use { st ->
            st.bind(args)
            val cols = st.getColumnCount()
            val names = List(cols) { st.getColumnName(it) }
            val rows = ArrayList<Array<Any?>>()
            while (st.step()) {
                rows += Array(cols) { i ->
                    when (st.getColumnType(i)) {
                        SQLITE_DATA_NULL -> null
                        SQLITE_DATA_INTEGER -> st.getLong(i)
                        SQLITE_DATA_FLOAT -> st.getDouble(i)
                        SQLITE_DATA_BLOB -> st.getBlob(i)
                        else -> st.getText(i)
                    }
                }
            }
            RowsCursor(names, rows)
        }
    }

    private fun one(sql: String): Long = conn.prepare(sql).use { st -> if (st.step()) st.getLong(0) else 0L }

    override fun insertWithOnConflict(table: String, nullColumnHack: String?, values: SqlValues, conflict: Int): Long = run {
        val verb = when (conflict) {
            SqlDb.CONFLICT_ROLLBACK -> "INSERT OR ROLLBACK"
            SqlDb.CONFLICT_ABORT -> "INSERT OR ABORT"
            SqlDb.CONFLICT_FAIL -> "INSERT OR FAIL"
            SqlDb.CONFLICT_IGNORE -> "INSERT OR IGNORE"
            SqlDb.CONFLICT_REPLACE -> "INSERT OR REPLACE"
            else -> "INSERT"
        }
        val keys = values.map.keys.toList()
        val sql = if (keys.isEmpty()) "$verb INTO $table(${nullColumnHack ?: "rowid"}) VALUES(NULL)"
        else "$verb INTO $table(${keys.joinToString(",")}) VALUES(${keys.joinToString(",") { "?" }})"
        conn.prepare(sql).use { st -> st.bind(values.map.values.toTypedArray()); st.step() }
        if (one("SELECT changes()") == 0L) -1L else one("SELECT last_insert_rowid()")
    }

    override fun insert(table: String, nullColumnHack: String?, values: SqlValues): Long =
        try { insertWithOnConflict(table, nullColumnHack, values, SqlDb.CONFLICT_NONE) } catch (e: SqlException) { -1L }

    override fun update(table: String, values: SqlValues, where: String?, whereArgs: Array<out String?>?): Int = run {
        val keys = values.map.keys.toList()
        val sql = "UPDATE $table SET ${keys.joinToString(",") { "$it=?" }}" + (if (where.isNullOrEmpty()) "" else " WHERE $where")
        conn.prepare(sql).use { st -> st.bind((values.map.values.toList() + (whereArgs?.toList() ?: emptyList())).toTypedArray()); st.step() }
        one("SELECT changes()").toInt()
    }

    override fun delete(table: String, where: String?, whereArgs: Array<out String?>?): Int = run {
        val sql = "DELETE FROM $table" + (if (where.isNullOrEmpty()) "" else " WHERE $where")
        conn.prepare(sql).use { st -> st.bind(whereArgs); st.step() }
        one("SELECT changes()").toInt()
    }

    override fun beginTransaction() {
        lock.lock() // held until the matching endTransaction
        try {
            if (depth == 0) {
                execSQL("BEGIN IMMEDIATE")
                allGood = true
            }
            depth++
            thisGood.add(false)
        } catch (e: Exception) {
            lock.unlock()
            throw e
        }
    }

    override fun setTransactionSuccessful() {
        if (thisGood.isNotEmpty()) thisGood[thisGood.lastIndex] = true
    }

    override fun endTransaction() {
        try {
            if (depth == 0) return
            if (!thisGood.removeAt(thisGood.lastIndex)) allGood = false
            depth--
            if (depth == 0) execSQL(if (allGood) "COMMIT" else "ROLLBACK")
        } finally {
            lock.unlock()
        }
    }

    override var version: Int
        get() = run { one("PRAGMA user_version").toInt() }
        set(v) { execSQL("PRAGMA user_version = $v") }

    override fun close() = lock.withLock {
        if (open) { open = false; conn.close() }
    }
}

/** A query's rows, read in full (as Android's cursor window holds them). */
private class RowsCursor(private val names: List<String>, private val rows: List<Array<Any?>>) : SqlCursor {
    private var pos = -1
    override val count: Int get() = rows.size
    override val columnCount: Int get() = names.size
    override fun moveToNext(): Boolean { if (pos < rows.size) pos++; return pos < rows.size }
    override fun moveToFirst(): Boolean { pos = 0; return rows.isNotEmpty() }
    override fun getColumnName(i: Int) = names[i]
    private fun v(i: Int): Any? = rows[pos][i]
    override fun getType(i: Int): Int = when (v(i)) {
        null -> SqlCursor.FIELD_TYPE_NULL
        is Long -> SqlCursor.FIELD_TYPE_INTEGER
        is Double -> SqlCursor.FIELD_TYPE_FLOAT
        is ByteArray -> SqlCursor.FIELD_TYPE_BLOB
        else -> SqlCursor.FIELD_TYPE_STRING
    }
    override fun isNull(i: Int) = v(i) == null
    override fun getLong(i: Int): Long = when (val x = v(i)) {
        null -> 0L
        is Long -> x
        is Double -> x.toLong()
        is String -> x.trim().toLongOrNull() ?: x.trim().toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }
    override fun getInt(i: Int): Int = getLong(i).toInt()
    override fun getDouble(i: Int): Double = when (val x = v(i)) {
        null -> 0.0
        is Long -> x.toDouble()
        is Double -> x
        is String -> x.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
    override fun getFloat(i: Int): Float = getDouble(i).toFloat()
    override fun getString(i: Int): String? = when (val x = v(i)) {
        null -> null
        is ByteArray -> x.decodeToString()
        is Double -> sqliteText(x)
        else -> x.toString()
    }
    override fun getBlob(i: Int): ByteArray? = when (val x = v(i)) {
        null -> null
        is ByteArray -> x
        else -> x.toString().encodeToByteArray()
    }
    override fun close() = Unit

    /** A REAL as SQLite writes it as text (e.g. 2.0, 0.5). */
    private fun sqliteText(d: Double): String = d.toString()
}
