// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import arrow.core.right
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.jobs.schedule.AppliedConfigLedger
import dev.stapler.stelekit.jobs.schedule.AndroidNetworkSnapshotProvider
import dev.stapler.stelekit.jobs.schedule.ConstraintState
import dev.stapler.stelekit.jobs.schedule.NetworkGate
import dev.stapler.stelekit.jobs.schedule.NetworkGateResult
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.NetworkSkipReason
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshot
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshotProvider
import dev.stapler.stelekit.jobs.schedule.RestrictionReason
import dev.stapler.stelekit.jobs.schedule.RunSource
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.SettingsTaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRunContext
import dev.stapler.stelekit.jobs.schedule.TaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskRunRecord
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.jobs.schedule.TaskWorkKeys
import dev.stapler.stelekit.jobs.schedule.TaskWorkerRuntime
import dev.stapler.stelekit.jobs.schedule.WorkManagerTaskScheduler
import dev.stapler.stelekit.jobs.schedule.recordRun
import dev.stapler.stelekit.jobs.schedule.toTaskRunResult
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.SteleKitContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Legacy Android entry point for [BackgroundSyncScheduler], now a shim over [WorkManagerTaskScheduler]
 * (Story 3.1.2). Kept so existing hosts and the pinned tests compile and behave as before; the work
 * keeps the unique name `stelekit_git_sync_<graphId>` and the [GitSyncWorker] class, so the clone
 * worker's pause/resume protocol and in-flight pre-upgrade jobs are unaffected.
 *
 * Android enforces a minimum 15-minute repeat interval for periodic work. For sub-15-minute polling
 * while foregrounded, [GitSyncService] manages its own coroutine timer.
 */
class WorkManagerSyncScheduler(
    private val context: Context,
    private val graphId: String,
) : BackgroundSyncScheduler {

    private val key get() = TaskKey(GitSyncTask.KIND, TaskScope(graphId))

    /**
     * Persists intent through the port: jitter and constraints come from [TaskSettingsStore] (the spec
     * defaults when nothing is stored), the interval is the caller's. Blocks briefly on the adapter, which
     * guards every WorkManager failure itself, so this never throws.
     */
    @Deprecated("Use BackgroundTaskScheduler.apply(TaskKey(git-sync, graphId), config); kept for one release.")
    override fun schedule(intervalMinutes: Int) {
        runBlocking {
            val adapter = legacyAdapter(context)
            val stored = legacyStore(context).read(key)
            adapter.apply(
                key,
                TaskConfig(enabled = true, schedule = Schedule.coerce(intervalMinutes, stored.schedule.jitterMinutes), constraints = stored.constraints),
            )
        }
    }

    @Deprecated("Use BackgroundTaskScheduler.cancel(TaskKey(git-sync, graphId)); kept for one release.")
    override fun cancel() {
        runBlocking { legacyAdapter(context).cancel(key) }
    }

    companion object {
        /** Android's floor for periodic work. */
        const val MIN_PERIODIC_INTERVAL_MINUTES = 15L

        /** Not `private` (Story 5.1.2): [AndroidGitCloneWorkerLauncher] shares this name so
         * WorkManager's own uniqueness guarantee serializes it against the periodic job. */
        internal fun workNameFor(graphId: String) = TaskKey(GitSyncTask.KIND, TaskScope(graphId)).workName()

        /**
         * Pauses [graphId]'s scheduled periodic sync job ahead of a relocate/link's copy step
         * (Story 3.2.1), so a concurrent WorkManager fetch never races the foreground copy of
         * `.git`. The adapter snapshots the live work first, then cancels it; [resumeFor] restores it.
         *
         * A `Context`-scoped companion function rather than an instance method: the caller
         * ([AndroidGraphMoveQuiesceStrategy]) quiesces an arbitrary [graphId] from a
         * [dev.stapler.stelekit.model.StorageMoveOperation], not necessarily one it already holds
         * a per-graph [WorkManagerSyncScheduler] instance for.
         */
        fun pauseFor(context: Context, graphId: String) {
            runBlocking { legacyAdapter(context).pause(TaskScope(graphId)) }
        }

        /**
         * Re-enqueues what [pauseFor] paused, with the interval, flex and remaining delay the live work had
         * (falling back to the last applied config, then the store). A graph the user disabled meanwhile
         * stays off.
         */
        fun resumeFor(context: Context, graphId: String) {
            runBlocking { legacyAdapter(context).resume(TaskScope(graphId)) }
        }

        /** The port adapter built from a [Context] and process statics alone (no UI state); also what a cold process can build. */
        internal fun legacyAdapter(context: Context): WorkManagerTaskScheduler {
            SteleKitContext.init(context)
            val settings = PlatformSettings()
            val registry = TaskWorkerRuntime.registry
            return WorkManagerTaskScheduler(
                context = context,
                registry = registry,
                store = TaskSettingsStore(settings, TaskWorkerRuntime.legacySeed, registry),
                ledger = AppliedConfigLedger(settings),
                workers = GitSyncWorkerBinding.resolver,
            )
        }

        private fun legacyStore(context: Context): TaskSettingsStore {
            SteleKitContext.init(context)
            val registry = TaskWorkerRuntime.registry
            return TaskSettingsStore(PlatformSettings(), TaskWorkerRuntime.legacySeed, registry)
        }
    }
}

