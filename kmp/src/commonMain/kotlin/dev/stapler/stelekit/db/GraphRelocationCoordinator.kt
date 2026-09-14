// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pluggable copy-and-verify step, defaulting to [BulkCopyVerifier.copyAndVerifyPaths]. A named
 * seam (rather than a hard dependency on the concrete, non-`open` [BulkCopyVerifier] class) so
 * `businessTest`s can substitute an instrumented fake that suspends at a checkpoint reached only
 * after every file is copied and hash-verification has begun — the mid-`Verifying` cancellation
 * case `GraphRelocationCoordinator`'s own tests need (Task 3.1.5l) — without [BulkCopyVerifier]
 * itself needing to expose that internal checkpoint.
 */
fun interface CopyAndVerifyStep {
    suspend fun run(
        sourceRoot: String,
        destinationRoot: String,
        onProgress: (processed: Int, total: Int) -> Unit,
    ): Either<DomainError.StorageError, CopyReport>
}

/**
 * Orchestrates a [StorageMoveOperation.Relocate] end to end: quiesce → close the driver → copy
 * into a staging directory → verify → atomically repoint → reopen-and-confirm → repersist →
 * release, per Story 3.1.5's acceptance criteria. This is the safety-critical core of the whole
 * app-owned-storage feature — read that story's plan.md section, not this file's comments, for
 * *why* each step is sequenced the way it is; two adversarial-review/pre-mortem passes already
 * found and closed several race conditions here (most notably: `GraphManager.switchGraph()` is
 * fire-and-forget, so every exit from the closed-driver region below pairs it with a suspend on
 * `GraphManager.awaitPendingMigration()` before treating the graph as editable again).
 *
 * Constructed with the same uniform, dependency-only style `GraphManager` itself receives its
 * platform collaborators in — [quiesceStrategy] is Story 3.1.3's `commonMain` port, so this class
 * never references an `androidMain`/`wasmJsMain`-only type directly.
 */
