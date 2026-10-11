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
 * Writing sounds (INK-15): a pen-on-paper sound that follows every stroke. It gets louder and
 * brighter as the pen moves faster or presses harder, and fades out when the pen stops or lifts.
 * Each tool has its own sound:
 *  - pen: a pencil scratching;
 *  - highlighter: a marker's felt-tip swish;
 *  - eraser: a softer drawing sound.
 *
 * The sounds are recordings (free sounds from Pixabay, made into seamless loops by
 * tools/build_sounds.py), played by [Sampler]. If they can't be loaded, [Synth] makes a similar
 * sound from filtered noise. An [AudioTrack] plays it on a background thread through media volume,
 * and only while the pen is on the page.
 *
 * @param loadLoops reads the recorded loops (called once, off the main thread).
 */
class WritingSound(private val loadLoops: () -> Map<Texture, FloatArray>? = { null }) {
    enum class Texture { PEN, HIGHLIGHTER, ERASER }

    /** The sound itself, kept apart from the audio output so it can be tested. */
    interface Voice {
        var texture: Texture
        /** Loudness the sound moves towards, 0..1 (speed, pressure and the volume setting). */
        var target: Float
        /** 0..1: how bright (fast) the stroke is. */
        var brightness: Float
        /** A new stroke begins. */
        fun restart() {}
        /** Fills [out] with the next samples (-1..1). */
        fun render(out: FloatArray)
    }

    /**
     * Plays the recorded [loops] (at [srcRate]) round and round while the pen moves. Faster strokes
     * play them a little faster and brighter; slow ones are softer and duller.
     */
    class Sampler(
        private val loops: Map<Texture, FloatArray>,
        private val srcRate: Int = LOOP_RATE,
        private val rate: Int = RATE,
        seed: Long = 1L,
    ) : Voice {
        @Volatile override var texture = Texture.PEN
        @Volatile override var target = 0f
        @Volatile override var brightness = 0f

        private var level = 0f
        private var pos = 0.0
        private var low = 0f
        private val random = java.util.Random(seed)

        /** Each stroke starts somewhere new in the loop, so strokes don't all sound the same. */
        override fun restart() {
            val loop = loops[texture] ?: return
            pos = random.nextInt(loop.size.coerceAtLeast(1)).toDouble()
        }

        override fun render(out: FloatArray) {
            val loop = loops[texture]
            if (loop == null || loop.size < 2) { out.fill(0f); return }
            val attack = 1f - exp(-1f / (rate * 0.012f))
            val release = 1f - exp(-1f / (rate * 0.06f))
            val b = brightness.coerceIn(0f, 1f)
            val step = srcRate.toDouble() / rate * (0.9 + 0.2 * b)
            val cut = 0.3f + 0.7f * b
            val n = loop.size
            for (i in out.indices) {
                val goal = target
                level += (goal - level) * if (goal > level) attack else release
                val at = pos.toInt()
                val frac = (pos - at).toFloat()
                val x = loop[at % n] * (1f - frac) + loop[(at + 1) % n] * frac
                low += (x - low) * cut
                out[i] = (low * level * GAIN).coerceIn(-1f, 1f)
                pos += step
                if (pos >= n) pos -= n
            }
        }
    }

    /** The made-up sound, used when the recordings can't be loaded. */
    class Synth(private val rate: Int = RATE, seed: Long = 1L) : Voice {
        @Volatile override var texture = Texture.PEN
        @Volatile override var target = 0f
        @Volatile override var brightness = 0f

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

        override fun render(out: FloatArray) {
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

    /** The recordings once loaded (on the audio thread, on the first stroke), else the made-up sound. */
    @Volatile private var voice: Voice = Synth()
    @Volatile private var loaded = false
    private val synth: Voice get() = voice
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
        synth.restart()
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
        if (!loaded) {
            loaded = true
            runCatching { loadLoops() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { loops ->
                val old = voice
                voice = Sampler(loops).apply { texture = old.texture; target = old.target; brightness = old.brightness; restart() }
            }
        }
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
        /** The recorded loops' sample rate (tools/build_sounds.py). */
        const val LOOP_RATE = 32000
        /** The loops are about -16 dBFS; this brings them level with the made-up sound. */
        private const val GAIN = 2.2f
        private const val CHUNK = 256

        /** The recorded loops in assets/sounds, one per tool. */
        fun loadLoops(assets: android.content.res.AssetManager): Map<Texture, FloatArray> =
            Texture.entries.associateWith { t -> assets.open("sounds/${t.name.lowercase()}.wav").use { readWav(it.readBytes()) } }

        /** The samples (-1..1) of a 16-bit mono WAV file. */
        fun readWav(bytes: ByteArray): FloatArray {
            val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var at = 12
            while (at + 8 <= bytes.size) {
                val id = String(bytes, at, 4, Charsets.US_ASCII)
                val size = b.getInt(at + 4)
                if (id == "data") {
                    val n = minOf(size, bytes.size - at - 8) / 2
                    return FloatArray(n) { b.getShort(at + 8 + it * 2) / 32768f }
                }
                at += 8 + size + (size and 1)
            }
            return FloatArray(0)
        }
    }
}
