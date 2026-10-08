package com.biblestudy.app

import androidx.test.core.app.ApplicationProvider
import com.biblestudy.app.data.RemoteFile
import com.biblestudy.app.data.SyncEngine
import com.biblestudy.app.data.SyncStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A Google Drive app folder in memory, shared by the test's "devices". */
class MemoryStore : SyncStore {
    val files = LinkedHashMap<String, ByteArray>()
    var writes = 0
    override fun list() = files.keys.map { RemoteFile(it, it) }
    override fun read(f: RemoteFile) = files[f.name] ?: throw java.io.IOException("gone")
    override fun write(name: String, data: ByteArray) { files[name] = data; writes++ }
    override fun delete(f: RemoteFile) { files.remove(f.name) }
}

/** Sync between devices (SYNC-1): two notes databases meeting in one folder. */
@RunWith(RobolectricTestRunner::class)
class SyncTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val store = MemoryStore()
    private val opened = ArrayList<UserDb>()

    private inner class Device(name: String) {
        val db = UserDb(app, "sync-$name.db").also { opened += it }
        val images = File(app.filesDir, "images-$name").apply { deleteRecursively(); mkdirs() }
        val engine = SyncEngine(db, store, name, images)
        fun sync() = engine.sync()
    }

    @After
    fun close() = opened.forEach { it.close() }

    private fun stroke(id: Long, book: Int = 43, region: Region = Region.TEXT, x: Float = 10f, chapter: Int = 3) =
        InkStroke(id, 1L, if (region == Region.TEXT) "KJV" else null, book, chapter, region, 16, false, 0xFF112233.toInt(), 2.5f, floatArrayOf(x, 20f, 0.5f, x + 30f, 25f, 0.7f))

    private fun pause() = Thread.sleep(5) // so later edits have later times

    @Test
    fun notesInkAndBookmarksReachTheOtherDevice() {
        val tablet = Device("tablet"); val phone = Device("phone")
        tablet.db.insert(stroke(100))
        tablet.db.insert(Highlight(101, 1L, "KJV", 43, 3, 10, 40, 0x66FFEE00))
        tablet.db.setNote(43, 3, 16, "God's love", endVerse = 17)
        tablet.db.addBookmark(Bookmark(102, 19, 23, 1, 5L, "Psalms"))
        tablet.db.setTags("n:43:3:16", setOf("love"))
        tablet.db.setMeaning(0x66FFEE00, "Promises")
        tablet.db.saveLayer(Layer(2L, "Questions", 0xFF0000FF.toInt(), visible = true, locked = false, sort = 1))
        tablet.db.saveWorkspace("Desk", "{}") // a layout: stays on the tablet
        assertTrue(tablet.sync().sent > 0)

        val got = phone.sync()
        assertTrue(got.changed)
        val (strokes, hls) = phone.db.loadText("KJV", 43, 3)
        assertEquals(100L, strokes.single().id)
        assertArrayEquals(stroke(100).points, strokes.single().points, 0f)
        assertEquals(0xFF112233.toInt(), strokes.single().color)
        assertEquals(101L, hls.single().id)
        val note = phone.db.noteCovering(43, 3, 17)!!
        assertEquals("God's love", note.text)
        assertEquals(17, note.endVerse)
        assertEquals("Psalms", phone.db.bookmarks().single().folder)
        assertEquals(setOf("love"), phone.db.tags()["n:43:3:16"])
        assertEquals("Promises", phone.db.meanings()[0x66FFEE00])
        assertEquals(listOf("My Notes", "Questions"), phone.db.layers().map { it.name })
        assertTrue(phone.db.workspaces().isEmpty())

        // Nothing new: nothing sent, nothing read again.
        val writes = store.writes
        assertEquals(0, phone.sync().sent)
        assertEquals(0, tablet.sync().received)
        assertEquals(writes, store.writes)
    }

    @Test
    fun deletesAndEditsGoBothWaysAndTheNewestWins() {
        val tablet = Device("tablet"); val phone = Device("phone")
        tablet.db.insert(stroke(200, region = Region.RIGHT))
        tablet.db.insert(stroke(201, region = Region.RIGHT, x = 50f))
        val box = MarginText(202, 1L, 43, 3, Region.RIGHT, 16, 5f, 5f, 200f, "First thought")
        tablet.db.insert(box)
        tablet.sync(); phone.sync()

        // The phone erases a stroke and edits the box; later the tablet edits the box again.
        phone.db.delete(stroke(201, region = Region.RIGHT))
        pause(); phone.db.insert(box.copy(text = "Phone's thought"))
        pause(); tablet.db.insert(box.copy(text = "Tablet's later thought"))
        phone.sync(); tablet.sync(); phone.sync()

        for (d in listOf(tablet, phone)) {
            assertEquals(listOf(200L), d.db.loadMargin(43, 3).first.map { it.id })
            assertEquals("Tablet's later thought", d.db.loadTexts(43, 3).single().text)
        }
    }

    @Test
    fun aNoteChangedOnBothDevicesBeforeSyncingKeepsBoth() {
        val tablet = Device("tablet"); val phone = Device("phone")
        tablet.db.setNote(43, 3, 16, "Loved")
        tablet.sync(); phone.sync()
        phone.db.setNote(43, 3, 16, "Loved the world")
        pause(); tablet.db.setNote(43, 3, 16, "Gave his Son")
        phone.sync(); tablet.sync(); phone.sync()
        for (d in listOf(tablet, phone)) {
            val text = d.db.note(43, 3, 16)!!
            assertTrue(text, text.contains("Loved the world") && text.contains("Gave his Son"))
        }
        assertEquals(tablet.db.note(43, 3, 16), phone.db.note(43, 3, 16))
    }

    @Test
    fun sketchPagesNumberedAlikeStaySeparate() {
        val tablet = Device("tablet"); val phone = Device("phone")
        // Each device's first page is its page 1, with different drawings on it.
        val a = Sketch(1, "Tablet page", Paper.BLANK, 43, 3, 16, Sketch.START_HEIGHT, 1_700_000_000_000L, uid = "tabletpage")
        val b = Sketch(1, "Phone page", Paper.LINED, 0, 0, 0, Sketch.START_HEIGHT, 1_700_000_000_001L, uid = "phonepage")
        tablet.db.saveSketch(a); tablet.db.insert(stroke(300, book = a.book, region = Region.RIGHT, chapter = 1))
        phone.db.saveSketch(b); phone.db.insert(stroke(301, book = b.book, region = Region.RIGHT, chapter = 1))
        tablet.sync(); phone.sync(); tablet.sync()

        for (d in listOf(tablet, phone)) {
            val pages = d.db.sketches()
            assertEquals(setOf("Tablet page", "Phone page"), pages.map { it.name }.toSet())
            assertEquals(2, pages.map { it.id }.toSet().size)
            val t = pages.first { it.uid == "tabletpage" }; val p = pages.first { it.uid == "phonepage" }
            assertEquals(listOf(300L), d.db.loadMargin(t.book, 1).first.map { it.id })
            assertEquals(listOf(301L), d.db.loadMargin(p.book, 1).first.map { it.id })
        }
        // Deleting a page on one device deletes it, and what's on it, on the other.
        val onPhone = phone.db.sketches().first { it.uid == "tabletpage" }
        phone.db.deleteSketch(onPhone)
        phone.sync(); tablet.sync()
        assertEquals(listOf("Phone page"), tablet.db.sketches().map { it.name })
        assertTrue(tablet.db.loadMargin(a.book, 1).first.isEmpty() || tablet.db.sketches().none { it.book == a.book })
    }

    @Test
    fun readyMadePagesAreNotDoubled() {
        val tablet = Device("tablet"); val phone = Device("phone")
        // Both devices made the same ready-made page with the same ids, dated long ago.
        for (d in listOf(tablet, phone)) {
            val s = Sketch(if (d === tablet) 1 else 5, "Tabernacle", Paper.BLANK, 0, 0, 0, Sketch.START_HEIGHT, 3L, uid = "ready-3")
            d.db.saveSketch(s)
            d.db.insert(MarginText(8_000_000_000_000_300_000L, 1L, s.book, 1, Region.RIGHT, 1, 40f, 80f, 300f, "The Tabernacle"))
            com.biblestudy.app.data.Sync.markAsOriginal(d.db.writableDatabase, 0L)
        }
        tablet.sync(); phone.sync(); tablet.sync()
        for (d in listOf(tablet, phone)) {
            val s = d.db.sketches().single()
            assertEquals(1, d.db.loadTexts(s.book, 1).size)
        }
        // Deleted on the phone: the tablet's copy (dated long ago) doesn't bring it back.
        phone.db.deleteSketch(phone.db.sketches().single())
        phone.sync(); tablet.sync(); phone.sync()
        assertTrue(tablet.db.sketches().isEmpty())
        assertTrue(phone.db.sketches().isEmpty())
    }

    @Test
    fun picturesTravelWithTheirFile() {
        val tablet = Device("tablet"); val phone = Device("phone")
        File(tablet.images, "pic1.jpg").writeBytes(byteArrayOf(1, 2, 3, 4))
        tablet.db.insert(MarginImage(400, 1L, 43, 3, Region.RIGHT, 16, 0f, 0f, 100f, 80f, "pic1.jpg"))
        tablet.sync(); phone.sync()
        assertEquals("pic1.jpg", phone.db.loadMargin(43, 3).second.single().file)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(phone.images, "pic1.jpg").readBytes())
        // Removing the picture removes its file on the other device.
        phone.db.delete(phone.db.loadMargin(43, 3).second.single())
        phone.sync(); tablet.sync()
        assertTrue(tablet.db.loadMargin(43, 3).second.isEmpty())
        assertTrue(!File(tablet.images, "pic1.jpg").exists())
    }

    @Test
    fun manyChangesAreFoldedIntoOneCopyAndANewDeviceGetsEverything() {
        val tablet = Device("tablet")
        for (i in 0 until SyncEngine.COMPACT_AFTER + 5) {
            tablet.db.insert(stroke(1000L + i, x = i.toFloat()))
            if (i == 3) tablet.db.delete(stroke(1000L + 2, x = 2f))
            tablet.sync()
        }
        assertTrue(store.files.keys.any { it.startsWith("s-") })
        assertTrue(store.files.keys.count { it.startsWith("j-") } < SyncEngine.COMPACT_AFTER)
        val phone = Device("phone")
        phone.sync()
        val ids = phone.db.loadText("KJV", 43, 3).first.map { it.id }.toSet()
        assertEquals(SyncEngine.COMPACT_AFTER + 4, ids.size)
        assertTrue(1002L !in ids)
    }

    @Test
    fun aDeviceJoiningLaterKeepsItsOwnNotesToo() {
        val tablet = Device("tablet")
        tablet.db.setNote(1, 1, 1, "In the beginning")
        tablet.sync()
        // The phone had notes before sync was turned on.
        val phone = Device("phone")
        phone.db.setNote(19, 23, 1, "The Lord is my shepherd")
        phone.db.insert(stroke(500))
        phone.sync(); tablet.sync()
        for (d in listOf(tablet, phone)) {
            assertEquals("In the beginning", d.db.note(1, 1, 1))
            assertEquals("The Lord is my shepherd", d.db.note(19, 23, 1))
            assertEquals(listOf(500L), d.db.loadText("KJV", 43, 3).first.map { it.id })
        }
        assertNull(tablet.db.note(2, 1, 1))
    }
}
