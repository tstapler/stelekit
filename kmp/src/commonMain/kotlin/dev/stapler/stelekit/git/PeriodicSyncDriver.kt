// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Why a scheduled tick did not run its body. */
sealed interface SkipReason {
    /** A manual sync or another tick is in flight. */
    data object Busy : SkipReason

    /** The network monitor reports offline; not a failure. */
    data object Offline : SkipReason
}

/** Result of one scheduled tick. */
sealed interface TickOutcome {
    data object Success : TickOutcome
    data class Failure(val message: String, val error: DomainError? = null) : TickOutcome
    data class Skip(val reason: SkipReason) : TickOutcome
}

/**
 * Owns the interval loop for scheduled sync: waits one interval, runs [tick], repeats. The tick body
 * is injected so the policy (what a tick does) can change without touching the loop.
 *
 * Runs on the caller's [scope]; the caller owns its lifetime and exception handler.
 */
class PeriodicSyncDriver(
    private val tick: suspend () -> TickOutcome,
    private val scope: CoroutineScope,
    private val launchContext: CoroutineContext = EmptyCoroutineContext,
) {
    @kotlin.concurrent.Volatile private var job: Job? = null

    /** Cancels any running loop, then starts one ticking every [intervalMinutes]. No-op for `<= 0`. */
    fun start(intervalMinutes: Int) {
        stop()
        if (intervalMinutes <= 0) return
        job = scope.launch(launchContext) {
            while (true) {
                delay(intervalMinutes * MILLIS_PER_MINUTE)
                tick()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
