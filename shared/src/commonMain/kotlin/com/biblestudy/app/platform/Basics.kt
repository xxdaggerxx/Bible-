package com.biblestudy.app.platform

import kotlin.random.Random
import kotlinx.datetime.toLocalDateTime

/** Milliseconds since 1970 (the wall clock). */
expect fun nowMillis(): Long

/** Milliseconds since some fixed point, for timing; never goes back. */
expect fun uptimeMillis(): Long

/** [n] random lower-case hex digits, e.g. for ids shared between devices. */
fun randomHex(n: Int): String = buildString(n) { repeat(n) { append("0123456789abcdef"[Random.nextInt(16)]) } }

/** The map with its keys in order (like Java's toSortedMap). */
fun <K : Comparable<K>, V> Map<K, V>.sortedByKey(): Map<K, V> = entries.sortedBy { it.key }.associate { it.key to it.value }

/** Today's date here, "yyyy-MM-dd". */
fun today(): String = kotlinx.datetime.Clock.System.now()
    .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date.toString()

/** A moment as "yyyy-MM-dd-HHmmss" in local time, for file names. */
fun stamp(millis: Long): String {
    val t = kotlinx.datetime.Instant.fromEpochMilliseconds(millis).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${t.year}-${two(t.monthNumber)}-${two(t.dayOfMonth)}-${two(t.hour)}${two(t.minute)}${two(t.second)}"
}
