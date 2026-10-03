package com.biblestudy.app

import com.biblestudy.app.ui.WritingSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt

class WritingSoundTest {
    private fun rms(a: FloatArray) = sqrt(a.map { it * it }.average()).toFloat()
    private fun crossings(a: FloatArray) = (1 until a.size).count { (a[it - 1] < 0f) != (a[it] < 0f) }

    private fun render(t: WritingSound.Texture, target: Float, brightness: Float, n: Int = 22050): FloatArray {
        val s = WritingSound.Synth()
        s.texture = t; s.target = target; s.brightness = brightness
        return FloatArray(n).also { s.render(it) }
    }

    @Test
    fun silentWhenThePenIsStill() {
        assertEquals(0f, rms(render(WritingSound.Texture.PEN, 0f, 0f)), 1e-6f)
    }

    @Test
    fun fasterIsLouderAndBrighter() {
        val slow = render(WritingSound.Texture.PEN, 0.15f, 0.1f)
        val fast = render(WritingSound.Texture.PEN, 0.5f, 0.9f)
        assertTrue(rms(fast) > rms(slow) * 2)
        assertTrue(crossings(fast) > crossings(slow))
    }

    @Test
    fun eachToolSoundsDifferent() {
        val pen = render(WritingSound.Texture.PEN, 0.5f, 0.5f)
        val hl = render(WritingSound.Texture.HIGHLIGHTER, 0.5f, 0.5f)
        val er = render(WritingSound.Texture.ERASER, 0.5f, 0.5f)
        // The pen is the brightest, the eraser the lowest.
        assertTrue(crossings(pen) > crossings(hl))
        assertTrue(crossings(hl) > crossings(er))
        for (a in listOf(pen, hl, er)) assertTrue(a.all { abs(it) <= 1f })
    }

    /** Writes a short "stroke" of each tool to build/sounds, to listen to on a computer. */
    @Test
    fun sampleStrokes() {
        val dir = File("build/sounds").apply { mkdirs() }
        for (t in WritingSound.Texture.entries) {
            val s = WritingSound.Synth()
            s.texture = t
            val out = ArrayList<Float>()
            val chunk = FloatArray(441)
            // Three words' worth: speed rises and falls, with short lifts between.
            for (step in 0 until 300) {
                val inWord = (step % 100) < 80
                val pace = if (inWord) kotlin.math.sin((step % 100) / 80.0 * Math.PI).toFloat() else 0f
                s.brightness = pace
                s.target = 0.6f * sqrt(pace) * 0.5f
                s.render(chunk)
                out.addAll(chunk.toList())
            }
            wav(File(dir, "${t.name.lowercase()}.wav"), out.toFloatArray())
        }
    }

    private fun wav(f: File, a: FloatArray) {
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            fun i32(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())) }
            fun i16(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte())) }
            r.writeBytes("RIFF"); i32(36 + a.size * 2); r.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1)
            i32(WritingSound.RATE); i32(WritingSound.RATE * 2); i16(2); i16(16); r.writeBytes("data"); i32(a.size * 2)
            for (x in a) i16((x * 32767).toInt())
        }
    }
}
