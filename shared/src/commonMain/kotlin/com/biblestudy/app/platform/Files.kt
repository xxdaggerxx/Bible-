package com.biblestudy.app.platform

import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * Files, the same on every platform (Okio), with the handful of calls the app makes, named as
 * in java.io.File so the code reads as before.
 */
expect val fs: FileSystem

fun Path.exists(): Boolean = fs.exists(this)
val Path.isFile: Boolean get() = fs.metadataOrNull(this)?.isRegularFile == true
val Path.isDirectory: Boolean get() = fs.metadataOrNull(this)?.isDirectory == true
fun Path.length(): Long = fs.metadataOrNull(this)?.size ?: 0L
fun Path.lastModified(): Long = fs.metadataOrNull(this)?.lastModifiedAtMillis ?: 0L

/** Deletes a file or empty folder; returns whether it was there. */
fun Path.delete(): Boolean = if (fs.exists(this)) { runCatching { fs.delete(this) }.isSuccess } else false
fun Path.deleteRecursively(): Boolean = runCatching { fs.deleteRecursively(this) }.isSuccess
fun Path.mkdirs(): Path { fs.createDirectories(this); return this }
fun Path.listFiles(): List<Path>? = if (isDirectory) fs.list(this) else null

fun Path.readBytes(): ByteArray = fs.read(this) { readByteArray() }
fun Path.readText(): String = fs.read(this) { readUtf8() }
fun Path.writeBytes(b: ByteArray) { fs.write(this) { write(b) } }
fun Path.writeText(s: String) { fs.write(this) { writeUtf8(s) } }
fun Path.lines(): List<String> = readText().lines()

/** Moves this file to [to], replacing it; returns whether it worked. */
fun Path.renameTo(to: Path): Boolean = runCatching { fs.atomicMove(this, to) }.isSuccess

fun Path.copyTo(to: Path, overwrite: Boolean = false): Path {
    if (!overwrite && fs.exists(to)) throw okio.IOException("$to already exists")
    to.parent?.let { fs.createDirectories(it) }
    fs.copy(this, to)
    return to
}

/** Copies a folder's files and folders into [to]. */
fun Path.copyRecursively(to: Path, overwrite: Boolean = false) {
    fs.createDirectories(to)
    for (p in fs.list(this)) {
        val dest = to / p.name
        if (p.isDirectory) p.copyRecursively(dest, overwrite) else p.copyTo(dest, overwrite)
    }
}

fun Path.source() = fs.source(this).buffer()
fun Path.sink() = fs.sink(this).buffer()
