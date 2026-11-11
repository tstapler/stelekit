// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import dev.stapler.stelekit.jobs.workManagerOrNull
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** What one [ScheduleStartup.run] did; each step is empty when it was skipped or failed. */
data class ScheduleStartupReport(
    val migrated: Set<TaskKey> = emptySet(),
    val cancelledScopes: Set<TaskScope> = emptySet(),
    val reapplied: Set<TaskKey> = emptySet(),
    val resumed: Set<TaskKey> = emptySet(),
)

/**
 * Re-arms scheduled work after a reboot, force-stop or update without opening any graph (ADR-003, ADR-005).
 * Order: migrate the ACTIVE graph (the store's lazy seed), reconcile orphans, then [reapplyAll] from the ledger.
 * Every step is independently guarded and the whole run is skipped when WorkManager is not initialised, so
 * `Application.onCreate` never fails on it. Non-active graphs migrate lazily on first read, worker run or resume.
 */
class ScheduleStartup(
    private val store: TaskSettingsStore,
    private val scheduler: BackgroundTaskScheduler,
    private val registry: TaskRegistry,
    private val ledger: AppliedConfigLedger,
    private val knownScopes: KnownScopes,
    /** The registry's active graph id, or null when none. Reads persisted settings only; never opens a graph. */
    private val activeGraphId: suspend () -> String?,
    /** Cancels work of removed graphs (`ScheduledTaskReconciler.reconcile`); fail closed on its own. */
    private val reconcile: suspend () -> Set<TaskScope>,
    private val workManagerReady: () -> Boolean,
    /** True while unfinished non-periodic work (a clone) holds [key]'s work name; see [isCloneWorkLive]. */
    private val cloneWorkLive: suspend (TaskKey) -> Boolean,
    /** Called with the time of a settings read that failed, for the "Can't read settings" status (Story 4.1.2). */
    private val onSettingsUnreadable: (Long) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val logger = Logger("ScheduleStartup")

    suspend fun run(): ScheduleStartupReport {
        if (!safely("WorkManager readiness check") { workManagerReady() }.let { it == true }) return ScheduleStartupReport()
        val migrated = safely("migrate") { migrateActiveGraph() } ?: emptySet()
        val cancelled = safely("reconcile") { reconcile() } ?: emptySet()
        val resumed = safely("resumeStalePaused") { resumeStalePaused() } ?: emptySet()
        val reapplied = safely("reapplyAll") { reapplyAll() } ?: emptySet()
        return ScheduleStartupReport(migrated, cancelled, reapplied, resumed)
    }

    private fun periodicKinds(): List<TaskKind> =
        registry.kinds().filter { registry.specFor(it)?.shape is TaskShape.Periodic }

    /** Reads (and so lazily seeds) each periodic kind for the active graph only. */
    private suspend fun migrateActiveGraph(): Set<TaskKey> {
        val active = activeGraphId()?.takeIf { it.isNotEmpty() } ?: return emptySet()
        val migrated = mutableSetOf<TaskKey>()
        for (kind in periodicKinds()) {
            val key = TaskKey(kind, TaskScope(active))
            when (store.readResult(key)) {
                is TaskReadResult.Ok -> migrated += key
                is TaskReadResult.ReadFailed -> onSettingsUnreadable(nowMs())
            }
        }
        return migrated
    }

    /**
     * A clone killed with its process never ran its resume, leaving a paused snapshot (and no periodic work) forever.
     * Resumes each scope whose snapshots have no live clone work; a scope with a live clone stays paused for the
     * clone's own resume. Runs before [reapplyAll], whose `apply` would otherwise only update the snapshot.
     */
    suspend fun resumeStalePaused(): Set<TaskKey> {
        val scopes = (knownScopes.read() as? KnownScopesResult.Known)?.scopes ?: return emptySet()
        val resumed = mutableSetOf<TaskKey>()
        for (scope in scopes) {
            val paused = periodicKinds().map { TaskKey(it, scope) }.filter { ledger.pausedSnapshot(it) != null }
            if (paused.isEmpty() || paused.any { cloneWorkLive(it) }) continue
            scheduler.resume(scope)
            resumed += paused
        }
        return resumed
    }

    /**
     * Backstop for work a force-stop wiped: for each known scope x periodic kind with a ledger entry and no live
     * work, applies the ledger's config. Reads the LEDGER only (never [TaskSettingsStore], so no lazy seed and no
     * graph DB) and does nothing when scopes are [KnownScopesResult.Unknown]. Live work is left untouched
     * because `apply` with UPDATE would move its pending run.
     */
    suspend fun reapplyAll(): Set<TaskKey> {
        val scopes = (knownScopes.read() as? KnownScopesResult.Known)?.scopes ?: return emptySet()
        val reapplied = mutableSetOf<TaskKey>()
        for (scope in scopes) for (kind in periodicKinds()) {
            val key = TaskKey(kind, scope)
            val config = ledger.lastApplied(key)?.takeIf { it.enabled } ?: continue
            if (scheduler.describe(key) != null) continue
            scheduler.apply(key, config)
            reapplied += key
        }
        return reapplied
    }

    private suspend fun <T> safely(step: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("startup step '$step' failed; continuing", e)
        null
    }
}

/** True when WorkManager is initialised for [context]; the host passes it as [ScheduleStartup]'s readiness check. */
fun isWorkManagerReady(context: Context): Boolean = workManagerOrNull(context) != null

/** True when unfinished non-periodic work (an in-flight clone) holds [key]'s unique work name. Unreadable counts as live. */
suspend fun isCloneWorkLive(context: Context, key: TaskKey): Boolean {
    val wm = workManagerOrNull(context) ?: return false
    return try {
        withContext(PlatformDispatcher.IO) { wm.getWorkInfosForUniqueWork(key.workName()).get() }
            .any { !it.state.isFinished && it.periodicityInfo == null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Logger("ScheduleStartup").warn("cannot read work for ${key.workName()}; treating as live", e)
        true
    }
}
