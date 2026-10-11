package com.biblestudy.app.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.Buffer
import okio.Source
import okio.buffer
import okio.use
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmLZMA
import platform.Foundation.NSError
import platform.Foundation.create
import platform.Foundation.decompressedDataUsingAlgorithm

/**
 * Unpacked with Foundation's LZMA, which reads the xz format. It unpacks the whole file at once
 * (the largest bundled file is under 50 MB unpacked), so it needs iOS 13 or later.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun xz(packed: Source): Source {
    val bytes = packed.buffer().use { it.readByteArray() }
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val unpacked = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        data.decompressedDataUsingAlgorithm(NSDataCompressionAlgorithmLZMA, error.ptr)
            ?: throw okio.IOException("Couldn't unpack: ${error.value?.localizedDescription}")
    }
    return Buffer().write(unpacked.toByteArray())
}
