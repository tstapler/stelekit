// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The root's `GraphManager.onGraphRemoved` handler: cancels the removed graph's scheduled work and tombstones
 * its periodic kinds so a re-added graph does not silently resume (ADR-003). Non-suspending: it launches on
 * [scope], which the root owns and guards (SupervisorJob + CoroutineExceptionHandler, never a composition
 * scope). A missed run is backstopped by the startup reconcile. Tombstones only from this path.
 */
class GraphRemovalTasks(
    private val scheduler: BackgroundTaskScheduler,
    private val store: TaskSettingsStore,
    private val registry: TaskRegistry,
    private val scope: CoroutineScope,
) {
    private val logger = Logger("GraphRemovalTasks")

    fun onRemoved(graph: TaskScope) {
        scope.launch {
            try {
                scheduler.cancelAll(graph)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("cancelAll failed for ${graph.value}", e)
            }
            for (kind in registry.kinds()) {
                if (registry.specFor(kind)?.shape !is TaskShape.Periodic) continue
                try {
                    store.tombstone(TaskKey(kind, graph))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn("tombstone failed for ${kind.id}/${graph.value}", e)
                }
            }
        }
    }
}
