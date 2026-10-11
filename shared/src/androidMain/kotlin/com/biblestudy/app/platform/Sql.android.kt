package com.biblestudy.app.platform

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DefaultDatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Android's own SQLite, exactly as the app used it before it was shared (PLAT-2). */
class AndroidSqlDriver(private val context: Context) : SqlDriver {
    override fun open(path: String, readOnly: Boolean, create: Boolean): SqlDb {
        val db = if (create) {
            SQLiteDatabase.openOrCreateDatabase(File(path), null)
        } else {
            SQLiteDatabase.openDatabase(path, null, if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE)
        }
        return AndroidSqlDb(db)
    }

    override fun openHelper(path: String, version: Int, helper: SqlOpenHelper): SqlHelperHandle {
        // A plain name opens in the app's databases folder, as Android's helper always did.
        val name = File(path).let { if (it.parentFile == context.getDatabasePath("x").parentFile) it.name else path }
        val android = object : SQLiteOpenHelper(
            context, name, null, version,
            { db -> helper.onCorruption(db.path); DefaultDatabaseErrorHandler().onCorruption(db) },
        ) {
            override fun onCreate(db: SQLiteDatabase) = helper.onCreate(AndroidSqlDb(db))
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = helper.onUpgrade(AndroidSqlDb(db), oldVersion, newVersion)
        }
        return object : SqlHelperHandle {
            private var w: AndroidSqlDb? = null
            private var r: AndroidSqlDb? = null
            override val writable: SqlDb
                get() = android.writableDatabase.let { db -> w?.takeIf { it.db === db } ?: AndroidSqlDb(db).also { w = it } }
            override val readable: SqlDb
                get() = android.readableDatabase.let { db -> r?.takeIf { it.db === db } ?: AndroidSqlDb(db).also { r = it } }
            override fun close() = android.close()
        }
    }
}

/** One of Android's databases. */
class AndroidSqlDb(val db: SQLiteDatabase) : SqlDb {
    override val path: String get() = db.path
    override val isOpen: Boolean get() = db.isOpen
    override fun execSQL(sql: String) = db.execSQL(sql)
    override fun execSQL(sql: String, args: Array<out Any?>) = db.execSQL(sql, args)
    override fun rawQuery(sql: String, args: Array<out String?>?): SqlCursor = AndroidSqlCursor(db.rawQuery(sql, args))
    override fun insertWithOnConflict(table: String, nullColumnHack: String?, values: SqlValues, conflict: Int): Long =
        db.insertWithOnConflict(table, nullColumnHack, values.android(), conflict)
    override fun insert(table: String, nullColumnHack: String?, values: SqlValues): Long = db.insert(table, nullColumnHack, values.android())
    override fun update(table: String, values: SqlValues, where: String?, whereArgs: Array<out String?>?): Int =
        db.update(table, values.android(), where, whereArgs)
    override fun delete(table: String, where: String?, whereArgs: Array<out String?>?): Int = db.delete(table, where, whereArgs)
    override fun beginTransaction() = db.beginTransaction()
    override fun setTransactionSuccessful() = db.setTransactionSuccessful()
    override fun endTransaction() = db.endTransaction()
    override var version: Int
        get() = db.version
        set(v) { db.version = v }
    override fun close() = db.close()
}

private fun SqlValues.android() = ContentValues(size).also { cv ->
    for ((k, v) in map) when (v) {
        null -> cv.putNull(k)
        is Long -> cv.put(k, v)
        is Double -> cv.put(k, v)
        is String -> cv.put(k, v)
        is ByteArray -> cv.put(k, v)
        else -> cv.put(k, v.toString())
    }
}

class AndroidSqlCursor(private val c: Cursor) : SqlCursor {
    override val count: Int get() = c.count
    override val columnCount: Int get() = c.columnCount
    override fun moveToNext() = c.moveToNext()
    override fun moveToFirst() = c.moveToFirst()
    override fun getColumnName(i: Int): String = c.getColumnName(i)
    override fun getType(i: Int) = c.getType(i)
    override fun isNull(i: Int) = c.isNull(i)
    override fun getInt(i: Int) = c.getInt(i)
    override fun getLong(i: Int) = c.getLong(i)
    override fun getShort(i: Int) = c.getShort(i)
    override fun getFloat(i: Int) = c.getFloat(i)
    override fun getDouble(i: Int) = c.getDouble(i)
    override fun getString(i: Int): String? = c.getString(i)
    override fun getBlob(i: Int): ByteArray? = c.getBlob(i)
    override fun close() = c.close()
}