/**
 * WorkManager worker for scheduled git sync: a THIN WRAPPER (Story 3.1.2, ADR-001). With the app alive it
 * runs the policy-gated [ScheduledSyncRunner] tick on the registered service; with no live service it
 * hands the single fetch to [ColdSyncRunner], which cannot touch the working tree. It does no registry
 * lookup, no `enabled` gate and no store read, so a stale or pre-upgrade job still runs; disable goes
 * through `apply`/`cancel`. It evaluates the shared `NetworkGate` (policy from [KEY_NETWORK_POLICY],
 * absent = `Any`) and appends one run-log record through the shared [recordRun].
 *
 * Order: watchdog -> gate -> clone-yield -> body -> notify. Failure is never `retry()` (ADR-002: the
 * transport retry already ran inside the git layer).
 */
class GitSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_GRAPH_ID = "graph_id"
        const val KEY_INTERVAL_MINUTES = "interval_minutes"
        const val KEY_NETWORK_POLICY = TaskWorkKeys.KEY_NETWORK_POLICY

        /** Test seams; null in production. */
        @Volatile internal var snapshotProviderOverride: ((Context) -> NetworkSnapshotProvider)? = null
        @Volatile internal var runLogOverride: ((Context) -> TaskRunLog)? = null
        @Volatile internal var coldPathOverride: ColdPathRunner? = null
    }

    override suspend fun doWork(): Result {
        val graphId = inputData.getString(KEY_GRAPH_ID) ?: return Result.failure()
        val startedAtMs = System.currentTimeMillis()
        var constraintState = ConstraintState(online = false, isMetered = null, charging = null)
        var recorded: TaskRunResult? = null
        try {
            SteleKitContext.init(applicationContext)
            val key = TaskKey(GitSyncTask.KIND, TaskScope(graphId))
            val policy = TaskWorkKeys.decode(inputData.getString(KEY_NETWORK_POLICY))

            // A side-effecting observation only: never allowed to affect this worker's own Result.
            GitSyncAdapterHooks.runWatchdog(applicationContext, graphId)

            val snapshot = readSnapshot()
            if (snapshot != null) constraintState = ConstraintState(snapshot.online, snapshot.isMetered, charging = null)
            val gate = when {
                snapshot != null -> NetworkGate.evaluate(policy, snapshot.online, snapshot.isMetered)
                // WorkManager's CONNECTED constraint already held, so an unreadable provider proceeds for Any.
                policy == NetworkPolicy.Any -> NetworkGateResult.Proceed
                else -> NetworkGateResult.Skip(NetworkSkipReason.UnknownNetwork)
            }
            if (gate is NetworkGateResult.Skip) {
                recorded = gate.toTaskRunResult()
                return Result.success()
            }

            if (GitSyncAdapterHooks.shouldYieldToCloneWorker(applicationContext, graphId)) {
                recorded = TaskRunResult.Skipped(GitSyncAdapterHooks.YIELD_REASON)
                return Result.success()
            }

            val liveService = GitSyncServiceRegistry.getService(graphId)
            val ctx = TaskRunContext(scheduledAtMs = null, startedAtMs = startedAtMs, appOpen = liveService != null, constraintState = constraintState)
            val result = GitSyncTask(cold = coldPathOverride ?: AndroidColdPathRunner(applicationContext)).run(key, ctx)
            recorded = result
            GitSyncAdapterHooks.postRun(applicationContext, key, inputData, result)
            return if (result is TaskRunResult.Failed) Result.failure() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger("GitSyncWorker").error("git-sync worker failed", e)
            // ADR-002: a second WorkManager-level retry would stack on the git layer's own retry.
            recorded = TaskRunResult.Failed(e.message ?: e::class.simpleName ?: "error", retryable = false)
            return Result.failure()
        } finally {
            recorded?.let { final -> recordOnce(graphId, startedAtMs, final, constraintState) }
        }
    }

    private suspend fun readSnapshot(): NetworkSnapshot? = try {
        (snapshotProviderOverride?.invoke(applicationContext) ?: AndroidNetworkSnapshotProvider(applicationContext)).snapshot()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Logger("GitSyncWorker").warn("network snapshot unavailable", e)
        null
    }

    /** The log write can never change the worker's Result: building the log or appending may fail. */
    private suspend fun recordOnce(graphId: String, startedAtMs: Long, result: TaskRunResult, state: ConstraintState) {
        val runLog = try {
            runLogOverride?.invoke(applicationContext) ?: SettingsTaskRunLog(PlatformSettings())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger("GitSyncWorker").warn("run log unavailable for $graphId", e)
            return
        }
        recordRun(
            runLog,
            TaskRunRecord(TaskKey(GitSyncTask.KIND, TaskScope(graphId)), RunSource.Background, scheduledAtMs = null, startedAtMs = startedAtMs, result = result, constraintState = state),
        )
    }
}

