package com.biblestudy.app

import androidx.compose.ui.graphics.Color
import com.biblestudy.app.data.UserDb
import com.biblestudy.app.platform.DesktopAppContext
import com.biblestudy.app.platform.FilePrefs
import com.biblestudy.app.platform.SqlCursor
import com.biblestudy.app.platform.SqlDb
import com.biblestudy.app.ui.ChapterExport
import okio.Path.Companion.toOkioPath
import okio.sink
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The computer version: notes made on Android open intact, settings keep, exports are valid files. */
class DesktopTest {
    private val home = Files.createTempDirectory("inkandword").toFile()
    private val ctx = DesktopAppContext(home.toOkioPath())
    private val fixture = File("../app/src/test/resources/fixtures/v2.10.0")

    @Test
    fun aNotesDatabaseFromAndroid2_10_0OpensIntactWithTheBundledSqlite() {
        val file = ctx.getDatabasePath("golden.db").toFile()
        file.parentFile.mkdirs()
        File(fixture, "userdata.db").copyTo(file)
        val expected = File(fixture, "userdata.dump.txt").readText()
        val db = UserDb(ctx, "golden.db")
        try {
            db.writableDatabase // runs any upgrade
            db.checkpoint()
            assertEquals(expected, dump(db.readableDatabase, columnsOf(expected)))
            assertEquals(listOf("My Notes", "Sermon prep"), db.layers().map { it.name })
            assertEquals("A typed note", db.note(43, 3, 16))
        } finally {
            db.close()
        }
    }

    @Test
    fun settingsAreKeptBetweenRuns() {
        ctx.prefs("study").edit(now = true) {
            putString("version", "KJV"); putInt("book", 43); putBoolean("dark", true)
            putFloat("size", 1.5f); putStringSet("tags", setOf("a", "b"))
        }
        val again = FilePrefs(home.toOkioPath() / "settings" / "study.json")
        assertEquals("KJV", again.getString("version", null))
        assertEquals(43, again.getInt("book", 0))
        assertEquals(true, again.getBoolean("dark", false))
        assertEquals(1.5f, again.getFloat("size", 0f))
        assertEquals(setOf("a", "b"), again.getStringSet("tags", null))
    }

    @Test
    fun aChapterExportsAsAPdfAndAPicture() {
        val pdf = File(home, "c.pdf")
        ChapterExport.pdf(pdf.sink(), 800f, 3000f, "John 3 (KJV)") { drawRect(Color.Blue) }
        val text = pdf.readBytes().decodeToString()
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue("/Count 3 " in text, "3000 points tall is three A4 pages")
        // Checked with a real PDF reader when one is installed.
        if (File("/usr/bin/pdfinfo").exists()) {
            val p = ProcessBuilder("pdfinfo", pdf.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), out)
            assertTrue(Regex("Pages:\\s+3").containsMatchIn(out), out)
        }
        val png = File(home, "c.png")
        ChapterExport.png(png.sink(), 800f, 1000f, "John 3 (KJV)") { drawRect(Color.Blue) }
        val bytes = png.readBytes()
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code), bytes.take(4).map { it.toInt() and 0xFF })
    }

    private fun columnsOf(dump: String): Map<String, List<String>> =
        dump.lines().filter { it.isNotEmpty() && !it.startsWith(" ") }.associate { line ->
            line.substringBefore('(') to line.substringAfter('(').removeSuffix(")").split(", ")
        }

    /** As DataSafetyTest.dump on Android: every row of the given tables and columns, as text. */
    private fun dump(db: SqlDb, only: Map<String, List<String>>): String {
        val sb = StringBuilder()
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('android_metadata', 'sqlite_sequence') ORDER BY name", null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)!!) } }.filter { it in only }
        for (t in tables) {
            val cols = only.getValue(t)
            sb.append(t).append('(').append(cols.joinToString(", ")).append(")\n")
            val order = (1..cols.size).joinToString(", ")
            db.rawQuery("SELECT ${cols.joinToString(", ")} FROM $t ORDER BY $order", null).use { c ->
                while (c.moveToNext()) sb.append("  ").append((0 until c.columnCount).joinToString(" | ") { value(c, it) }).append('\n')
            }
        }
        return sb.toString()
    }

    private fun value(c: SqlCursor, i: Int): String = when (c.getType(i)) {
        SqlCursor.FIELD_TYPE_NULL -> "NULL"
        SqlCursor.FIELD_TYPE_BLOB -> "x'" + c.getBlob(i)!!.joinToString("") { "%02x".format(it) } + "'"
        SqlCursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
        SqlCursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toString()
        else -> "'" + c.getString(i) + "'"
    }
}
