// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.jobs.workManagerOrNull
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Cancels scheduled work whose graph no longer exists (ADR-005). Fail closed: acts only on
 * [KnownScopesResult.Known]; [KnownScopesResult.Unknown] cancels nothing. Never cancels the app-wide scope
 * `""` and never writes a tombstone (only `GraphManager.removeGraph`'s hook does). Never throws.
 */
class ScheduledTaskReconciler(
    private val context: Context,
    private val knownScopes: KnownScopes,
) {
    private val logger = Logger("ScheduledTaskReconciler")

    /** Returns the scopes whose work was cancelled. */
    suspend fun reconcile(): Set<TaskScope> = try {
        when (val known = knownScopes.read()) {
            KnownScopesResult.Unknown -> emptySet()
            is KnownScopesResult.Known -> if (known.scopes.isEmpty()) emptySet() else cancelOrphans(known.scopes)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("reconcile failed; nothing further cancelled", e)
        emptySet()
    }

    private suspend fun cancelOrphans(known: Set<TaskScope>): Set<TaskScope> {
        val wm = workManagerOrNull(context) ?: return emptySet()
        val infos = withContext(PlatformDispatcher.IO) { wm.getWorkInfosByTag(SchedTags.ANY).get() }
        val orphans = infos.mapNotNull { SchedTags.scopeOf(it.tags) }
            .filter { it.value.isNotEmpty() && it !in known }
            .toSet()
        for (scope in orphans) withContext(PlatformDispatcher.IO) { wm.cancelAllWorkByTag(SchedTags.scope(scope)) }
        return orphans
    }
}
