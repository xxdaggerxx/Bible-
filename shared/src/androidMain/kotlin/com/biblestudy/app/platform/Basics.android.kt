package com.biblestudy.app.platform

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun uptimeMillis(): Long = android.os.SystemClock.uptimeMillis()
