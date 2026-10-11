package com.biblestudy.app.platform

import kotlin.random.Random

/** Milliseconds since 1970 (the wall clock). */
expect fun nowMillis(): Long

/** Milliseconds since some fixed point, for timing; never goes back. */
expect fun uptimeMillis(): Long

/** [n] random lower-case hex digits, e.g. for ids shared between devices. */
fun randomHex(n: Int): String = buildString(n) { repeat(n) { append("0123456789abcdef"[Random.nextInt(16)]) } }
