package com.biblestudy.app.platform

/** Sound out, as 16-bit mono samples (writing sounds, INK-15). */
interface PcmOut {
    /** Plays [count] samples; blocks while the output is full. */
    fun write(pcm: ShortArray, count: Int)
    fun close()
}

/** Opens the sound output at [rate] samples a second, or null if there's none. */
expect fun openPcmOut(rate: Int, minBufferSamples: Int): PcmOut?

/** Runs [block] on a new background thread named [name], at high priority. */
expect fun startThread(name: String, block: () -> Unit)
