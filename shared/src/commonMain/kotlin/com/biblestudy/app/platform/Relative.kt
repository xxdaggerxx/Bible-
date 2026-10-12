package com.biblestudy.app.platform

/** "5 minutes ago", "3 hours ago", "Yesterday", else the date: for platforms without their own. */
fun simpleRelativeTime(at: Long, now: Long): String {
    val min = (now - at) / 60_000
    return when {
        min < 1 -> "Just now"
        min < 60 -> if (min == 1L) "1 minute ago" else "$min minutes ago"
        min < 24 * 60 -> (min / 60).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
        min < 48 * 60 -> "Yesterday"
        else -> formatDate(at)
    }
}