/** The DB-backed config read for a graph that may not be open in this process (cold worker, legacy seed). Opens and closes one driver. */
internal fun loadGitConfigFromDb(graphId: String): GitConfig? {
    val factory = DriverFactory()
    val driver = factory.createDriver(factory.getDatabaseUrl(graphId))
    try {
        val db = dev.stapler.stelekit.db.SteleDatabase(driver)
        return db.steleDatabaseQueries.selectGitConfig(graphId).executeAsOneOrNull()?.toGitConfig()
    } finally {
        driver.close()
    }
}

/**
 * The slow path's decision logic, split from [GitSyncWorker.doWork]'s DB/driver setup so it is
 * testable without a real SQLite driver (Robolectric's bundled SQLite lacks the `fts5` module the
 * app schema needs). fetch() reports failure via `Either.Left`, not by throwing, so the result
 * must be inspected (Story 1.2.3, ADR-002: failure, never retry — the retry loop already ran).
 */
internal suspend fun runSlowPathFetch(
    loadConfig: suspend () -> GitConfig?,
    gitRepository: GitRepository,
    staleness: SyncStalenessStore = SyncStalenessStore(InMemorySettings()),
    clock: () -> Long = { System.currentTimeMillis() },
    locks: NamedLocks = ColdSyncLocks,
): ListenableWorker.Result = when (runColdFetch(loadConfig, gitRepository, staleness, clock, locks)) {
    is ColdSyncOutcome.Error -> ListenableWorker.Result.failure()
    is ColdSyncOutcome.Fetched, ColdSyncOutcome.NoConfig -> ListenableWorker.Result.success()
}

/** [runSlowPathFetch]'s decision as a typed outcome, shared with [AndroidColdPathRunner]. */
internal suspend fun runColdFetch(
    loadConfig: suspend () -> GitConfig?,
    gitRepository: GitRepository,
    staleness: SyncStalenessStore,
    clock: () -> Long,
    locks: NamedLocks = ColdSyncLocks,
): ColdSyncOutcome {
    val config = loadConfig() ?: return ColdSyncOutcome.NoConfig
    val configs = object : GitConfigRepository {
        override suspend fun getConfig(graphId: String) = config.right()
        override suspend fun saveConfig(config: GitConfig) = Unit.right()
        override suspend fun deleteConfig(graphId: String) = Unit.right()
        override fun observeConfig(graphId: String) = kotlinx.coroutines.flow.flowOf(config.right())
    }
    return ColdSyncRunner(gitRepository, configs, staleness, locks, clock).run(config.graphId)
}

