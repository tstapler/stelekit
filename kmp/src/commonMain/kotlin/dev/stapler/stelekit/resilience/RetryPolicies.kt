// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.resilience

import arrow.resilience.Schedule
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Named retry schedules for common transient failures.
 *
 * Using named constants allows tests to inject zero-delay schedules and production code to use
 * jittered backoff without scattering schedule configuration throughout the codebase.
 */
object RetryPolicies {
    /**
     * Retry policy for SQLITE_BUSY errors: up to 3 retries with jittered delay.
     * Jitter avoids thundering-herd on a single SQLite file shared by concurrent writes.
     */
    val sqliteBusy: Schedule<Throwable, Long> =
        Schedule.recurs<Throwable>(3).jittered()

    /**
     * Retry policy for file-watcher re-registration: exponential backoff starting at 100ms,
     * stopping when the delay would exceed 5 seconds.
     */
    val fileWatchReregistration: Schedule<Throwable, kotlin.time.Duration> =
        Schedule.exponential<Throwable>(100.milliseconds)
            .doUntil { _, duration -> duration > 5.seconds }

    /**
     * Zero-delay policy for unit tests: same retry count as [sqliteBusy] but no delay,
     * so tests run fast while still exercising the retry logic.
     */
    val testImmediate: Schedule<Throwable, Long> =
        Schedule.recurs<Throwable>(3)
}
