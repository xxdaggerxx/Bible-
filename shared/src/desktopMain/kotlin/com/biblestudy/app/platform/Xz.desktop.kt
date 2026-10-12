package com.biblestudy.app.platform

import okio.Source
import okio.buffer
import okio.source

actual fun xz(packed: Source): Source = org.tukaani.xz.XZInputStream(packed.buffer().inputStream().buffered(1 shl 16)).source()
