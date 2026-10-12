package com.biblestudy.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs jobs in the background, at most [threads] at a time (like a fixed thread pool). */
class WorkerPool(threads: Int) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(threads))
    fun execute(block: () -> Unit) { scope.launch { block() } }
}
