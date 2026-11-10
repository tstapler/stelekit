// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.stapler.stelekit.jobs.workManagerOrNull
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.PlatformSettings
import kotlinx.coroutines.CancellationException

/**
 * Process-wide inputs a cold [TaskWorker] cannot receive by constructor (WorkManager builds it by
 * reflection). `SteleKitApplication.onCreate` calls [install] before any worker can run.
 */
object TaskWorkerRuntime {
    @Volatile private var installedRegistry: TaskRegistry = TaskRegistry()

    /** Set by later stories (git legacy seed); [LegacyConfigSeed.None] until then. */
    @Volatile var legacySeed: LegacyConfigSeed = LegacyConfigSeed.None
    @Volatile var foreground: TaskForegroundProvider = TaskForegroundProvider()

    /**
     * Set by `SteleKitApplication.onCreate` to `GraphRegistryKnownScopes` (jobs.schedule must not import
     * `db`, ADR-001). Until set, `Unknown` means a worker never cancels itself.
     */
    @Volatile var knownScopes: KnownScopes = KnownScopes { KnownScopesResult.Unknown }

    private val hooksByKind = java.util.concurrent.ConcurrentHashMap<TaskKind, TaskAdapterHooks>()

    /** Test seam: replaces dependency construction entirely. Null in production. */
    @Volatile internal var depsOverride: ((Context) -> TaskWorkerDeps)? = null

    val registry: TaskRegistry get() = installedRegistry

    fun install(registry: TaskRegistry) {
        installedRegistry = registry
    }

    fun registerHooks(kind: TaskKind, hooks: TaskAdapterHooks) {
        hooksByKind[kind] = hooks
    }

    internal fun depsFor(context: Context): TaskWorkerDeps {
        depsOverride?.let { return it(context) }
        val settings = PlatformSettings()
        return TaskWorkerDeps(
            registry = installedRegistry,
            store = TaskSettingsStore(settings, legacySeed, installedRegistry),
            runLog = SettingsTaskRunLog(settings),
            network = AndroidNetworkSnapshotProvider(context),
            knownScopes = knownScopes,
            hooks = hooksByKind::get,
            health = SettingsReadHealth(context),
        )
    }
}

/**
 * Generic worker for one-shots and non-git periodic kinds: gates, runs, records, never silently crashes.
 * All logic is in [runTaskWork]; this maps its outcome to a WorkManager `Result`. `git-sync` is run by
 * the thin `GitSyncWorker`, not by this class.
 */
class TaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val logger = Logger("TaskWorker")

    override suspend fun doWork(): Result {
        val kindId = inputData.getString(TaskWorkKeys.KEY_TASK_KIND) ?: return Result.failure()
        val key = try {
            TaskKey(TaskKind(kindId), TaskScope(inputData.getString(TaskWorkKeys.KEY_TASK_SCOPE).orEmpty()))
        } catch (_: IllegalArgumentException) {
            return Result.failure()
        }
        return try {
            val deps = TaskWorkerRuntime.depsFor(applicationContext)
            val outcome = runTaskWork(
                context = applicationContext,
                key = key,
                input = inputData,
                runAttemptCount = runAttemptCount,
                deps = deps,
                promoteToForeground = { kind ->
                    TaskWorkerRuntime.foreground.factoryFor(kind)?.let { setForeground(it.create(applicationContext, key)) }
                },
            )
            when (outcome) {
                TaskWorkOutcome.Success -> Result.success()
                TaskWorkOutcome.Retry -> Result.retry()
                is TaskWorkOutcome.Failure ->
                    if (outcome.error == null) Result.failure() else Result.failure(workDataOf(TaskWorkKeys.KEY_ERROR to outcome.error))
                TaskWorkOutcome.CancelOwnWork -> {
                    workManagerOrNull(applicationContext)?.cancelUniqueWork(key.workName())
                    Result.success()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error("TaskWorker failed for ${key.kind.id}", e)
            Result.failure(workDataOf(TaskWorkKeys.KEY_ERROR to (e.message ?: e::class.simpleName ?: "error")))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val key = TaskKey(
            TaskKind(inputData.getString(TaskWorkKeys.KEY_TASK_KIND).orEmpty().ifEmpty { "unknown" }),
            TaskScope(inputData.getString(TaskWorkKeys.KEY_TASK_SCOPE).orEmpty()),
        )
        val factory = TaskWorkerRuntime.foreground.factoryFor(key.kind) ?: error("no foreground info for ${key.kind.id}")
        return factory.create(applicationContext, key)
    }
}
