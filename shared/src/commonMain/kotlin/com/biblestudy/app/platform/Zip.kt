package com.biblestudy.app.platform

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Deflater
import okio.deflate
import okio.Timeout
import okio.Inflater
import okio.InflaterSource
import okio.Sink
import okio.Source
import okio.buffer
import okio.use

/**
 * Zip files, read and written the same on every platform: backups (DATA-3) and Bibles picked as
 * .zip. Reads what Java's ZipOutputStream writes (as earlier versions of the app did, with data
 * descriptors after deflated entries) and writes files any unzip tool reads.
 */
object Zip {
    private const val LOCAL = 0x04034b50
    private const val CENTRAL = 0x02014b50
    private const val END = 0x06054b50
    private const val DESCRIPTOR = 0x08074b50

    /** Something to read a zip from at any offset: a file, or bytes in memory. */
    private interface At {
        val size: Long
        fun at(offset: Long): BufferedSource
    }

    /** Reads the zip file at [path]: see [read]. */
    fun readFile(path: okio.Path, onEntry: (name: String, isDirectory: Boolean, data: BufferedSource) -> Boolean) {
        fs.openReadOnly(path).use { h ->
            read(object : At {
                override val size = h.size()
                override fun at(offset: Long) = h.source(offset).buffer()
            }, onEntry)
        }
    }

    /** Reads a zip held in memory: see [read]. */
    fun readBytes(bytes: ByteArray, onEntry: (name: String, isDirectory: Boolean, data: BufferedSource) -> Boolean) {
        read(object : At {
            override val size = bytes.size.toLong()
            override fun at(offset: Long) = Buffer().write(bytes, offset.toInt(), bytes.size - offset.toInt())
        }, onEntry)
    }

