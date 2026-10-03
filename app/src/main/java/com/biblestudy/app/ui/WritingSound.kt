package com.biblestudy.app.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Writing sounds (INK-15): a soft pen-on-paper sound made on the tablet as you write, so it follows
 * every stroke. It gets louder and brighter as the pen moves faster or presses harder, and fades
 * out when the pen stops or lifts. Each tool has its own texture:
 *  - pen: fine, bright scratching with the grain of the paper;
 *  - highlighter: a broader, softer felt-tip swish;
 *  - eraser: a low rubbing.
 *
 * The sound is filtered noise shaped by [Synth]; an [AudioTrack] plays it on a background thread
 * through media volume, and only while the pen is on the page.
 */
class WritingSound {
    enum class Texture { PEN, HIGHLIGHTER, ERASER }

    /** The sound itself, kept apart from the audio output so it can be tested. */
    class Synth(private val rate: Int = RATE, seed: Long = 1L) {
        @Volatile var texture = Texture.PEN
        /** Loudness the sound moves towards, 0..1 (speed, pressure and the volume setting). */
        @Volatile var target = 0f
        /** 0..1: how bright (fast) the stroke is. */
        @Volatile var brightness = 0f

        private var level = 0f
        private var low = 0f
        private var low2 = 0f
        private var bass = 0f
        private var grain = 0f
        private var phase = 0f
        private var rnd = seed xor 0x5DEECE66DL

        private fun noise(): Float {
            // xorshift: fast, and plenty random for a hiss.
            rnd = rnd xor (rnd shl 13); rnd = rnd xor (rnd ushr 7); rnd = rnd xor (rnd shl 17)
            return ((rnd ushr 40).toInt() and 0xFFFF) / 32768f - 1f
        }

        /** Fills [out] with the next samples (-1..1). */
        fun render(out: FloatArray) {
            // The level glides towards its target so the sound never clicks on or off.
            val attack = 1f - exp(-1f / (rate * 0.012f))
            val release = 1f - exp(-1f / (rate * 0.06f))
            val t = texture
            val b = brightness.coerceIn(0f, 1f)
            // Low-pass amounts: brighter for the pen and when moving fast.
            val cut = when (t) {
                Texture.PEN -> 0.30f + 0.25f * b
                Texture.HIGHLIGHTER -> 0.12f + 0.18f * b
                Texture.ERASER -> 0.04f + 0.06f * b
            }
            for (i in out.indices) {
                val goal = target
                level += (goal - level) * if (goal > level) attack else release
                val n = noise()
                low += (n - low) * cut
                low2 += (low - low2) * cut
                bass += (low2 - bass) * 0.05f
                var s = when (t) {
                    // A band of hiss (bright minus its own low end) with the occasional paper grain tick.
                    Texture.PEN -> (low2 - bass) * 1.7f
                    Texture.HIGHLIGHTER -> low2 * 1.6f
                    Texture.ERASER -> low2 * 2.4f
                }
                if (t == Texture.PEN) {
                    if (grain <= 0f && noise() > 1f - 0.002f * (1f + 4f * b)) grain = 1f
                    s += grain * low * 1.2f
                    grain *= 0.92f
                } else if (t == Texture.ERASER) {
                    // Rubbing: the sound swells back and forth with the strokes of the hand.
                    phase += 9f / rate
                    if (phase > 1f) phase -= 1f
                    s *= 0.7f + 0.3f * abs(phase * 2f - 1f)
                }
                out[i] = (s * level).coerceIn(-1f, 1f)
            }
        }
    }

    @Volatile var enabled = true
    /** 0..1, from Settings. */
    @Volatile var volume = 0.6f

    private val synth = Synth()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var lastMove = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var speed = 0f

    /** The pen touched down with [texture]. */
    fun start(texture: Texture, x: Float, y: Float, timeMs: Long) {
        if (!enabled || volume <= 0f) return
        synth.texture = texture
        synth.target = 0f
        lastX = x; lastY = y; lastT = timeMs; speed = 0f
        lastMove = now()
        ensureRunning()
    }

    /** The pen moved: [x], [y] in screen pixels, [density] to judge speed the same on every screen. */
    fun move(x: Float, y: Float, pressure: Float, timeMs: Long, density: Float) {
        if (!running) return
        val dt = (timeMs - lastT).coerceAtLeast(1L)
        if (dt < 4L) return
        val d = hypot(x - lastX, y - lastY) / density
        val v = d * 1000f / dt // dp per second
        speed += (v - speed) * 0.5f
        lastX = x; lastY = y; lastT = timeMs
        lastMove = now()
        val pace = (speed / 600f).coerceIn(0f, 1f)
        val press = 0.55f + 0.45f * pressure.coerceIn(0f, 1f)
        synth.brightness = pace
        synth.target = volume * press * sqrt(pace) * when (synth.texture) {
            Texture.PEN -> 0.5f
            Texture.HIGHLIGHTER -> 0.45f
            Texture.ERASER -> 0.6f
        }
    }

    /** The pen lifted: the sound fades away. */
    fun stop() {
        synth.target = 0f
    }

    private fun ensureRunning() {
        if (running) return
        val t = runCatching { makeTrack() }.getOrNull() ?: return
        track = t
        running = true
        thread = Thread({ loop(t) }, "writing-sound").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun makeTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val b = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(min, CHUNK * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return b.build().also { it.play() }
    }

    private fun loop(t: AudioTrack) {
        val f = FloatArray(CHUNK)
        val pcm = ShortArray(CHUNK)
        try {
            while (running) {
                // Silence while the pen rests; after a second of it, the audio stops until the next stroke.
                if (now() - lastMove > 120L) synth.target = 0f
                if (now() - lastMove > 1000L) break
                synth.render(f)
                for (i in f.indices) pcm[i] = (f[i] * 32767f).toInt().toShort()
                t.write(pcm, 0, min(pcm.size, CHUNK))
            }
        } catch (_: Throwable) {
        } finally {
            running = false
            runCatching { t.stop(); t.release() }
            if (track === t) track = null
        }
    }

    /** Real elapsed time (not the wall clock, which can stand still or jump). */
    private fun now() = System.nanoTime() / 1_000_000L

    fun release() {
        running = false
    }

    companion object {
        const val RATE = 44100
        private const val CHUNK = 256
    }
}
