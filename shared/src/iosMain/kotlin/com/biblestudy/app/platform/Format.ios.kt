package com.biblestudy.app.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970

private fun format(millis: Long, date: ULong, time: ULong): String {
    val f = NSDateFormatter()
    f.dateStyle = date
    f.timeStyle = time
    return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
}

actual fun formatDate(millis: Long): String = format(millis, NSDateFormatterMediumStyle, NSDateFormatterNoStyle)

actual fun formatTime(millis: Long): String = format(millis, NSDateFormatterNoStyle, NSDateFormatterShortStyle)

actual fun relativeTime(at: Long, now: Long): String = simpleRelativeTime(at, now)
