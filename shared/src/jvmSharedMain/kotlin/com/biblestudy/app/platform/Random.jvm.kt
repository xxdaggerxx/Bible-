package com.biblestudy.app.platform

actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }
