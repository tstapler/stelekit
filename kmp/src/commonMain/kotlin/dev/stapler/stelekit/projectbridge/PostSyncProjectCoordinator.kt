// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Owns [PostSyncProjectJob] and decides when it runs, so `StelekitViewModel` only constructs it.
 *
 * Trigger: the persisted `gitLastSyncAt` of `GitSyncCoordinator`, not `SyncState.Success`. `GitSyncService`
 * records `lastSyncAt` before it picks the final state and replaces `Success` with `LfsActionRequired` when LFS
 * files are held, so a state-based trigger would miss those syncs (and a short-lived StateFlow value can be
 * missed anyway). The first emission after the graph is ready is the "once at graph open" run. A graph switch
 * restarts the collector body ([collectLatest]), which cancels the in-flight run.
 *
 * Runs are keyed by `(graphId, lastSyncAt)`; a key is remembered only when the run finished, so an
 * unsettled or failed run retries on the next trigger. Owns its scope, parented to the caller's `Job` and
 * guarded by a [CoroutineExceptionHandler] (an uncaught OOM kills the process on Android).
 */
class PostSyncProjectCoordinator(
    private val deps: ProjectBridgeDeps,
    private val gitLastSyncAt: StateFlow<Long?>,
    /** Reads the service's own state: a `stateIn` copy can lag the `lastSyncAt` emission that triggered the run. */
    private val syncStateProvider: () -> SyncState?,
    private val activeGraphIdProvider: () -> String?,
    parentContext: CoroutineContext,
    private val job: PostSyncProjectJob = PostSyncProjectJob(deps),
) {
    private val logger = Logger("PostSyncProjectCoordinator")
    private val scope = CoroutineScope(
        parentContext + SupervisorJob(parentContext[Job]) + CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) logger.error("post-sync project job: uncaught ${e::class.simpleName}")
        },
    )

    @kotlin.concurrent.Volatile
    private var lastFinishedKey: Pair<String, Long?>? = null

    fun start() {
        scope.launch {
            combine(deps.readyGraphIds.distinctUntilChanged(), gitLastSyncAt) { ready, at -> ready to at }
                .distinctUntilChanged()
                .collectLatest { (ready, at) -> onTrigger(ready, at) }
        }
    }

    private suspend fun onTrigger(readyGraphId: String?, lastSyncAt: Long?) {
        val graphId = readyGraphId ?: return
        if (graphId != activeGraphIdProvider()) return
        val key = graphId to lastSyncAt
        if (key == lastFinishedKey) return
        val outcome = try {
            job.run(graphId) { syncStateProvider() is SyncState.LfsActionRequired }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error("post-sync project job failed: ${e::class.simpleName}")
            return
        }
        if (outcome is PostSyncRunOutcome.Completed || outcome is PostSyncRunOutcome.DigestUnavailable) {
            lastFinishedKey = key
        }
    }
}