/** One lock registry per process, shared by every closed-app fetch. */
private val ColdSyncLocks = NamedLocks()

private class InMemorySettings : dev.stapler.stelekit.platform.Settings {
    private val values = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean) = values[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = values[key] ?: defaultValue
    override fun putString(key: String, value: String) { values[key] = value }
    override fun containsKey(key: String) = values.containsKey(key)
}

private fun dev.stapler.stelekit.db.Git_config.toGitConfig() =
    dev.stapler.stelekit.git.model.GitConfig(
        graphId = graph_id,
        repoRoot = RepoRoot(repo_root),
        wikiSubdir = wiki_subdir.ifEmpty { null },
        remoteName = remote_name,
        remoteBranch = remote_branch,
        authType = runCatching {
            dev.stapler.stelekit.git.model.GitAuthType.valueOf(auth_type)
        }.getOrDefault(dev.stapler.stelekit.git.model.GitAuthType.NONE),
        sshKeyPath = ssh_key_path,
        sshKeyPassphraseKey = ssh_key_passphrase_key,
        httpsTokenKey = https_token_key,
        oauthTokenKey = oauth_token_key,
        pollIntervalMinutes = poll_interval_minutes.toInt(),
        autoCommit = auto_commit != 0L,
        commitMessageTemplate = commit_message_template,
        cloneDepthState = dev.stapler.stelekit.git.model.CloneDepthState.fromRaw(clone_depth_state, shallow_depth),
    )

/**
 * Reports battery saver, a background-restricted app, or a restricted standby bucket as
 * "restricted"; Doze is transient and deliberately not reported (it ends on its own).
 */
class AndroidBackgroundRestrictionProbe(private val context: Context) : BackgroundRestrictionProbe {
    override fun restriction(): RestrictionReason = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val usage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
        } else {
            null
        }
        val bucketRestricted = usage != null && usage.appStandbyBucket >= STANDBY_BUCKET_RESTRICTED
        val appRestricted = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
            activity?.isBackgroundRestricted == true
        when {
            appRestricted -> RestrictionReason.AppRestricted
            bucketRestricted -> RestrictionReason.StandbyRestricted
            power?.isPowerSaveMode == true -> RestrictionReason.BatterySaver
            else -> RestrictionReason.None
        }
    }.getOrDefault(RestrictionReason.None)

    private companion object {
        /** `UsageStatsManager.STANDBY_BUCKET_RESTRICTED` (API 28). */
        const val STANDBY_BUCKET_RESTRICTED = 45
    }
}

/**
 * git-sync-resilience Story 3.1.7: threshold past which a `RUNNING` [GitCloneWorker] [WorkInfo]
 * is presumed killed by OEM battery optimization (no exception thrown, `onStopped()` never
 * invoked — a distinct silent-kill mechanism a correctly `setForeground()`'d service does not
 * defeat, per pre-mortem.md P1 #1) rather than still legitimately transferring — comfortably past
 * [GIT_TRANSPORT_TIMEOUT_SECONDS] (300s) and the retry loop's 10-minute `maxElapsed` wall-clock
 * deadline (Story 3.1.4), both of which bound how long a legitimately-running clone can take.
 */
const val STUCK_CLONE_WATCHDOG_THRESHOLD_MINUTES = 30

/**
 * Pure watchdog check (Task 3.1.7a): true when [state] is `RUNNING` and [startTimeMs] is more
 * than [STUCK_CLONE_WATCHDOG_THRESHOLD_MINUTES] before [nowMs] — the one failure mode in this
 * plan with no thrown exception for `classifyGitFailure` to route at all. Deliberately
 * independent of WorkManager/SharedPreferences so it's directly unit-testable; see
 * [checkAndSurfaceStuckGitCloneWorker] for the real WorkManager-backed caller.
 */
