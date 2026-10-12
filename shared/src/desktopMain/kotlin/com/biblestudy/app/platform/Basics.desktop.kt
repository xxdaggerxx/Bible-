package com.biblestudy.app.platform

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun uptimeMillis(): Long = System.nanoTime() / 1_000_000
