package com.biblestudy.app

import com.biblestudy.app.platform.Zip
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Backups made by earlier versions (Java's zip) and by this one (the shared zip code) read back on both. */
class ZipTest {
    private val files = linkedMapOf(
        "userdata.db" to ByteArray(70_000) { (it * 31 % 251).toByte() },
        "images/a.png" to ByteArray(3) { it.toByte() },
        "images/empty.jpg" to ByteArray(0),
        "bibles/imported.json" to "[{\"code\":\"BAK\"}]".encodeToByteArray(),
    )

    private fun readShared(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        Zip.readBytes(bytes) { name, _, data -> out[name] = data.readByteArray(); true }
        return out
    }

    private fun assertSame(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys.toList(), actual.keys.toList())
        for ((k, v) in expected) assertArrayEquals(k, v, actual[k])
    }

    @Test
    fun javaZipsReadWithTheSharedReader() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((k, v) in files) { z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() } }
        assertSame(files, readShared(bos.toByteArray()))
    }

    @Test
    fun sharedZipsReadWithJavaAndTheSharedReader() {
        val buf = Buffer()
        val w = Zip.Writer(buf)
        for ((k, v) in files) w.putEntry(k, v)
        w.finish()
        val bytes = buf.readByteArray()
        val java = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; java[e.name] = z.readBytes() } }
        assertSame(files, java)
        assertSame(files, readShared(bytes))
    }
}
