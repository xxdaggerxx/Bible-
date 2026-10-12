package com.biblestudy.app.platform

actual fun relativeTime(at: Long, now: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(at, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
