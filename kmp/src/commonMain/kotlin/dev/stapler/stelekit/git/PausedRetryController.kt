// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How one attempt of a paused retry ended, as far as the retry budget cares. */
internal enum class PausedAttemptResult { Settled, StillUnsure, Coalesced }

/** Who ended a sync on an unknown owner; only a user press may restart a spent budget. */
internal enum class PausedSource { User, Background }

/**
 * The automatic retries after a sync paused on an unknown lock owner (ADR-001 decision 14, UX-51/52).
 *
 * The timer is an in-process coroutine on the service scope, like [RateLimitRetry]: it is lost with the process and
 * nothing is persisted; the durable re-evaluation is the periodic worker, which reaches `sync` through the gate and
 * claim and so never bypasses this budget. "Retry now" is an extra attempt that never moves [pending]'s try number.
 */
internal class PausedRetryController(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val policy: PausedRetryPolicy,
    /** Suspends until the repository gate is free, so a timed retry never fires into a running sync. */
    private val awaitFree: suspend (String) -> Unit,
    /** One normal sync attempt through the gate; its result is classified here, not by the service wrapper. */
    private val attempt: suspend (String) -> PausedAttemptResult,
) {
    private val logger = Logger("PausedRetry")
    private val lock = Mutex()
    private val _pending = MutableStateFlow<PausedRetryInfo?>(null)

    private var graphId: String = ""
    private var tryNumber = 0
    private var exhausted = false
    private var timer: Job? = null
    private var attemptRunning = false

    /** The retry waiting, or null when none is (idle, or both tries spent and the sheet reads Failed). */
    val pending: StateFlow<PausedRetryInfo?> = _pending.asStateFlow()

    /** [pending] that also runs an overdue retry once whenever someone starts collecting it again (UX-52). */
    val observed: StateFlow<PausedRetryInfo?> = object : StateFlow<PausedRetryInfo?> by _pending {
        override suspend fun collect(collector: FlowCollector<PausedRetryInfo?>): Nothing {
            runOverdueOnce()
            _pending.collect(collector)
        }
    }

    /** A sync ended on an unknown owner. A pending try keeps its number; a spent budget restarts only for a user. */
    suspend fun onUnsure(graphId: String, source: PausedSource) = lock.withLock {
        when {
            tryNumber > 0 -> arm(tryNumber)
            exhausted && source == PausedSource.Background -> Unit
            else -> {
                this.graphId = graphId
                exhausted = false
                arm(1)
            }
        }
    }

    /** A sync ended for any other reason than coalescing: whatever was pending no longer applies. */
    suspend fun onSettled() = lock.withLock { reset() }

    /** Hurries the pending try. Never advances the try number, so repeated presses cannot reach Failed. */
    fun retryNow(onCoalesced: () -> Unit = {}) {
        scope.launch {
            var busy = false
            val number = lock.withLock {
                busy = attemptRunning
                if (tryNumber == 0 || busy) null else tryNumber.also { beginAttempt() }
            }
            if (number == null) {
                if (busy) onCoalesced()
                return@launch
            }
            finishAttempt(number, countsAsTry = false, onCoalesced)
        }
    }

    /** Runs the pending try now if its time has passed while nobody was looking (a suspended app's timer lags). */
    suspend fun runOverdueOnce() {
        val due = lock.withLock {
            val info = _pending.value
            if (info == null || attemptRunning || info.nextRetryAtMs > clock()) null else info.tryNumber.also { beginAttempt() }
        } ?: return
        logger.info("paused retry overdue try=$due, running once")
        scope.launch { finishAttempt(due, countsAsTry = true) {} }
    }

    private fun beginAttempt() {
        attemptRunning = true
        timer?.cancel()
        timer = null
    }

    private suspend fun finishAttempt(number: Int, countsAsTry: Boolean, onCoalesced: () -> Unit) {
        val result = try {
            attempt(graphId)
        } catch (e: CancellationException) {
            lock.withLock { attemptRunning = false }
            throw e
        } catch (e: Exception) {
            logger.warn("paused retry attempt failed: ${e.message}")
            PausedAttemptResult.Settled
        }
        lock.withLock {
            attemptRunning = false
            settle(result, number, countsAsTry, onCoalesced)
        }
    }

    private fun settle(result: PausedAttemptResult, number: Int, countsAsTry: Boolean, onCoalesced: () -> Unit) {
        when (result) {
            PausedAttemptResult.Settled -> reset()
            PausedAttemptResult.Coalesced -> {
                if (tryNumber > 0) arm(tryNumber)
                onCoalesced()
            }
            PausedAttemptResult.StillUnsure -> when {
                !countsAsTry -> arm(number)
                number < policy.maxTries -> arm(number + 1)
                else -> {
                    logger.info("paused retry exhausted after $number tries")
                    reset()
                    exhausted = true
                }
            }
        }
    }

    private fun arm(number: Int) {
        timer?.cancel()
        tryNumber = number
        val wait = policy.delayMs(number)
        _pending.value = PausedRetryInfo(clock() + wait, number)
        logger.info("paused retry try=$number of ${policy.maxTries} in ${wait}ms")
        val owner = graphId
        timer = scope.launch {
            delay(wait)
            awaitFree(owner)
            if (!claimTimedAttempt(number)) return@launch
            finishAttempt(number, countsAsTry = true) {}
        }
    }

    private suspend fun claimTimedAttempt(number: Int): Boolean = lock.withLock {
        if (attemptRunning || tryNumber != number) return@withLock false
        attemptRunning = true
        timer = null
        true
    }

    private fun reset() {
        timer?.cancel()
        timer = null
        tryNumber = 0
        exhausted = false
        _pending.value = null
    }
}
