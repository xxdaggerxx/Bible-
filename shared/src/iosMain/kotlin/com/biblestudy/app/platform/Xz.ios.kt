package com.biblestudy.app.platform

import okio.Source

// TODO(iOS): unpack with Apple's Compression framework (COMPRESSION_LZMA reads xz).
actual fun xz(packed: Source): Source = throw UnsupportedOperationException("xz is not unpacked on iOS yet")