class GraphRelocationCoordinator(
    private val graphManager: GraphManager,
    private val fileSystem: FileSystem,
    private val quiesceStrategy: GraphMoveQuiesceStrategy,
    private val copyAndVerifyStep: CopyAndVerifyStep = CopyAndVerifyStep { source, destination, onProgress ->
        BulkCopyVerifier(fileSystem).copyAndVerifyPaths(source, destination, onProgress)
    },
) {
    /**
     * Runs [operation] end to end, emitting UI-facing progress/terminal states. Collecting this
     * flow off the main dispatcher is the caller's responsibility (mirrors [GraphManager]'s own
     * "called from the Compose UI dispatcher, heavy work moved to `PlatformDispatcher.IO`
     * internally" convention) — this class dispatches its own blocking file/DB work to
     * [PlatformDispatcher.IO] internally but does not switch dispatchers for the flow's
     * collection itself.
     *
     * Cancelling collection of the returned [Flow] at any point after the driver has been closed
     * (during copy or verify) still reopens and confirms the driver via a `NonCancellable` cleanup
     * — see the closed-driver-region `try`/`finally` below — but a cancelled flow can no longer
     * successfully deliver a terminal emission, so no state is sent on that path; only the
     * cleanup (reopen, quiesce release, flag clear) runs.
     */
    fun relocate(operation: StorageMoveOperation.Relocate): Flow<StorageMoveUiState> = channelFlow {
        val graphIdValue = operation.graphId
        val graphId = GraphId(graphIdValue)

        MoveInProgressFlag.setMoveInProgress(graphIdValue, true)
        try {
            send(StorageMoveUiState.Quiescing)

            // Steps 1-2. Bounded so a stuck drain surfaces as a visible failure instead of an
            // indefinite spinner (this codebase's own documented "silent indefinite hang" class).
            val quiesceOutcome: Either<DomainError.StorageError, Unit>? =
                withTimeoutOrNull(QUIESCE_TIMEOUT_MS) { quiesceStrategy.quiesce(operation) }

            if (quiesceOutcome == null) {
                // The driver was never closed on this path — no reopen call at all.
                send(StorageMoveUiState.Failed(DomainError.StorageError.QuiesceTimedOut(QUIESCE_TIMEOUT_MS)))
                return@channelFlow
            }
            if (quiesceOutcome is Either.Left) {
                send(StorageMoveUiState.Failed(quiesceOutcome.value))
                return@channelFlow
            }

            // Steps 3-7: the closed-driver region. Every exit path — happy, copy failure,
            // verification failure, or cancellation — reopens the driver (step 6) before the
            // operation ends; the finally below runs under NonCancellable so it still executes
            // even if this coroutine itself has been cancelled mid-copy/verify.
            var copyOutcome: Either<DomainError.StorageError, Unit> =
                DomainError.StorageError.RelocationFailed(graphIdValue).left()
            var reopenedRepositorySet: RepositorySet? = null
            try {
                withContext(PlatformDispatcher.IO) {
                    graphManager.tearDownActiveGraphResources()?.close()
                }
                copyOutcome = copyIntoStagingThenRepoint(operation)
            } finally {
                withContext(NonCancellable) {
                    // Reopen-and-confirm is one inseparable pair — switchGraph() alone only
                    // schedules the reopen; awaitPendingMigration() is what actually waits for it.
                    graphManager.switchGraph(graphId, forceReinit = true)
                    reopenedRepositorySet = graphManager.awaitPendingMigration()

                    // Step 7: only on the happy path, and only once reopen is confirmed, is the
                    // new location persisted — never through a closed/unconfirmed connection.
                    if (reopenedRepositorySet != null && copyOutcome is Either.Right) {
                        graphManager.onGraphLocationDetermined(graphIdValue, operation.destination)
                    }
                }
            }

            // Only reached if the coroutine above was not cancelled.
            val terminalState = when {
                reopenedRepositorySet == null ->
                    StorageMoveUiState.ReopenFailed(graphId, DomainError.StorageError.ReopenFailed(graphIdValue))
                copyOutcome is Either.Left -> StorageMoveUiState.Failed(copyOutcome.value)
                else -> StorageMoveUiState.Summary
            }
            send(terminalState)
        } finally {
            // Step 8, shared by every exit path above (success, ordinary failure, quiesce
            // timeout/failure, or cancellation at any point) — under NonCancellable so it still
            // runs when this coroutine itself has been cancelled.
            withContext(NonCancellable) {
                quiesceStrategy.release(operation)
                MoveInProgressFlag.setMoveInProgress(graphIdValue, false)
            }
        }
    }

    /**
     * Steps 4-5: copy+verify into the staging directory, then atomically repoint staging to the
     * final destination. The untouched source is only ever read here, never written.
     */
    private suspend fun ProducerScope<StorageMoveUiState>.copyIntoStagingThenRepoint(
        operation: StorageMoveOperation.Relocate,
    ): Either<DomainError.StorageError, Unit> = withContext(PlatformDispatcher.IO) {
        val sourceRoot = when (val resolved = resolveSourceRoot(operation.source)) {
            is Either.Left -> return@withContext resolved
            is Either.Right -> resolved.value
        }
        val destinationRoot = when (val resolved = resolveDestinationRoot(operation.destination)) {
            is Either.Left -> return@withContext resolved
            is Either.Right -> resolved.value
        }

        val destinationParent = destinationRoot.substringBeforeLast("/", missingDelimiterValue = "")
        val stagingPath = RelocationStagingDirectory.stagingPath(destinationParent, operation.graphId)
        RelocationStagingDirectory.writeMarker(fileSystem, stagingPath, operation.graphId)

        send(StorageMoveUiState.Copying(0, 0))
        // Verifying is rendered from inside this same copy-and-verify call Copying is (Story
        // 3.1.1's BulkCopyVerifier interleaves copy and verify per file, so there is no separate
        // "all copied, now verifying" suspension point to hook) — the best available signal is
        // the final progress batch, which fires only once every file has been both copied and
        // independently re-verified. See StorageMoveUiState.Verifying's doc.
        val copyResult = copyAndVerifyStep.run(sourceRoot, stagingPath) { processed, total ->
            trySend(StorageMoveUiState.Copying(processed, total))
            if (total > 0 && processed >= total) trySend(StorageMoveUiState.Verifying)
        }
        val report = when (copyResult) {
            is Either.Left -> return@withContext copyResult
            is Either.Right -> copyResult.value
        }

        val createdDirs = mutableSetOf<String>()
        val moves = report.verifiedPaths.map { relativePath ->
            val to = "$destinationRoot/$relativePath"
            val parent = to.substringBeforeLast("/", missingDelimiterValue = destinationRoot)
            if (createdDirs.add(parent)) fileSystem.createDirectory(parent)
            FileMove(from = "$stagingPath/$relativePath", to = to)
        }
        AtomicFileRelocationStep.relocate(fileSystem, moves)
    }

    /**
     * Resolves [location] as the *current* root of a graph's content. [StorageLocation.DirectAccessFolder]
     * and [StorageLocation.SafFolder] carry their path directly (see `resolveRootPathOrNull` in
     * `BulkCopyVerifier.kt`); [StorageLocation.AppOwned] has no path of its own (Story 3.1.1's
     * `BulkCopyVerifier` class doc), so it is looked up from [GraphManager]'s registry — the graph
     * is already living there, so [GraphManager.getGraphInfo]'s `path` is that same app-owned
     * directory `FileSystem.newAppOwnedGraphPath()` allocated when this graph was created or last
     * relocated. [StorageLocation.HostFolder] is a known, documented gap — see [hostFolderUnsupported].
     */
    private fun resolveSourceRoot(location: StorageLocation): Either<DomainError.StorageError, String> =
        when (location) {
            is StorageLocation.AppOwned ->
                graphManager.getGraphInfo(GraphId(location.graphId))?.path?.right()
                    ?: DomainError.StorageError.DestinationNotWritable(
                        "No registered path for app-owned graph ${location.graphId} — it was never opened",
                    ).left()
            is StorageLocation.HostFolder -> hostFolderUnsupported(location)
            else -> location.resolveRootPathOrNull()?.right()
                ?: DomainError.StorageError.DestinationNotWritable(
                    "Cannot resolve a filesystem root path for location: $location",
                ).left()
        }

    /**
     * Resolves [location] as a *fresh* destination root for a relocate/link target.
     * [StorageLocation.AppOwned] is never an existing, registered path here — it is a brand-new
     * app-private directory the move is about to populate — so unlike [resolveSourceRoot] this
     * allocates one via [FileSystem.newAppOwnedGraphPath] rather than consulting [GraphManager]'s
     * registry (which, for a move *into* app-owned storage, still reflects the graph's old location).
     */
    private fun resolveDestinationRoot(location: StorageLocation): Either<DomainError.StorageError, String> =
        when (location) {
            is StorageLocation.AppOwned ->
                try {
                    fileSystem.newAppOwnedGraphPath().right()
                } catch (e: UnsupportedOperationException) {
                    DomainError.StorageError.DestinationNotWritable(
                        "This platform does not support app-owned storage: ${e.message}",
                    ).left()
                }
            is StorageLocation.HostFolder -> hostFolderUnsupported(location)
            else -> location.resolveRootPathOrNull()?.right()
                ?: DomainError.StorageError.DestinationNotWritable(
                    "Cannot resolve a filesystem root path for location: $location",
                ).left()
        }

    /**
     * [StorageLocation.HostFolder] content lives behind an opaque `FileSystemDirectoryHandle`
     * (Web `HostDirectorySync`), not a plain path string — [BulkCopyVerifier]'s generic path-based
     * copy has no way to reach it. This is a known, deliberate gap (not a silent failure): a future
     * story must give `HostDirectorySync` its own copy mechanism built on its existing
     * directory-handle read/write-through primitives, then wire it in here as an alternative to
     * [CopyAndVerifyStep] rather than trying to force it through a root-path string.
     */
    private fun hostFolderUnsupported(location: StorageLocation.HostFolder): Either<DomainError.StorageError, String> =
        DomainError.StorageError.DestinationNotWritable(
            "HostFolder \"${location.displayName}\" relocation is not implemented: its content lives " +
                "behind an opaque FileSystemDirectoryHandle, not a filesystem path, so it cannot be " +
                "copied by BulkCopyVerifier's generic path-based copy. Needs a HostDirectorySync-based " +
                "copy path (future story).",
        ).left()

    companion object {
        /**
         * 30s: long enough for a large `GitWriteBackQueue` drain under normal load, short enough
         * that a stuck flush surfaces as a visible failure within one user-patience window rather
         * than an indefinite spinner.
         */
        const val QUIESCE_TIMEOUT_MS = 30_000L
    }
}
