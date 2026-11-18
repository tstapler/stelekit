// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Story 3.4.2: the one pending auto-retry after a rate-limited git step. Runs on the owning service's own
 * [scope] (never `rememberCoroutineScope()`), so shutting the service down cancels it. Scheduling replaces any
 * earlier retry, and a manual sync calls [cancel] so it supersedes a pending one.
 */
internal class RateLimitRetry(
    private val scope: CoroutineScope,
    /** Suspends until the graph's repository gate is free, so a retry never fires into a running sync. */
    private val awaitFree: suspend (String) -> Unit = {},
) {
    @kotlin.concurrent.Volatile private var job: Job? = null

    /** Re-invokes [retryOperation] after [retryAfterSeconds], or [DEFAULT_SECONDS] when the header was absent. */
    fun schedule(graphId: String, retryAfterSeconds: Int?, retryOperation: suspend (String) -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay((retryAfterSeconds ?: DEFAULT_SECONDS) * 1000L)
            awaitFree(graphId)
            // Clear first: the retried sync()/fetchOnly() calls cancel() at their top and must not cancel this coroutine.
            job = null
            retryOperation(graphId)
        }
    }

    fun cancel() {
        job?.cancel()
    }

    companion object {
        const val DEFAULT_SECONDS = 60
    }
}