    /**
     * Reads the entries of a zip, in order, from its central directory (so entries written with
     * their sizes after the data, as Java does, read exactly). [onEntry] gets each entry's name
     * and contents; it returns false to stop.
     */
    private fun read(zip: At, onEntry: (name: String, isDirectory: Boolean, data: BufferedSource) -> Boolean) {
        // The end record is in the last 64 KB (after an optional comment).
        val tail = minOf(zip.size, 65_557L)
        val end = zip.at(zip.size - tail).use { it.readByteArray(tail) }
        var e = end.size - 22
        fun intAt(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
        while (e >= 0 && intAt(end, e) != END) e--
        if (e < 0) throw okio.IOException("Not a zip file")
        val count = (end[e + 10].toInt() and 0xFF) or ((end[e + 11].toInt() and 0xFF) shl 8)
        val dirOffset = intAt(end, e + 16).toLong() and 0xFFFFFFFFL
        class Entry(val name: String, val method: Int, val compressed: Long, val local: Long)
        val entries = ArrayList<Entry>()
        zip.at(dirOffset).use { d ->
            repeat(count) {
                if (d.readIntLe() != CENTRAL) throw okio.IOException("Damaged zip directory")
                d.skip(6) // made by, needed, flags
                val method = d.readShortLe().toInt() and 0xFFFF
                d.skip(8) // time, date, crc
                val compressed = d.readIntLe().toLong() and 0xFFFFFFFFL
                d.skip(4) // size
                val nameLen = d.readShortLe().toInt() and 0xFFFF
                val extraLen = d.readShortLe().toInt() and 0xFFFF
                val commentLen = d.readShortLe().toInt() and 0xFFFF
                d.skip(8) // disk, attributes
                val local = d.readIntLe().toLong() and 0xFFFFFFFFL
                val name = d.readUtf8(nameLen.toLong())
                d.skip((extraLen + commentLen).toLong())
                entries += Entry(name, method, compressed, local)
            }
        }
        for (en in entries) {
            val go = zip.at(en.local).use { h ->
                if (h.readIntLe() != LOCAL) throw okio.IOException("Damaged zip entry ${en.name}")
                h.skip(22)
                val nameLen = h.readShortLe().toInt() and 0xFFFF
                val extraLen = h.readShortLe().toInt() and 0xFFFF
                h.skip((nameLen + extraLen).toLong())
                val raw = LimitedSource(h, en.compressed)
                val data: Source = when (en.method) {
                    0 -> raw
                    8 -> InflaterSource(raw.buffer(), Inflater(true))
                    else -> throw okio.IOException("Unsupported zip compression ${en.method} in ${en.name}")
                }
                onEntry(en.name, en.name.endsWith("/"), data.buffer())
            }
            if (!go) return
        }
    }

    /** Writes a zip to [sink]: [putEntry] for each file, then [finish]. */
    class Writer(private val sink: BufferedSink, private val level: Int = 6) {
        private class Entry(val name: ByteArray, val offset: Long, val crc: Int, val compressed: Long, val size: Long, val method: Int)

        private val entries = ArrayList<Entry>()
        private var offset = 0L

        /** Adds a file named [name] with what [write] writes. */
        fun putEntry(name: String, write: (BufferedSink) -> Unit) {
            val nameBytes = name.encodeToByteArray()
            val start = offset
            header(LOCAL, nameBytes, crc = 0, compressed = 0, size = 0, flags = 0x0808, method = 8, local = true)
            val counting = CountingSink(sink)
            val crc = Crc32()
            val raw = CrcSink(counting.deflate(Deflater(level, true)), crc)
            raw.buffer().use { write(it) }
            offset += counting.count
            // Data descriptor: signature, crc, sizes.
            sink.writeIntLe(DESCRIPTOR); sink.writeIntLe(crc.value); sink.writeIntLe(counting.count.toInt()); sink.writeIntLe(crc.length.toInt())
            offset += 16
            entries += Entry(nameBytes, start, crc.value, counting.count, crc.length, 8)
        }

        fun putEntry(name: String, bytes: ByteArray) = putEntry(name) { it.write(bytes) }

        /** Adds a file with what [from] holds, and closes it. */
        fun putEntry(name: String, from: Source) = from.use { src -> putEntry(name) { it.writeAll(src) } }

        fun finish() {
            val dirStart = offset
            for (e in entries) {
                header(CENTRAL, e.name, e.crc, e.compressed, e.size, flags = 0x0808, method = e.method, local = false, at = e.offset)
            }
            val dirSize = offset - dirStart
            sink.writeIntLe(END)
            sink.writeShortLe(0); sink.writeShortLe(0)
            sink.writeShortLe(entries.size); sink.writeShortLe(entries.size)
            sink.writeIntLe(dirSize.toInt()); sink.writeIntLe(dirStart.toInt())
            sink.writeShortLe(0)
            sink.flush()
        }

        private fun header(sig: Int, name: ByteArray, crc: Int, compressed: Long, size: Long, flags: Int, method: Int, local: Boolean, at: Long = 0) {
            sink.writeIntLe(sig)
            if (!local) sink.writeShortLe(20) // made by
            sink.writeShortLe(20) // needed
            sink.writeShortLe(flags) // data descriptor, UTF-8 names
            sink.writeShortLe(method)
            sink.writeIntLe(DOS_1980) // time and date
            sink.writeIntLe(crc)
            sink.writeIntLe(compressed.toInt())
            sink.writeIntLe(size.toInt())
            sink.writeShortLe(name.size)
            sink.writeShortLe(0) // extra
            var n = 30 + name.size
            if (!local) {
                sink.writeShortLe(0) // comment
                sink.writeShortLe(0) // disk
                sink.writeShortLe(0) // internal attributes
                sink.writeIntLe(0) // external attributes
                sink.writeIntLe(at.toInt())
                n = 46 + name.size
            }
            sink.write(name)
            offset += n
        }

        private companion object {
            /** 1 January 1980, 00:00 in DOS form. */
            const val DOS_1980 = (0 shl 0) or (0 shl 5) or (0 shl 11) or (1 shl 16) or (1 shl 21) or (0 shl 25)
        }
    }

    private class LimitedSource(private val source: BufferedSource, private var left: Long) : Source by source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (left <= 0) return -1
            val n = source.read(sink, minOf(byteCount, left))
            if (n > 0) left -= n
            return n
        }
        override fun close() = Unit // the zip goes on
    }

    private class CountingSink(private val delegate: Sink) : Sink {
        var count = 0L
        override fun write(source: Buffer, byteCount: Long) { count += byteCount; delegate.write(source, byteCount) }
        override fun flush() = delegate.flush()
        override fun timeout(): Timeout = delegate.timeout()
        override fun close() = flush() // the zip goes on
    }

    private class CrcSink(private val delegate: Sink, private val crc: Crc32) : Sink {
        override fun write(source: Buffer, byteCount: Long) {
            crc.update(source.peek().readByteArray(byteCount))
            delegate.write(source, byteCount)
        }
        override fun flush() = delegate.flush()
        override fun timeout(): Timeout = delegate.timeout()
        override fun close() = delegate.close()
    }
}

/** CRC-32 (as in zip and gzip). */
class Crc32 {
    private var crc = 0.inv()
    var length = 0L
        private set
    val value: Int get() = crc.inv()

    fun update(b: ByteArray) {
        var c = crc
        for (x in b) c = TABLE[(c xor x.toInt()) and 0xFF] xor (c ushr 8)
        crc = c
        length += b.size
    }

    private companion object {
        val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
            c
        }
    }
}
