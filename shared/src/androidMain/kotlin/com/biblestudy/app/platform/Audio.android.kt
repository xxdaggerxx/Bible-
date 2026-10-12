package com.biblestudy.app.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

actual fun openPcmOut(rate: Int, minBufferSamples: Int): PcmOut? = runCatching {
    val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val b = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        )
        .setAudioFormat(
            AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        )
        .setBufferSizeInBytes(maxOf(min, minBufferSamples * 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
    val t = b.build().also { it.play() }
    object : PcmOut {
        override fun write(pcm: ShortArray, count: Int) { t.write(pcm, 0, count) }
        override fun close() { runCatching { t.stop(); t.release() } }
    }
}.getOrNull()

actual fun startThread(name: String, block: () -> Unit) {
    Thread(block, name).apply { priority = Thread.MAX_PRIORITY; start() }
}
