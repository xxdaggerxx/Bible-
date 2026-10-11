package com.biblestudy.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.biblestudy.app.data.DataSafety
import com.biblestudy.app.platform.AndroidAppContext
import com.biblestudy.app.platform.AndroidSqlDb
import okio.Path.Companion.toOkioPath
import com.biblestudy.app.data.UserDb
import com.biblestudy.app.model.Bookmark
import com.biblestudy.app.model.Highlight
import com.biblestudy.app.model.InkStroke
import com.biblestudy.app.model.Layer
import com.biblestudy.app.model.MarginImage
import com.biblestudy.app.model.MarginText
import com.biblestudy.app.model.Paper
import com.biblestudy.app.model.Region
import com.biblestudy.app.model.Sketch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * Notes made with an earlier version must never be lost (DATA-8). `fixtures/v2.10.0` holds a notes
 * database written by version 2.10.0, with something in every table, and its pictures. These tests
 * open it with today's code and check every row and value is still there, read back the same.
 *
 * The fixture is never changed: a later version adds a new fixture beside it. To make one, run
 * `MAKE_FIXTURE=<version> ./gradlew testDebugUnitTest --tests '*DataSafetyTest.makeFixture'`.
 */
@RunWith(RobolectricTestRunner::class)
class DataSafetyTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val ctx = AndroidAppContext(app)
    private val opened = ArrayList<UserDb>()

    @After
    fun close() = opened.forEach { it.close() }

    /** Everything the fixture holds, written through the app's own code. */
    private object Content {
        val layers = listOf(
            Layer(1, "My Notes", UserDb.DEFAULT_LAYER_COLOR, visible = true, locked = false, sort = 0),
            Layer(2, "Sermon prep", 0xFF2E7D32.toInt(), visible = false, locked = true, sort = 1, opacity = 0.5f),
        )
        val sketch = Sketch(3, "Tabernacle", Paper.GRID, 2, 25, 8, 2400f, 1_700_000_000_000, note = false, uid = "0123456789abcdef0123456789abcdef")
        val noteSketch = Sketch(4, "Margin note", Paper.LINED, 43, 3, 16, Sketch.START_HEIGHT, 1_700_000_100_000, note = true, uid = "fedcba9876543210fedcba9876543210")
        val strokes = listOf(
            // On the words of John 3:16 (KJV), a pen and a freehand highlighter.
            InkStroke(101, 1, "KJV", 43, 3, Region.TEXT, 16, false, 0xFF1565C0.toInt(), 2.5f, floatArrayOf(10f, 3.25f, 0.5f, 40.5f, 3.5f, 0.75f, 80f, 3.4f, 1f)),
            InkStroke(102, 2, "BSB", 43, 3, Region.TEXT, 17, true, 0x66FFEB3B, 14f, floatArrayOf(0f, 5f, 1f, 120f, 5f, 1f), font = "SERIF"),
            // In both margins, shared across versions.
            InkStroke(103, 1, null, 43, 3, Region.LEFT, 16, false, 0xFF000000.toInt(), 1.75f, floatArrayOf(-3.5f, 12f, 0.1f, 22f, -8f, 0.9f)),
            InkStroke(104, 1, null, 1, 1, Region.RIGHT, 1, false, 0xFFC62828.toInt(), 3f, floatArrayOf(5f, 5f, 0.5f)),
            // On a sketch page.
            InkStroke(105, 1, null, sketch.book, 1, Region.LEFT, 0, false, 0xFF6D4C41.toInt(), 4f, FloatArray(300) { it * 1.5f }),
        )
        val highlights = listOf(
            Highlight(201, 1, "KJV", 43, 3, 120, 188, 0x80FFEB3B.toInt()),
            Highlight(202, 2, "BSB", 19, 23, 0, 40, 0x8081C784.toInt(), underline = true),
        )
        val images = listOf(
            MarginImage(301, 1, 43, 3, Region.RIGHT, 16, 12f, 30f, 200f, 150f, "margin-1.png", rotation = 1, cropL = 0.1f, cropT = 0.2f, cropR = 0.9f, cropB = 0.8f),
            MarginImage(302, 1, sketch.book, 1, Region.LEFT, 0, 100f, 200f, 400f, 300f, "sketch-1.png"),
        )
        val texts = listOf(
            MarginText(401, 1, 43, 3, Region.LEFT, 16, 4f, 8f, 180f, "God so loved — the world!\nSecond line", 22f, 0xFF333333.toInt(), 0x20FFEB3B, "0-3-80ffeb3b;8-13-8081c784-u"),
            MarginText(402, 2, sketch.book, 1, Region.LEFT, 0, 50f, 60f, 300f, "On the sketch page"),
        )
        val notes = listOf(Triple(43 to 3, 16 to 16, "A typed note"), Triple(19 to 23, 1 to 6, "A note over verses 1–6 with ünïcödé"))
        val bookmarks = listOf(Bookmark(501, 43, 3, 16, 1_700_000_200_000), Bookmark(502, 1, 1, 1, 1_700_000_300_000, folder = "Creation"))
        val tags = mapOf("n:43:3:16" to setOf("gospel", "love"), "h:201" to setOf("promise"))
        val meanings = mapOf(0x80FFEB3B.toInt() to "Promises", 0x8081C784.toInt() to "Commands")
        val workspace = "Study" to """{"panels":[{"version":"KJV","book":43,"chapter":3}]}"""
    }

    private fun write(db: UserDb, images: File) {
        Content.layers.drop(1).forEach { db.saveLayer(it) }
        db.saveSketch(Content.sketch)
        db.saveSketch(Content.noteSketch)
        (Content.strokes + Content.highlights + Content.images + Content.texts).forEach { db.insert(it) }
        for ((bc, verses, text) in Content.notes) db.setNote(bc.first, bc.second, verses.first, text, verses.second)
        Content.bookmarks.forEach { db.addBookmark(it) }
        Content.tags.forEach { (item, t) -> db.setTags(item, t) }
        Content.meanings.forEach { (c, l) -> db.setMeaning(c, l) }
        db.saveWorkspace(Content.workspace.first, Content.workspace.second)
        db.addReading("2024-05-01", 43, 3, 125, study = false)
        db.addReading("2024-05-01", 43, 3, 40, study = true)
        db.addOpen(43, 3)
        db.markRead(43, 3, 1_700_000_400_000)
        images.mkdirs()
        // Two small "pictures" (their bytes only need to come back the same).
        File(images, "margin-1.png").writeBytes(ByteArray(2000) { (it * 7).toByte() })
        File(images, "sketch-1.png").writeBytes(ByteArray(5000) { (it * 13).toByte() })
    }

    /** Writes a new fixture for version $MAKE_FIXTURE (see the class comment). */
    @Test
    fun makeFixture() {
        val version = System.getenv("MAKE_FIXTURE")
        assumeTrue(version != null)
        val db = UserDb(app, "fixture.db").also { opened += it }
        val images = File(app.filesDir, "fixture-images").apply { deleteRecursively() }
        write(db, images)
        db.checkpoint()
        db.close()
        val out = File("src/test/resources/fixtures/v$version").apply { deleteRecursively(); mkdirs() }
        app.getDatabasePath("fixture.db").copyTo(File(out, "userdata.db"))
        images.copyRecursively(File(out, "images"))
        SQLiteDatabase.openDatabase(File(out, "userdata.db").path, null, SQLiteDatabase.OPEN_READONLY).use {
            File(out, "userdata.dump.txt").writeText(dump(it))
        }
    }

    /** Copies fixture [version] in as this device's notes; returns the database file. */
    private fun install(version: String, name: String = UserDb.NAME): File {
        val src = File(javaClass.classLoader!!.getResource("fixtures/v$version/userdata.db")!!.toURI()).parentFile!!
        val db = app.getDatabasePath(name)
        db.parentFile!!.mkdirs()
        File(src, "userdata.db").copyTo(db, overwrite = true)
        File(src, "images").copyRecursively(File(app.filesDir, "images"), overwrite = true)
        return db
    }

    private fun expectedDump(version: String) =
        javaClass.classLoader!!.getResource("fixtures/v$version/userdata.dump.txt")!!.readText()

    @Test
    fun everyRowOfA2_10_0DatabaseSurvivesOpeningWithThisVersion() {
        val file = install("2.10.0", "golden.db")
        val expected = expectedDump("2.10.0")
        // The fixture is as it was made.
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(expected, dump(it)) }

        val db = UserDb(app, "golden.db").also { opened += it }
        db.writableDatabase // runs any upgrade
        db.checkpoint()
        // Every table, column and value the old version wrote is still there (new columns may be added).
        assertEquals(expected, dump((db.readableDatabase as AndroidSqlDb).db, columnsOf(expected)))

        // And the app reads it back the same.
        assertEquals(Content.layers, db.layers())
        assertEquals(listOf(Content.sketch, Content.noteSketch), db.sketches().filter { it.id in setOf(3L, 4L) })
        val (kjvInk, kjvHl) = db.loadText("KJV", 43, 3)
        assertSameStroke(Content.strokes[0], kjvInk.single())
        assertEquals(listOf(Content.highlights[0]), kjvHl)
        assertSameStroke(Content.strokes[1], db.loadText("BSB", 43, 3).first.single())
        assertEquals(listOf(Content.highlights[1]), db.loadText("BSB", 19, 23).second)
        val (margin, pictures) = db.loadMargin(43, 3)
        assertSameStroke(Content.strokes[2], margin.single())
        assertEquals(listOf(Content.images[0]), pictures)
        assertSameStroke(Content.strokes[3], db.loadMargin(1, 1).first.single())
        val (sketchInk, sketchPictures) = db.loadMargin(Content.sketch.book, 1)
        assertSameStroke(Content.strokes[4], sketchInk.single())
        assertEquals(listOf(Content.images[1]), sketchPictures)
        assertEquals(listOf(Content.texts[0]), db.allTexts())
        assertEquals(listOf(Content.texts[1]), db.loadTexts(Content.sketch.book, 1))
        assertEquals("A typed note", db.note(43, 3, 16))
        assertEquals(6, db.notes(19, 23)[1]!!.endVerse)
        assertEquals(Content.bookmarks, db.bookmarks().sortedBy { it.id })
        assertEquals(Content.tags, db.tags())
        assertEquals(Content.meanings, db.meanings())
        assertEquals(listOf(Content.workspace), db.workspaces())
        assertEquals(listOf(Triple("2024-05-01", 165, 40)), db.readingDays())
        db.readingChapters().single().let { assertEquals(listOf(165L, 1L, 1L, 1_700_000_400_000L), listOf(it.seconds.toLong(), it.opens.toLong(), it.timesRead.toLong(), it.lastRead)) }
    }

    @Test
    fun theFirstStartOfANewVersionBacksUpTheNotesBeforeOpeningThem() {
        install("2.10.0")
        val dir = File(app.filesDir, "safety-test").apply { deleteRecursively() }
        val zip = DataSafety.beforeOpening(ctx, 999, "9.9.9", dir.toOkioPath())?.toFile()
        assertNotNull(zip)
        assertEquals("bible-study-before-9.9.9.zip", zip!!.name)
        // Only once per version.
        assertNull(DataSafety.beforeOpening(ctx, 999, "9.9.9", dir.toOkioPath())?.toFile())

        // The zip is an ordinary backup: the notes database, unchanged, and every picture.
        val out = File(app.cacheDir, "safety-unzip").apply { deleteRecursively(); mkdirs() }
        ZipFile(zip).use { z ->
            assertEquals(setOf("userdata.db", "images/margin-1.png", "images/sketch-1.png"), z.entries().toList().map { it.name }.toSet())
            z.entries().toList().forEach { e -> File(out, e.name).apply { parentFile!!.mkdirs() }.outputStream().use { z.getInputStream(e).copyTo(it) } }
        }
        SQLiteDatabase.openDatabase(File(out, "userdata.db").path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(expectedDump("2.10.0"), dump(it))
        }
        assertArrayEquals(File(app.filesDir, "images/sketch-1.png").readBytes(), File(out, "images/sketch-1.png").readBytes())

        // A later version makes its own; only the newest few are kept.
        for (v in 1000..1004) DataSafety.beforeOpening(ctx, v, "v$v", dir.toOkioPath())?.toFile()?.setLastModified(v * 1000L)
        assertEquals(DataSafety.KEEP, dir.listFiles()!!.count { it.name.startsWith(DataSafety.PREFIX) })
    }

    @Test
    fun aNewInstallHasNothingToBackUp() {
        app.getDatabasePath(UserDb.NAME).delete()
        val dir = File(app.filesDir, "safety-new").apply { deleteRecursively() }
        assertNull(DataSafety.beforeOpening(ctx, 999, "9.9.9", dir.toOkioPath())?.toFile())
        assertTrue(!dir.exists() || dir.listFiles()!!.isEmpty())
    }

    private fun assertSameStroke(e: InkStroke, a: InkStroke) {
        assertEquals(
            listOf(e.id, e.layerId, e.version, e.book, e.chapter, e.region, e.verse, e.highlighter, e.color, e.width, e.lineAnchored, e.font),
            listOf(a.id, a.layerId, a.version, a.book, a.chapter, a.region, a.verse, a.highlighter, a.color, a.width, a.lineAnchored, a.font),
        )
        assertArrayEquals(e.points, a.points, 0f)
    }

    companion object {
        /**
         * Every row of every table, in a fixed order, as text: "table(col, …)" then one line per row.
         * With [only], just those tables and columns (to compare an upgraded database with an old one).
         */
        fun dump(db: SQLiteDatabase, only: Map<String, List<String>>? = null): String {
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('android_metadata', 'sqlite_sequence') ORDER BY name", null,
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                .filter { only == null || it in only }
            val sb = StringBuilder()
            for (t in tables) {
                val cols = only?.get(t) ?: db.rawQuery("PRAGMA table_info($t)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
                sb.append(t).append('(').append(cols.joinToString(", ")).append(")\n")
                val order = (1..cols.size).joinToString(", ")
                db.rawQuery("SELECT ${cols.joinToString(", ")} FROM $t ORDER BY $order", null).use { c ->
                    while (c.moveToNext()) sb.append("  ").append((0 until c.columnCount).joinToString(" | ") { value(c, it) }).append('\n')
                }
            }
            return sb.toString()
        }

        private fun value(c: Cursor, i: Int): String = when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> "NULL"
            Cursor.FIELD_TYPE_BLOB -> "x'" + c.getBlob(i).joinToString("") { "%02x".format(it) } + "'"
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toString()
            else -> "'" + c.getString(i) + "'"
        }

        /** The tables and columns named in a dump's header lines. */
        fun columnsOf(dump: String): Map<String, List<String>> =
            dump.lines().filter { it.isNotEmpty() && !it.startsWith(" ") }.associate { line ->
                line.substringBefore('(') to line.substringAfter('(').removeSuffix(")").split(", ")
            }
    }
}
