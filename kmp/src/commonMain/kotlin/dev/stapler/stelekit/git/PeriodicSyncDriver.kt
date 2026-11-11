// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.jobs.schedule.ConstraintState
import dev.stapler.stelekit.jobs.schedule.JitterPolicy
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.security.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Clock

/** Why a scheduled tick did not run its body. */
sealed interface SkipReason {
    /** A manual sync or another tick is in flight. */
    data object Busy : SkipReason

    /** The network monitor reports offline; not a failure. */
    data object Offline : SkipReason

    /** [ScheduledSyncPolicy] withheld the full sync for [reason] (anything but busy and offline, which have their own cases). */
    data class Policy(val reason: ScheduledSkipReason) : SkipReason
}

/** Result of one scheduled tick. */
sealed interface TickOutcome {
    /** A full sync (or nothing needed merging) completed; the only outcome that moves "Last synced". */
    data object Success : TickOutcome

    /** One fetch ran and nothing was merged (fetch-only decision, SAF graph, or the merge was withheld); never "Last synced". */
    data object Fetched : TickOutcome
    data class Failure(val message: String, val error: DomainError? = null) : TickOutcome
    data class Skip(val reason: SkipReason) : TickOutcome
}

/** A fetch result as a tick outcome: offline is a skip (not a failure), anything else is a failure. */
internal fun Either<DomainError.GitError, FetchResult>.toTickOutcome(): TickOutcome = fold(
    { err ->
        if (err is DomainError.GitError.Offline) TickOutcome.Skip(SkipReason.Offline)
        else TickOutcome.Failure(err.message, err)
    },
    { TickOutcome.Fetched },
)

/**
 * What the Settings section and the sync badge read. All timestamps are wall-clock epoch millis
 * from the driver's injected clock, never monotonic elapsed time, so they survive process sleep.
 */
data class ScheduledSyncStatus(
    /** The interval loop is active (not stopped, not paused). */
    val running: Boolean = false,
    /** A tick body is executing right now. */
    val tickInFlight: Boolean = false,
    val lastAttemptAtMs: Long? = null,
    val lastSuccessAtMs: Long? = null,
    /** Last tick that only fetched; kept apart from [lastSuccessAtMs] so fetch-only graphs never read as synced. */
    val lastFetchedAtMs: Long? = null,
    /** `lastAttemptAtMs + interval`; null while stopped or paused. */
    val nextAtMs: Long? = null,
    /** Set after [PeriodicSyncDriver.PAUSE_AFTER_FAILURES] consecutive failures; cleared by a successful tick. */
    val paused: Boolean = false,
    val consecutiveFailures: Int = 0,
    val lastFailure: String? = null,
    /** Reason the most recent tick was skipped, cleared by the next tick that runs its body. */
    val lastSkip: SkipReason? = null,
)

/**
 * Owns the interval loop for scheduled sync: per-tick `Throwable` guard, busy skip, wall-clock
 * bookkeeping, one `SyncTick` log line per tick, and a pause after repeated failures. The tick body
 * is injected so the policy (what a tick does) can change without touching the loop.
 *
 * Runs on the caller's [scope]; the caller owns its lifetime and exception handler.
 *
 * @param isBusy true while a manual sync is in flight; the tick is skipped, never queued.
 * @param clock wall-clock epoch millis.
 * @param label graph id shown in the `SyncTick` log line.
 * @param decision what a tick does, for the log line (`fetch` until the full-sync cutover).
 * @param random source of the per-cycle jitter; only consulted when a jitter window is set.
 * @param recorder write-only hook called once after each tick that ran; a throwing recorder is logged and ignored.
 * @param constraintState the in-app constraint snapshot handed to [recorder].
 */
