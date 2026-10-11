package dev.stapler.stelekit.platform

import dev.stapler.stelekit.coroutines.SessionLifecycle
import dev.stapler.stelekit.git.model.DirtyEntry
import dev.stapler.stelekit.git.model.DirtySetMarker
import dev.stapler.stelekit.git.model.PendingCommit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * Git dirty-tracking / marker-scheduler bookkeeping — the second of [GraphSyncSession]'s two
 * distinct concerns (see that class's KDoc). Extracted to its own nested value object per
 * architecture-review.md's cohesion Concern: this changes only when the dirty-tracking/
 * marker-scheduling *data shape* changes, never when [GraphSyncSession]'s own composition
 * changes. Pure data — no lifecycle methods, no scope; swapped wholesale along with its owning
 * [GraphSyncSession], never independently.
 */
data class GitWriteState(
    var dirtySet: MutableMap<String, DirtyEntry>,
    var baseSha: String,
    var pendingCommit: PendingCommit,
    var markerWriteInFlight: Boolean = false,
    var pendingMarkerWrite: DirtySetMarker? = null,
)

/**
 * [PlatformFileSystem]'s per-graph state bundle: composes [graphId], a [scope] (see below),
 * [hostDirectorySync] (Epic 1.1's per-graph [SessionLifecycle]), and one nested [gitWriteState].
 * Swapped wholesale by [dev.stapler.stelekit.coroutines.GraphScopedSession.switchTo] on every
 * graph switch — `PlatformFileSystem` itself stays a singleton; only this bundle is per-graph.
 *
 * **`scope` hosts no coroutine of substance today.** `scheduleMarkerWrite()`'s launched
 * coroutine continues to run on `PlatformFileSystem`'s own long-lived `scope` field, unchanged
 * from before this migration — a scheduled marker write must survive a graph switch using its
 * own entry-captured values (Story 2.1.3), which is only possible if that coroutine's scope is
 * never cancelled by [close]. This field exists solely to satisfy [SessionLifecycle]'s contract
 * and as a defensive cancellation point for any future per-graph coroutine added directly to
 * this bundle. See ADR-019's Consequences section and architecture-review.md Blocker 3.
 */
internal class GraphSyncSession(
    val graphId: OpfsGraphSlug,
    override val scope: CoroutineScope,
    val hostDirectorySync: HostDirectorySync,
    val gitWriteState: GitWriteState,
) : SessionLifecycle {

    /**
     * Tears down [hostDirectorySync] (stopping its poller and cancelling its own scope) before
     * cancelling [scope] — never the reverse, so the host-sync poller/write-through coroutines
     * are fully torn down before the bundle's own scope is, avoiding a half-torn-down state
     * where the scope is gone but a poller tick is still scheduled on it.
     */
    override fun close() {
        hostDirectorySync.close()
        scope.cancel()
    }
}
