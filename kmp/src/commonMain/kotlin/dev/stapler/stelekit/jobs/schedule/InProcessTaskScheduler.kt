// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.jobs.JobTracker
import dev.stapler.stelekit.jobs.NoJobTracker
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Live view of one scheduled key for status lines: when it next fires and what the last tick did. */
data class TaskScheduleStatus(val nextRunAtMs: Long?, val lastRun: TaskRunRecord?)

/**
 * Coroutine-loop adapter for Desktop: runs periodic work while the app is open under the same
 * settings as the platform adapters. Owns its scope (never a caller's) and a
 * [CoroutineExceptionHandler]; [stop] cancels it. [dispatcher] is the only injection point for
 * virtual time (tests pass a `StandardTestDispatcher`).
 *
 * The periodic network gate uses the configured policy even though [capabilities] says unmetered is
 * unsupported, so `Unmetered` on a host with unknown metering fails closed; [describe] reports the
 * downgrade. One-shots use the downgraded policy (contract suite).
 */
class InProcessTaskScheduler(
    private val registry: TaskRegistry,
    private val store: TaskSettingsStore,
    private val ledger: AppliedConfigLedger,
    private val runLog: TaskRunLog,
    private val jobTracker: JobTracker = NoJobTracker,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
    private val constraintState: () -> ConstraintState = { DESKTOP_CONSTRAINT_STATE },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BackgroundTaskScheduler {
    private val logger = Logger("InProcessTaskScheduler")
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, t ->
            logger.error("uncaught throwable in in-process scheduler scope", t)
        },
    )

    override val capabilities = SchedulerCapabilities(
        minIntervalMinutes = 1,
        minFlexMinutes = 0,
        supportsUnmetered = false,
        supportsCharging = false,
        supportsJitter = true,
        runsWhenClosed = false,
        schedulesWork = true,
    )

    private class Loop(val config: TaskConfig, val effective: EffectiveSchedule, val tickLock: Mutex = Mutex()) {
        var job: Job? = null
    }

    private class PendingOneShot(val requestId: String, val effective: TaskConstraints, val downgradeReason: String?) {
        var job: Job? = null
    }

    private val stateLock = Mutex()
    private val drawLock = Mutex()
    private val loops = LinkedHashMap<TaskKey, Loop>()
    private val oneShots = LinkedHashMap<TaskKey, PendingOneShot>()
    private val paused = LinkedHashSet<TaskKey>()
    private var nextRequestId = 0

    private val _status = MutableStateFlow<Map<TaskKey, TaskScheduleStatus>>(emptyMap())
    val status: StateFlow<Map<TaskKey, TaskScheduleStatus>> = _status.asStateFlow()

    override suspend fun apply(key: TaskKey, config: TaskConfig) = stateLock.withLock {
        TaskApplyLog.log(key, config, if (config.enabled) config.schedule.clamp(capabilities, JitterSeed.of(key)) else null)
        if (!config.enabled) {
            stopLoop(key)
            ledger.clear(key)
            paused.remove(key)
            return@withLock
        }
        ledger.recordApplied(key, config)
        if (key in paused) {
            ledger.snapshotPaused(key, config) // a change during a pause updates the snapshot only
            return@withLock
        }
        startLoop(key, config)
    }

    /** Starts live periodic work with no ledger record, as a pre-upgrade job would exist (contract hook). */
    internal suspend fun seedExternalPeriodic(key: TaskKey, config: TaskConfig) = stateLock.withLock {
        startLoop(key, config)
    }

    override suspend fun runOnce(key: TaskKey, constraints: TaskConstraints, onConflict: OneShotConflict) = stateLock.withLock {
        if (oneShots.containsKey(key) && onConflict == OneShotConflict.Keep) return@withLock
        oneShots.remove(key)?.job?.cancel()
        val downgraded = constraints.network == NetworkPolicy.Unmetered && !capabilities.supportsUnmetered
        val pending = PendingOneShot(
            requestId = "oneshot-${++nextRequestId}",
            effective = if (downgraded) constraints.copy(network = NetworkPolicy.Any) else constraints,
            downgradeReason = if (downgraded) UNMETERED_UNSUPPORTED else null,
        )
        oneShots[key] = pending
        pending.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                execute(key, scheduledAtMs = null, network = pending.effective.network)
            } finally {
                withContext(NonCancellable) {
                    stateLock.withLock { if (oneShots[key] === pending) oneShots.remove(key) }
                }
            }
        }.also { it.start() }
        Unit
    }

    override suspend fun cancel(key: TaskKey) = stateLock.withLock { removeAll(key) }

    override suspend fun cancelAll(scope: TaskScope) = stateLock.withLock {
        (loops.keys + oneShots.keys + paused).filter { it.scope == scope }.forEach { removeAll(it) }
    }

    override suspend fun pause(scope: TaskScope) = stateLock.withLock {
        for (key in loops.keys.filter { it.scope == scope }) {
            val live = loops.getValue(key)
            ledger.snapshotPaused(key, live.config.copy(enabled = true)) // never throws; the stop below always runs
            stopLoop(key)
            paused += key
        }
    }

    override suspend fun resume(scope: TaskScope) = stateLock.withLock {
        for (key in paused.filter { it.scope == scope }) {
            paused.remove(key)
            if (store.isExplicitlyDisabled(key)) {
                ledger.clear(key)
                continue
            }
            val config = ledger.pausedSnapshot(key)?.config ?: ledger.lastApplied(key) ?: store.read(key)
            if (config.enabled) {
                startLoop(key, config)
                ledger.recordApplied(key, config)
            }
            ledger.clearPausedSnapshot(key)
        }
    }

    override suspend fun describe(key: TaskKey): TaskScheduleInfo? = stateLock.withLock {
        loops[key]?.let { loop ->
            val eff = loop.effective
            return@withLock TaskScheduleInfo(
                effectiveIntervalMinutes = eff.intervalMinutes,
                clamped = eff.clamped,
                nextRunAtMs = _status.value[key]?.nextRunAtMs,
                minGapMinutes = eff.intervalMinutes,
                maxGapMinutes = eff.intervalMinutes + eff.jitterMinutes,
                appliesAfterNextRun = false, // a changed config restarts the loop
                downgradeReason = downgradeReason(loop.config.constraints),
            )
        }
        val reason = oneShots[key]?.downgradeReason ?: return@withLock null
        TaskScheduleInfo(0, false, null, 0, 0, appliesAfterNextRun = false, downgradeReason = reason)
    }

    /** The pending one-shot's request id and effective network, or null (contract hook). */
    internal suspend fun pendingOneShot(key: TaskKey): Pair<String, NetworkPolicy>? = stateLock.withLock {
        oneShots[key]?.let { it.requestId to it.effective.network }
    }

    /** Cancels every loop and in-flight run; the scheduler cannot be used afterwards. */
    fun stop() {
        scope.cancel()
    }

    // Callers hold stateLock.
    private suspend fun startLoop(key: TaskKey, config: TaskConfig) {
        if (loops[key]?.config == config) return // idempotent: identical config keeps the running loop
        loops.remove(key)?.job?.cancel()
        val effective = config.schedule.clamp(capabilities, JitterSeed.of(key))
        val loop = Loop(config, effective)
        loops[key] = loop
        val firstWait = nextWaitMs(effective)
        setNextRun(key, clock() + firstWait)
        loop.job = scope.launch {
            var wait = firstWait
            while (true) {
                delay(wait)
                val scheduledAt = clock()
                tick(key, loop, scheduledAt)
                wait = nextWaitMs(effective)
                setNextRun(key, clock() + wait)
            }
        }
    }

    private fun stopLoop(key: TaskKey) {
        loops.remove(key)?.job?.cancel()
        _status.update { it - key }
    }

    private fun removeAll(key: TaskKey) {
        stopLoop(key)
        oneShots.remove(key)?.job?.cancel()
        paused.remove(key)
        ledger.clear(key)
    }

    private suspend fun nextWaitMs(effective: EffectiveSchedule): Long =
        drawLock.withLock { JitterPolicy.nextDelayMs(effective.intervalMinutes, effective.jitterMinutes, random) }

    private fun setNextRun(key: TaskKey, nextRunAtMs: Long) {
        _status.update { map ->
            if (map[key] == null && key !in loops) map
            else map + (key to TaskScheduleStatus(nextRunAtMs, map[key]?.lastRun))
        }
    }

    private suspend fun tick(key: TaskKey, loop: Loop, scheduledAtMs: Long) {
        if (!loop.tickLock.tryLock()) {
            record(key, scheduledAtMs, clock(), TaskRunResult.Skipped("busy"), constraintState())
            return
        }
        try {
            execute(key, scheduledAtMs, loop.config.constraints.network)
        } finally {
            loop.tickLock.unlock()
        }
    }

    /** Gate, run and log one invocation. A gate skip never reaches the task body. Rethrows only cancellation. */
    private suspend fun execute(key: TaskKey, scheduledAtMs: Long?, network: NetworkPolicy) {
        val startedAt = clock()
        val state = constraintState()
        val gate = NetworkGate.evaluate(network, state.online, state.isMetered)
        if (gate is NetworkGateResult.Skip) {
            record(key, scheduledAtMs, startedAt, gate.toTaskRunResult(), state)
            return
        }
        val task = registry.taskFor(key.kind)
        if (task == null) {
            record(key, scheduledAtMs, startedAt, TaskRunResult.Failed("no task registered for ${key.kind.id}"), state)
            return
        }
        val spec = registry.specFor(key.kind)
        val handle = spec?.jobKind?.let { kind -> guarded("job tracker begin") { jobTracker.begin(kind, spec.title) } }
        val result = try {
            task.run(key, TaskRunContext(scheduledAtMs, startedAt, appOpen = true, constraintState = state))
        } catch (e: CancellationException) {
            guarded("job tracker cancelled") { handle?.cancelled() }
            throw e
        } catch (t: Throwable) { // OutOfMemoryError included: it must not kill the loop
            TaskRunResult.Failed(redactedFailureMessage(t))
        }
        when (result) {
            is TaskRunResult.Failed -> guarded("job tracker fail") { handle?.fail(result.message) }
            else -> guarded("job tracker succeed") { handle?.succeed() }
        }
        record(key, scheduledAtMs, startedAt, result, state)
    }

    private suspend fun record(key: TaskKey, scheduledAtMs: Long?, startedAtMs: Long, result: TaskRunResult, state: ConstraintState) {
        val record = TaskRunRecord(key, RunSource.Background, scheduledAtMs, startedAtMs, result, state)
        guarded("run log append") { runLog.append(record) }
        _status.update { map -> if (key in map) map + (key to map.getValue(key).copy(lastRun = record)) else map }
    }

    /** A tracker or log failure must never end the loop; only cancellation propagates. */
    private inline fun <T> guarded(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logger.warn("$what failed", t)
        null
    }

    private fun downgradeReason(constraints: TaskConstraints): String? = when {
        constraints.network == NetworkPolicy.Unmetered && !capabilities.supportsUnmetered -> UNMETERED_UNSUPPORTED
        constraints.requiresCharging && !capabilities.supportsCharging -> "charging constraint not supported by this scheduler"
        else -> null
    }

    private companion object {
        const val UNMETERED_UNSUPPORTED = "unmetered not supported by this scheduler"

        /** Desktop knows it is running (so online) but has no metering signal. */
        val DESKTOP_CONSTRAINT_STATE = ConstraintState(online = true, isMetered = null, charging = null)
    }
}
