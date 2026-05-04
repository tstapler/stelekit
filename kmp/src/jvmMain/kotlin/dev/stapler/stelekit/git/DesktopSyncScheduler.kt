// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * JVM (Desktop) implementation of [BackgroundSyncScheduler].
 *
 * Uses a coroutine delay loop owned by a dedicated [CoroutineScope].
 * The callback is invoked after each interval elapses.
 */
class DesktopSyncScheduler(
    private val onTick: suspend () -> Unit,
) : BackgroundSyncScheduler {

    private val scope = CoroutineScope(SupervisorJob() + PlatformDispatcher.IO)
    private var timerJob: Job? = null

    override fun schedule(intervalMinutes: Int) {
        cancel()
        timerJob = scope.launch {
            while (true) {
                delay(intervalMinutes * 60_000L)
                onTick()
            }
        }
    }

    override fun cancel() {
        timerJob?.cancel()
        timerJob = null
    }

    /** Fully shuts down the scheduler, releasing all resources. */
    fun shutdown() {
        scope.cancel()
    }
}
