package com.biblestudy.app.platform

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlin.math.abs
import kotlin.math.roundToLong

/** [x] with [places] decimals, e.g. 2.5 → "2.5" (as "%.1f" does in English). */
fun formatDecimal(x: Double, places: Int): String {
    var scale = 1L
    repeat(places) { scale *= 10 }
    val n = (abs(x) * scale).roundToLong()
    val whole = n / scale
    val frac = (n % scale).toString().padStart(places, '0')
    return (if (x < 0 && n != 0L) "-" else "") + whole + if (places > 0) ".$frac" else ""
}

/** A date as the platform shows it, e.g. "Oct 11, 2026". */
expect fun formatDate(millis: Long): String

/** A time of day as the platform shows it, e.g. "4:25 PM". */
expect fun formatTime(millis: Long): String

/** How long ago, e.g. "5 minutes ago", "Yesterday", "Oct 3". */
expect fun relativeTime(at: Long, now: Long): String

fun DayOfWeek.fullName(): String = name.lowercase().replaceFirstChar { it.uppercase() }
fun DayOfWeek.shortName(): String = fullName().take(3)
fun DayOfWeek.narrowName(): String = fullName().take(1)
fun Month.shortName(): String = name.lowercase().replaceFirstChar { it.uppercase() }.take(3)

/** "Mon 3 Oct". */
fun LocalDate.dayAndMonth(withWeekday: Boolean = true): String =
    (if (withWeekday) dayOfWeek.shortName() + " " else "") + "$dayOfMonth ${month.shortName()}"
