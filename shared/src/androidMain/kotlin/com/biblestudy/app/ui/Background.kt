package com.biblestudy.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [block] on a background thread (database reads and the like) and comes back on the main
 * thread before the caller carries on, so the result is put into screen state there. A screen's
 * coroutines already run on the main thread on a device, so this costs nothing; under test, where
 * they can resume on whichever thread finished the work, it keeps state from being changed off the
 * main thread while the screen is being drawn, which could lose the change.
 */
suspend fun <T> background(block: suspend CoroutineScope.() -> T): T {
    val result = withContext(Dispatchers.IO, block)
    return withContext(Dispatchers.Main.immediate) { result }
}
