package com.biblestudy.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { b ->
    b.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, n.toULong(), it.addressOf(0)) == 0) { "No secure random numbers" } }
}
