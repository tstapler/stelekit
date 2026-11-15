// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Touches the owner record's mtime every [intervalMs] while a run holds the lease. The mtime is the owner's last
 * proof of life and bounds which locks a later claimer may call the dead owner's (see `LockRecovery` step 5).
 * A starved heartbeat only makes a later claim fail closed with `ForeignLockSuspected`; it never causes a delete.
 * The [scope] belongs to the entry point that claimed the run, never to a composable.
 */
class OwnerHeartbeat(
    private val scope: CoroutineScope,
    private val lockFiles: LockFiles,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val logger = Logger("OwnerHeartbeat")
    private var job: Job? = null

    fun start() {
        stop()
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                beat()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun beat() {
        try {
            lockFiles.touchRecord()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("owner heartbeat failed", e)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