class PeriodicSyncDriver(
    private val tick: suspend () -> TickOutcome,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val isBusy: () -> Boolean = { false },
    private val label: String = "",
    private val decision: String = "fetch",
    private val launchContext: CoroutineContext = EmptyCoroutineContext,
    private val random: Random = Random.Default,
    private val recorder: TickRunRecorder = TickRunRecorder.None,
    private val constraintState: () -> ConstraintState = { ConstraintState(online = true, isMetered = null, charging = null) },
) {
    private val logger = Logger("PeriodicSyncDriver")
    private val tickLock = Mutex()
    private val _status = MutableStateFlow(ScheduledSyncStatus())
    val status: StateFlow<ScheduledSyncStatus> = _status.asStateFlow()

    @kotlin.concurrent.Volatile private var job: Job? = null
    @kotlin.concurrent.Volatile private var intervalMinutes: Int = 0
    @kotlin.concurrent.Volatile private var jitterMinutes: Int = 0

    /**
     * Cancels any running loop, then starts one ticking every [intervalMinutes]; with
     * [immediateFirstTick] one tick runs right away instead of after a full interval.
     * Each cycle waits between [intervalMinutes] and `intervalMinutes + jitterMinutes`; 0 is exactly the interval.
     * No-op for `intervalMinutes <= 0`. A fresh start clears a previous pause.
     */
    fun start(intervalMinutes: Int, immediateFirstTick: Boolean = false, jitterMinutes: Int = 0) {
        stop()
        if (intervalMinutes <= 0) return
        this.intervalMinutes = intervalMinutes
        this.jitterMinutes = maxOf(0, jitterMinutes)
        _status.update {
            it.copy(
                running = true,
                paused = false,
                consecutiveFailures = 0,
                nextAtMs = clock() + intervalMs(),
            )
        }
        job = scope.launch(launchContext) { runLoop(immediateFirstTick) }
    }

    fun stop() {
        job?.cancel()
        job = null
        intervalMinutes = 0
        jitterMinutes = 0
        _status.update { it.copy(running = false, nextAtMs = null) }
    }

    /**
     * Runs exactly one tick now, even while paused. A success clears the pause and restarts the
     * loop; a failure keeps it paused with the new cause. Does not guess whether the cause is fixed.
     */
    suspend fun resume() {
        val outcome = runTick()
        if (outcome is TickOutcome.Success && intervalMinutes > 0 && job == null) {
            job = scope.launch(launchContext) { runLoop(immediateFirstTick = false) }
            _status.update { it.copy(running = true) }
        }
    }

    private suspend fun runLoop(immediateFirstTick: Boolean) {
        if (immediateFirstTick) runTick()
        while (!_status.value.paused) {
            delay(intervalMs())
            runTick()
        }
        // Paused: the loop ends; resume() or a fresh start() brings it back.
        job = null
        _status.update { it.copy(running = false, nextAtMs = null) }
    }

    private suspend fun runTick(): TickOutcome {
        if (isBusy() || !tickLock.tryLock()) return skip(SkipReason.Busy)
        try {
            val attemptAt = clock()
            _status.update { it.copy(tickInFlight = true, lastAttemptAtMs = attemptAt, lastSkip = null) }
            val outcome = try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                TickOutcome.Failure(describe(t))
            }
            val safe = if (outcome is TickOutcome.Failure) outcome.copy(message = Redactor.redactGitMessage(outcome.message)) else outcome
            record(safe, attemptAt)
            recordRun(safe, attemptAt)
            return safe
        } finally {
            _status.update { it.copy(tickInFlight = false) }
            tickLock.unlock()
        }
    }

    private suspend fun recordRun(outcome: TickOutcome, startedAtMs: Long) {
        try {
            recorder.record(startedAtMs, outcome.toTaskRunResult(), constraintState())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("tick run record failed graph=$label", t)
        }
    }

    private fun skip(reason: SkipReason): TickOutcome {
        val outcome = TickOutcome.Skip(reason)
        _status.update { it.copy(lastSkip = reason) }
        logTick(outcome)
        return outcome
    }

    private fun record(outcome: TickOutcome, attemptAt: Long) {
        val next = _status.updateAndGet { s ->
            when (outcome) {
                is TickOutcome.Success -> s.copy(
                    lastSuccessAtMs = clock(),
                    consecutiveFailures = 0,
                    paused = false,
                    lastFailure = null,
                    nextAtMs = attemptAt + intervalMs(),
                )
                is TickOutcome.Fetched -> s.copy(
                    lastFetchedAtMs = clock(),
                    consecutiveFailures = 0,
                    paused = false,
                    lastFailure = null,
                    nextAtMs = attemptAt + intervalMs(),
                )
                is TickOutcome.Failure -> {
                    val failures = s.consecutiveFailures + 1
                    val paused = failures >= PAUSE_AFTER_FAILURES
                    s.copy(
                        consecutiveFailures = failures,
                        paused = paused,
                        lastFailure = outcome.message,
                        nextAtMs = if (paused) null else attemptAt + intervalMs(),
                    )
                }
                is TickOutcome.Skip -> s.copy(lastSkip = outcome.reason, nextAtMs = attemptAt + intervalMs())
            }
        }
        logTick(outcome)
        if (outcome is TickOutcome.Failure && next.paused && next.consecutiveFailures == PAUSE_AFTER_FAILURES) {
            logger.warn("sync.paused graph=$label after=$PAUSE_AFTER_FAILURES consecutive failures; last=${outcome.message}")
        }
    }

    private fun logTick(outcome: TickOutcome) {
        val line = "SyncTick graph=$label decision=$decision outcome=${outcome.logName()} " +
            "lastSuccess=${_status.value.lastSuccessAtMs ?: "never"}"
        when (outcome) {
            is TickOutcome.Failure -> logger.warn("$line error=${outcome.message}")
            else -> logger.info(line)
        }
    }

    private fun TickOutcome.logName(): String = when (this) {
        is TickOutcome.Success -> "success"
        is TickOutcome.Fetched -> "fetched"
        is TickOutcome.Failure -> "failure"
        is TickOutcome.Skip -> when (reason) {
            SkipReason.Busy -> "skip(busy)"
            SkipReason.Offline -> "skip(offline)"
            is SkipReason.Policy -> "skip(${reason.reason.name})"
        }
    }

    private fun describe(t: Throwable): String = Redactor.redactGitMessage("${t::class.simpleName}: ${t.message ?: "no message"}")

    private fun intervalMs() = JitterPolicy.nextDelayMs(intervalMinutes, jitterMinutes, random)

    companion object {
        /** Consecutive failed ticks before the loop pauses. */
        const val PAUSE_AFTER_FAILURES = 3
    }
}
