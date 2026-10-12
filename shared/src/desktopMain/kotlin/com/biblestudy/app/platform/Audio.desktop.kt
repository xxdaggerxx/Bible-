package com.biblestudy.app.platform

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

actual fun openPcmOut(rate: Int, minBufferSamples: Int): PcmOut? = runCatching {
    val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
    val line = AudioSystem.getSourceDataLine(format)
    line.open(format, maxOf(minBufferSamples * 2, rate / 10 * 2))
    line.start()
    object : PcmOut {
        private var bytes = ByteArray(0)
        override fun write(pcm: ShortArray, count: Int) {
            if (bytes.size < count * 2) bytes = ByteArray(count * 2)
            for (i in 0 until count) { val v = pcm[i].toInt(); bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte() }
            line.write(bytes, 0, count * 2)
        }
        override fun close() { runCatching { line.drain(); line.close() } }
    }
}.getOrNull()

actual fun startThread(name: String, block: () -> Unit) {
    Thread(block, name).apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
}
