package com.biblestudy.app.platform

/**
 * The SQLite the app uses, the same on every platform. On Android it is Android's own SQLite
 * (so the notes database opens exactly as it always has); on computers and iOS a bundled SQLite.
 * The calls mirror Android's, which the app was written against.
 */
interface SqlDb : AutoCloseable {
    val path: String
    val isOpen: Boolean

    fun execSQL(sql: String)
    fun execSQL(sql: String, args: Array<out Any?>)
    fun rawQuery(sql: String, args: Array<out String?>?): SqlCursor

    /** Inserts a row; returns its rowid, or -1 if it failed or was ignored. */
    fun insertWithOnConflict(table: String, nullColumnHack: String?, values: SqlValues, conflict: Int): Long
    fun insert(table: String, nullColumnHack: String?, values: SqlValues): Long = insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE)
    fun update(table: String, values: SqlValues, where: String?, whereArgs: Array<out String?>?): Int
    fun delete(table: String, where: String?, whereArgs: Array<out String?>?): Int

    fun beginTransaction()
    fun setTransactionSuccessful()
    fun endTransaction()

    var version: Int

    companion object {
        const val CONFLICT_NONE = 0
        const val CONFLICT_ROLLBACK = 1
        const val CONFLICT_ABORT = 2
        const val CONFLICT_FAIL = 3
        const val CONFLICT_IGNORE = 4
        const val CONFLICT_REPLACE = 5
    }
}

/** A query's rows, read one at a time. */
interface SqlCursor : AutoCloseable {
    val count: Int
    val columnCount: Int
    fun moveToNext(): Boolean
    fun moveToFirst(): Boolean
    fun getColumnName(i: Int): String
    fun getType(i: Int): Int
    fun isNull(i: Int): Boolean
    fun getInt(i: Int): Int
    fun getLong(i: Int): Long
    fun getShort(i: Int): Short = getInt(i).toShort()
    fun getFloat(i: Int): Float
    fun getDouble(i: Int): Double
    fun getString(i: Int): String?
    fun getBlob(i: Int): ByteArray?

    companion object {
        const val FIELD_TYPE_NULL = 0
        const val FIELD_TYPE_INTEGER = 1
        const val FIELD_TYPE_FLOAT = 2
        const val FIELD_TYPE_STRING = 3
        const val FIELD_TYPE_BLOB = 4
    }
}

/** Column values for an insert or update, in order. */
class SqlValues {
    val map = LinkedHashMap<String, Any?>()
    fun put(key: String, value: String?) { map[key] = value }
    fun put(key: String, value: Int?) { map[key] = value?.toLong() }
    fun put(key: String, value: Long?) { map[key] = value }
    fun put(key: String, value: Float?) { map[key] = value?.toDouble() }
    fun put(key: String, value: Double?) { map[key] = value }
    fun put(key: String, value: Boolean?) { map[key] = value?.let { if (it) 1L else 0L } }
    fun put(key: String, value: ByteArray?) { map[key] = value }
    fun putNull(key: String) { map[key] = null }
    fun getAsString(key: String): String? = map[key]?.toString()
    val size get() = map.size
}

/** Opens databases; one per platform. */
interface SqlDriver {
    /** Opens the database file at [path]; [create] makes it if it isn't there. */
    fun open(path: String, readOnly: Boolean = false, create: Boolean = false): SqlDb

    /** Opens a database kept by [helper], making or upgrading it to [version] (see [SqlOpenHelper]). */
    fun openHelper(path: String, version: Int, helper: SqlOpenHelper): SqlHelperHandle
}

/** An open helper's database, as opened by the platform. */
interface SqlHelperHandle : AutoCloseable {
    val writable: SqlDb
    val readable: SqlDb
}

/**
 * A database the app makes and upgrades itself, like Android's SQLiteOpenHelper (which it is, on
 * Android): opened when first used, [onCreate] for a new file, [onUpgrade] when [version] goes up.
 * After [close] it opens again when next used.
 */
abstract class SqlOpenHelper(private val driver: SqlDriver, val path: String, private val version: Int) : AutoCloseable {
    abstract fun onCreate(db: SqlDb)
    abstract fun onUpgrade(db: SqlDb, oldVersion: Int, newVersion: Int)

    /** Called when the file is found damaged, before the platform deals with it. */
    open fun onCorruption(path: String) {}

    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private var handle: SqlHelperHandle? = null

    private fun handle(): SqlHelperHandle = kotlinx.atomicfu.locks.synchronized(lock) {
        handle ?: driver.openHelper(path, version, this).also { handle = it }
    }

    val writableDatabase: SqlDb get() = handle().writable
    val readableDatabase: SqlDb get() = handle().readable

    override fun close() {
        kotlinx.atomicfu.locks.synchronized(lock) {
            handle?.close()
            handle = null
        }
    }
}

/** What a database call throws when SQLite reports an error. */
expect open class SqlException : RuntimeException
