// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import dev.stapler.stelekit.jobs.JobTags
import dev.stapler.stelekit.jobs.workManagerOrNull
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Android adapter over `androidx.work` 2.9.1 (ADR-001, ADR-002, ADR-005).
 *
 * A kind bound by [workers] (the host binds `git-sync` to its existing worker, because a later `UPDATE`
 * with another class would silently re-point jobs, ADR-002 outcome 4) uses that class; every other kind runs as [TaskWorker]. Periodic work uses
 * `UPDATE` (keeps the work id; interval, flex, initial delay and input data are replaced in place).
 * Every method is guarded: a WorkManager failure is logged, never thrown (CLAUDE.md, Android Throwables).
 */
class WorkManagerTaskScheduler(
    private val context: Context,
    private val registry: TaskRegistry,
    private val store: TaskSettingsStore,
    private val ledger: AppliedConfigLedger,
    override val capabilities: SchedulerCapabilities = CAPABILITIES,
    private val workers: WorkerClassResolver = WorkerClassResolver.TaskWorkerOnly,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : BackgroundTaskScheduler {
    private val logger = Logger("WorkManagerTaskScheduler")
    private val requests = TaskRequestFactory(registry, workers, capabilities)

    override suspend fun apply(key: TaskKey, config: TaskConfig) = guarded("apply ${key.workName()}") {
        if (!config.enabled) {
            ledger.clear(key)
            pausedKeys.remove(key)
            cancelUnique(key)
            return@guarded
        }
        ledger.recordApplied(key, config)
        if (ledger.pausedSnapshot(key) != null) {
            ledger.updatePausedConfig(key, config) // a change during a pause is restored by `resume`, nothing is enqueued
            return@guarded
        }
        enqueuePeriodic(key, config, ExistingPeriodicWorkPolicy.UPDATE)
    }

    override suspend fun runOnce(key: TaskKey, constraints: TaskConstraints, onConflict: OneShotConflict) =
        guarded("runOnce ${key.workName()}") {
            val wm = workManagerOrNull(context) ?: return@guarded
            val spec = registry.specFor(key.kind)
            val builder = OneTimeWorkRequest.Builder(requests.workerClassFor(key))
                .setConstraints(requests.constraintsOf(constraints))
                .setInputData(requests.inputData(key, constraints.network, interval = null))
            requests.schedTags(key, spec).forEach(builder::addTag)
            builder.addTag(JobTags.ANY)
            builder.addTag(JobTags.title(spec?.title ?: key.kind.id))
            if ((spec?.oneShotRetryCap ?: 0) > 0) builder.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, ONE_SHOT_BACKOFF_SECONDS, TimeUnit.SECONDS)
            val policy = if (onConflict == OneShotConflict.Replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            wm.enqueueUniqueWork(key.workName(), policy, builder.build()).result.get()
        }

    override suspend fun cancel(key: TaskKey) = guarded("cancel ${key.workName()}") {
        ledger.clear(key)
        pausedKeys.remove(key)
        cancelUnique(key)
    }

    override suspend fun cancelAll(scope: TaskScope) = guarded("cancelAll ${scope.value}") {
        for (key in keysInScope(scope)) {
            ledger.clear(key)
            pausedKeys.remove(key)
            cancelUnique(key)
        }
        workManagerOrNull(context)?.cancelAllWorkByTag(SchedTags.scope(scope))
    }

    /** Snapshots live periodic work into the ledger, then ALWAYS cancels it (a skipped cancel would block a clone). */
    override suspend fun pause(scope: TaskScope) = guarded("pause ${scope.value}") {
        for (key in keysInScope(scope)) {
            val live = try {
                livePeriodic(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("pause: cannot read live work for ${key.workName()}", e)
                null
            }
            if (live != null) {
                pausedKeys += key
                ledger.snapshotPaused(key, configOf(key, live), remainingDelayMs(live))
            }
            cancelUnique(key)
        }
    }

    override suspend fun resume(scope: TaskScope) = guarded("resume ${scope.value}") {
        val candidates = (pausedKeys.filter { it.scope == scope } + defaultKeys(scope)).toSet()
        for (key in candidates) {
            val snapshot = ledger.pausedSnapshot(key)
            if (snapshot == null && key !in pausedKeys) continue
            pausedKeys.remove(key)
            if (store.isExplicitlyDisabled(key)) {
                ledger.clear(key)
                continue
            }
            val config = snapshot?.config ?: ledger.lastApplied(key) ?: store.read(key)
            ledger.clearPausedSnapshot(key)
            if (config.enabled) {
                ledger.recordApplied(key, config)
                // CANCEL_AND_REENQUEUE: a clone that ran under the shared name leaves finished one-time work UPDATE cannot convert.
                enqueuePeriodic(key, config, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, snapshot?.remainingInitialDelayMs)
            }
        }
    }

    override suspend fun describe(key: TaskKey): TaskScheduleInfo? {
        val live = try {
            livePeriodic(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("describe: cannot read ${key.workName()}", e)
            null
        } ?: return null
        val period = live.periodicityInfo ?: return null
        val interval = TimeUnit.MILLISECONDS.toMinutes(period.repeatIntervalMillis).toInt()
        // WorkManager reports flex == interval when none was requested.
        val flex = TimeUnit.MILLISECONDS.toMinutes(period.flexIntervalMillis).toInt().takeIf { it < interval } ?: 0
        val clamped = ledger.lastApplied(key)?.schedule?.clamp(capabilities)?.clamped ?: false
        return TaskScheduleInfo(
            effectiveIntervalMinutes = interval,
            clamped = clamped,
            nextRunAtMs = live.nextScheduleTimeMillis.takeIf { it != Long.MAX_VALUE },
            // ADR-002 outcome 7: analytic window [interval - flex, interval + flex]; unverified on a real OS scheduler.
            minGapMinutes = interval - flex,
            maxGapMinutes = interval + flex,
            // ADR-002 outcome 3: UPDATE moves the pending run immediately.
            appliesAfterNextRun = false,
        )
    }

    /** Inserts live periodic work the way a pre-upgrade job exists: no ledger, store or scope tag. For tests. */
    internal fun seedExternalPeriodic(key: TaskKey, config: TaskConfig) {
        val wm = workManagerOrNull(context) ?: return
        wm.enqueueUniquePeriodicWork(key.workName(), ExistingPeriodicWorkPolicy.UPDATE, requests.buildPeriodic(key, config, tagged = false)).result.get()
    }

    /** The pending (unfinished) one-time request for [key]: its id and the network type it requires. For tests. */
    internal fun pendingOneShot(key: TaskKey): Pair<String, NetworkPolicy>? {
        val wm = workManagerOrNull(context) ?: return null
        val info = wm.getWorkInfosForUniqueWork(key.workName()).get()
            .firstOrNull { !it.state.isFinished && it.periodicityInfo == null } ?: return null
        val network = if (info.constraints.requiredNetworkType == NetworkType.UNMETERED) NetworkPolicy.Unmetered else NetworkPolicy.Any
        return info.id.toString() to network
    }

    private suspend fun enqueuePeriodic(key: TaskKey, config: TaskConfig, policy: ExistingPeriodicWorkPolicy, initialDelayMs: Long? = null) {
        val wm = workManagerOrNull(context) ?: return
        if (policy == ExistingPeriodicWorkPolicy.UPDATE) {
            val holder = blocking { wm.getWorkInfosForUniqueWork(key.workName()).get() }.firstOrNull { !it.state.isFinished }
            if (holder != null && holder.periodicityInfo == null) {
                logger.info("apply skipped: ${key.workName()} is held by running or queued non-periodic work")
                return
            }
            val holderScope = holder?.let { SchedTags.scopeOf(it.tags) }
            if (holderScope != null && holderScope != key.scope) {
                logger.warn("apply refused: ${key.workName()} is held by scope '${holderScope.value}' (hash collision)")
                return
            }
        }
        val request = requests.buildPeriodic(key, config, tagged = true, initialDelayMs = initialDelayMs)
        blocking { wm.enqueueUniquePeriodicWork(key.workName(), policy, request).result.get() }
    }

    private suspend fun livePeriodic(key: TaskKey): WorkInfo? {
        val wm = workManagerOrNull(context) ?: return null
        return blocking { wm.getWorkInfosForUniqueWork(key.workName()).get() }
            .firstOrNull { !it.state.isFinished && it.periodicityInfo != null }
    }

    /**
     * The initial delay that makes a re-enqueued request run when the live one would have. WorkManager reports the
     * next run as `start + initialDelay + (interval - flex)` (read back in the pause/resume test), so the delay is
     * that time minus now minus `(interval - flex)`. Falls back to the request's original delay when the next run
     * is unreadable (running work reports it as unset).
     */
    private fun remainingDelayMs(live: WorkInfo): Long {
        val next = live.nextScheduleTimeMillis
        val period = live.periodicityInfo ?: return live.initialDelayMillis
        if (next == Long.MAX_VALUE) return live.initialDelayMillis
        val windowStartOffset = period.repeatIntervalMillis - period.flexIntervalMillis
        return (next - nowMs() - windowStartOffset).coerceAtLeast(0L)
    }

    /** The config of live work: the last applied one when it still matches, else derived from the request. */
    private fun configOf(key: TaskKey, live: WorkInfo): TaskConfig {
        val period = checkNotNull(live.periodicityInfo)
        val interval = TimeUnit.MILLISECONDS.toMinutes(period.repeatIntervalMillis).toInt()
        val applied = ledger.lastApplied(key)
        if (applied != null && applied.schedule.clamp(capabilities).intervalMinutes == interval) return applied.copy(enabled = true)
        val flex = TimeUnit.MILLISECONDS.toMinutes(period.flexIntervalMillis).toInt().takeIf { it < interval } ?: 0
        val c = live.constraints
        return TaskConfig(
            enabled = true,
            schedule = Schedule.coerce(interval, flex),
            constraints = TaskConstraints(
                network = if (c.requiredNetworkType == NetworkType.UNMETERED) NetworkPolicy.Unmetered else NetworkPolicy.Any,
                requiresCharging = c.requiresCharging(),
                batteryNotLow = c.requiresBatteryNotLow(),
            ),
        )
    }

    private suspend fun keysInScope(scope: TaskScope): Set<TaskKey> {
        val tagged = blocking { workManagerOrNull(context)?.getWorkInfosByTag(SchedTags.scope(scope))?.get().orEmpty() }
            .mapNotNull { info -> SchedTags.kindIdOf(info.tags)?.let { TaskKey(it, scope) } }
        return (tagged + defaultKeys(scope) + pausedKeys.filter { it.scope == scope }).toSet()
    }

    /** Untagged pre-upgrade git work and every registered kind: keys that may exist without a tag. */
    private fun defaultKeys(scope: TaskScope): List<TaskKey> =
        (registry.kinds() + GIT_SYNC).map { TaskKey(it, scope) }

    private fun cancelUnique(key: TaskKey) {
        workManagerOrNull(context)?.cancelUniqueWork(key.workName())
    }

    private suspend fun <T> blocking(block: () -> T): T = withContext(PlatformDispatcher.IO) { block() }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("$what failed", e)
        }
    }

    companion object {
        /** WorkManager's floors: 15 min interval, 5 min flex; unmetered, charging and jitter (flex) honoured; runs when closed. */
        val CAPABILITIES = SchedulerCapabilities(
            minIntervalMinutes = JitterPolicy.DEFAULT_MIN_INTERVAL_MINUTES,
            minFlexMinutes = JitterPolicy.DEFAULT_MIN_FLEX_MINUTES,
            supportsUnmetered = true,
            supportsCharging = true,
            supportsJitter = true,
            runsWhenClosed = true,
            schedulesWork = true,
        )

        private val GIT_SYNC = TaskKind("git-sync")
        private const val ONE_SHOT_BACKOFF_SECONDS = 30L

        // Process-static like the ledger's snapshot copy: pause and resume may run on different adapter instances.
        private val pausedKeys: MutableSet<TaskKey> = ConcurrentHashMap.newKeySet()

        /** Test hook, paired with [AppliedConfigLedger.clearProcessStatic]. */
        fun clearProcessStatic() = pausedKeys.clear()
    }
}
