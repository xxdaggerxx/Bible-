package com.biblestudy.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.setActive
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/** Writing sounds through AVAudioEngine; quiet when the phone's silent switch is on (ambient). */
actual fun openPcmOut(rate: Int, minBufferSamples: Int): PcmOut? = runCatching { IosPcmOut(rate) }.getOrNull()

@OptIn(ExperimentalForeignApi::class)
private class IosPcmOut(rate: Int) : PcmOut {
    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private val format = AVAudioFormat(AVAudioPCMFormatFloat32, rate.toDouble(), 1u, false)
    /** Buffers queued and not yet played: [write] waits while all are in use. */
    private val slots = dispatch_semaphore_create(4)

    init {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryAmbient, null)
        AVAudioSession.sharedInstance().setActive(true, null)
        engine.attachNode(player)
        engine.connect(player, engine.mainMixerNode, format)
        engine.prepare()
        check(engine.startAndReturnError(null)) { "No sound output" }
        player.play()
    }

    override fun write(pcm: ShortArray, count: Int) {
        if (count <= 0) return
        dispatch_semaphore_wait(slots, DISPATCH_TIME_FOREVER)
        val buf = AVAudioPCMBuffer(format, count.toUInt()) ?: return run { dispatch_semaphore_signal(slots) }
        buf.frameLength = count.toUInt()
        val ch = buf.floatChannelData!![0]!!
        for (i in 0 until count) ch[i] = pcm[i] / 32768f
        player.scheduleBuffer(buf) { dispatch_semaphore_signal(slots) }
    }

    override fun close() {
        player.stop()
        engine.stop()
    }
}

actual fun startThread(name: String, block: () -> Unit) {
    NSThread { block() }.apply { setName(name); setThreadPriority(1.0) }.start()
}
