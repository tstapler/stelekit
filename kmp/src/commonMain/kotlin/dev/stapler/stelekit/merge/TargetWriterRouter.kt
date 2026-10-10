package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.db.GraphLocator
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.ReadyGraph
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.StorageLocation
import kotlin.time.TimeSource

const val MAX_ROUTER_ATTEMPTS = 3

/**
 * The single router for merge and share: picks the [TargetWriter] for a target graph and runs one
 * page batch under `GraphWriteLock(target)`.
 *
 * Readiness is the `(id, repoSet)` pair from [GraphManager.readyGraph], never the registry's
 * `activeGraphId` alone (it flips before init finishes). Under the lock only the pair captured
 * there is used, because teardown is not lock-protected.
 *
 * Lock order: `awaitPendingMigration()` runs OUTSIDE any lock (the init coroutine needs `lock(id)`
 * to complete it), and a batch holds exactly one graph lock, never nested.
 *
 * Extension seam (Phase 4): share's inbox fallback wraps this router's `Left` results (a
 * [DomainError.MergeError.WriteRefused] or [DomainError.MergeError.Retryable]) in a decorator;
 * the router itself never knows about the inbox.
 *
 * @param activeWriterFor builds the writer for the captured ready pair (ActiveTargetWriter)
 * @param offGraphWriterFor builds the writer for an inactive target (MarkdownTargetWriter)
 */
class TargetWriterRouter(
    private val graphManager: GraphManager,
    private val locator: GraphLocator,
    private val capabilities: TargetWriterCapabilities,
    private val activeWriterFor: (ReadyGraph) -> TargetWriter,
    private val offGraphWriterFor: (GraphInfo) -> TargetWriter,
    private val isEncrypted: (GraphId) -> Boolean = { false },
    private val onLockWaitMs: (GraphId, Long) -> Unit = { id, ms ->
        Logger("TargetWriterRouter").info("merge.apply.lock_wait_ms graph=$id ms=$ms")
    },
) {
    /**
     * Runs [block] (one page batch) against the right writer for [target]. A
     * [DomainError.MergeError.Retryable] from [block] releases the lock and re-decides, up to
     * [MAX_ROUTER_ATTEMPTS]; the exhausted result is a retryable `Left`.
     */
    suspend fun <T> withWriter(
        target: GraphId,
        block: suspend (TargetWriter) -> Either<DomainError, T>,
    ): Either<DomainError, T> {
        repeat(MAX_ROUTER_ATTEMPTS) {
            val awaited = awaitIfSwitchInFlight(target)
            val info = locator.locate(target).fold({ return it.left() }, { it })
            val storage = graphManager.getStorageLocation(target.value)
            val result = runUnderLock(target, info, storage, awaited, block)
            if (result != null && !result.isRetryable()) return result
        }
        return DomainError.MergeError.Retryable(
            "Target graph ${target.value} kept changing state; gave up after $MAX_ROUTER_ATTEMPTS attempts",
        ).left()
    }

    /** Outside any lock. True when it waited for the in-flight init of [target]. */
    private suspend fun awaitIfSwitchInFlight(target: GraphId): Boolean {
        val notReady = graphManager.readyGraph.value?.id != target
        if (notReady && graphManager.graphRegistry.value.activeGraphId == target) {
            graphManager.awaitPendingMigration()
            return true
        }
        return false
    }

    /** Null means "re-decide": the pair changed between the await and the lock. */
    private suspend fun <T> runUnderLock(
        target: GraphId,
        info: GraphInfo,
        storage: StorageLocation?,
        awaited: Boolean,
        block: suspend (TargetWriter) -> Either<DomainError, T>,
    ): Either<DomainError, T>? {
        val waitStart = TimeSource.Monotonic.markNow()
        return graphManager.graphWriteLock.withLock(target, label = "merge($target)") {
            onLockWaitMs(target, waitStart.elapsedNow().inWholeMilliseconds)
            val ready = graphManager.readyGraph.value
            when {
                ready?.id == target -> block(activeWriterFor(ready))
                // Registry says active but the pair is not published: a switch started after our
                // await. After an await it means init failed, so fall through to the file writer.
                !awaited && graphManager.graphRegistry.value.activeGraphId == target -> null
                else -> capabilities.canWriteOffGraph(
                    OffGraphTarget(target, info.path, isActive = false, encrypted = isEncrypted(target), storage = storage),
                ).fold(
                    { reason -> DomainError.MergeError.WriteRefused(WriteRefusedReason.Unwritable(reason)).left() },
                    { block(offGraphWriterFor(info)) },
                )
            }
        }
    }

    private fun Either<DomainError, *>.isRetryable() =
        leftOrNull() is DomainError.MergeError.Retryable
}
