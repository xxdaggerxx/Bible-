package com.biblestudy.app.platform

import platform.Foundation.NSThread

// TODO(iOS): play writing sounds through AVAudioEngine; until then there's no sound.
actual fun openPcmOut(rate: Int, minBufferSamples: Int): PcmOut? = null

actual fun startThread(name: String, block: () -> Unit) {
    NSThread { block() }.apply { setName(name); setThreadPriority(1.0) }.start()
}
