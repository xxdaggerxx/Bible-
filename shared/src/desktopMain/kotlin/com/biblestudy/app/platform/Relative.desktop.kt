package com.biblestudy.app.platform

actual fun relativeTime(at: Long, now: Long): String = simpleRelativeTime(at, now)