fun isGitCloneWorkerStuck(state: WorkInfo.State?, startTimeMs: Long?, nowMs: Long): Boolean {
    if (state != WorkInfo.State.RUNNING || startTimeMs == null) return false
    return (nowMs - startTimeMs) > STUCK_CLONE_WATCHDOG_THRESHOLD_MINUTES * 60_000L
}

/**
 * `SharedPreferences`-backed start-time bookkeeping for [GitCloneWorker]'s stuck-clone watchdog
 * (Task 3.1.7a) — [WorkInfo] itself exposes no start-time field, so this app tracks it
 * explicitly, keyed by graphId, alongside the enqueue in [AndroidGitCloneWorkerLauncher].
 */
object GitCloneWorkTracker {
    private const val PREFS_NAME = "stelekit_git_clone_tracker"

    fun recordStart(context: Context, graphId: String, workId: java.util.UUID, startTimeMs: Long) {
        prefs(context).edit()
            .putString(workIdKey(graphId), workId.toString())
            .putLong(startTimeKey(graphId), startTimeMs)
            .apply()
    }

    fun clear(context: Context, graphId: String) {
        prefs(context).edit().remove(workIdKey(graphId)).remove(startTimeKey(graphId)).apply()
    }

    fun trackedWorkId(context: Context, graphId: String): java.util.UUID? =
        prefs(context).getString(workIdKey(graphId), null)
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }

    fun startTime(context: Context, graphId: String): Long? =
        prefs(context).getLong(startTimeKey(graphId), -1L).takeIf { it >= 0 }

    private fun workIdKey(graphId: String) = "${graphId}_work_id"
    private fun startTimeKey(graphId: String) = "${graphId}_start_ms"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Real WorkManager-backed caller of [isGitCloneWorkerStuck] (Task 3.1.7b) — queries the tracked
 * work id's current [WorkInfo] and posts [GitCloneWorker.STUCK_CLONE_WATCHDOG_MESSAGE] if stuck.
 * Called from [GitSyncWorker]'s existing periodic invocation; never throws (callers must guard —
 * see `GitSyncWorker.doWork()`'s `runCatching` call site), since this is a side-effecting
 * observation, not a retry decision.
 */
suspend fun checkAndSurfaceStuckGitCloneWorker(
    context: Context,
    graphId: String,
    nowMs: Long = System.currentTimeMillis(),
) {
    val workId = GitCloneWorkTracker.trackedWorkId(context, graphId) ?: return
    val info = WorkManager.getInstance(context).getWorkInfoByIdFlow(workId).first()
    if (info == null || info.state.isFinished) {
        GitCloneWorkTracker.clear(context, graphId)
        return
    }
    if (isGitCloneWorkerStuck(info.state, GitCloneWorkTracker.startTime(context, graphId), nowMs)) {
        GitCloneWorker.postStuckCloneWatchdogNotification(context)
    }
}

/**
 * git-sync-resilience Story 5.1.3: pure yield-decision — true only for a [WorkInfo.State.RUNNING]
 * [GitCloneWorker]. Split out (mirroring [isGitCloneWorkerStuck]) so the decision is directly
 * unit-testable without a real WorkManager-tracked work item.
 */
fun shouldSlowPathYieldToGitCloneWorker(state: WorkInfo.State?): Boolean = state == WorkInfo.State.RUNNING

/** Real WorkManager-backed caller of [shouldSlowPathYieldToGitCloneWorker] (Story 5.1.3), keyed
 * by [GitCloneWorkTracker]'s tracked id rather than the shared unique-work name (Story 5.1.2), to
 * avoid confusing a running [GitCloneWorker] with [GitSyncWorker] itself. */
suspend fun isGitCloneWorkerRunningFor(context: Context, graphId: String): Boolean {
    val workId = GitCloneWorkTracker.trackedWorkId(context, graphId) ?: return false
    val info = WorkManager.getInstance(context).getWorkInfoByIdFlow(workId).first()
    return shouldSlowPathYieldToGitCloneWorker(info?.state)
}
