// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
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

/** `StorageLocation.kind`-string convention, matching `GraphManager`'s `toStorageLocationRow` (Story 1.1.3). */
private fun StorageLocation.kindName(): String = this::class.simpleName ?: "Unknown"

/** "Relocate" / "Link" — matches `StorageMoveOperation`'s sealed-interface case names. */
private fun StorageMoveOperation.operationTypeName(): String = this::class.simpleName ?: "Unknown"

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
 * Platform seam for [GraphRelocationCoordinator.link] (Epic 4.1, Task 4.1.1a) — attaches
 * [destination] as a continuous mirror of the already-live content at [existingOpfsPath], then
 * returns once that mirror is established. `commonMain` cannot reference wasmJsMain's
 * `HostDirectorySync.connectHostDirectory` directly (KMP's one-way dependency direction — see
 * [GraphMoveQuiesceStrategy]'s doc comment for the same constraint this codebase already
 * follows), so this narrow port stands in for it: Web wires it via
 * `createWasmJsHostLinkStep(hostDirectorySync)`, which forwards straight to
 * `connectHostDirectory` (reusing its existing `runHostReconciliation` walk unchanged, per Story
 * 4.1.1's acceptance criteria) rather than reimplementing a parallel connect flow. `null` (the
 * default) means this platform/graph has no Link implementation — Android's own Link mode (Epic
 * 4.2, git-shadow-worktree write-back) is a different mechanism entirely and is not routed
 * through [GraphRelocationCoordinator].
 */
fun interface HostLinkStep {
    suspend fun link(existingOpfsPath: String, destination: StorageLocation): Either<DomainError.StorageError, Unit>
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
        // A git-cloned graph's history lives entirely in a root-level .git directory that
        // listFilesRecursiveWithModTimes's shared default (FileSystem.kt) deliberately excludes
        // for Android's shadow-worktree mirror — copyAndVerifyPaths's includeGitDirectory opt-in
        // is what puts .git back on the same real per-file SHA-256 verify BulkCopyVerifier already
        // gives markdown, closing the silent-history-loss bug a plain relocate used to hit.
        val includeGitDirectory = fileSystem.directoryExists("$source/.git")
        BulkCopyVerifier(fileSystem).copyAndVerifyPaths(source, destination, onProgress, includeGitDirectory)
    },
    /** See [HostLinkStep]'s doc comment. `null` (the default) means [link] always fails fast with
     * [DomainError.StorageError.DestinationNotWritable] — every non-Web construction site. */
    private val hostLinkStep: HostLinkStep? = null,
) {
    private val logger = Logger("GraphRelocationCoordinator")

    /** Story 5.1.1: `MoveStarted`, fired before any quiesce/copy work begins. */
    private fun logMoveStarted(operation: StorageMoveOperation) {
        logger.info(
            "MoveStarted graphId=${operation.graphId} source=${operation.source.kindName()} " +
                "destination=${operation.destination.kindName()} operation=${operation.operationTypeName()}",
        )
    }

    /**
     * Story 5.1.1: `MoveVerified`, fired once [copyAndVerifyStep] returns (both outcomes) —
     * `hashMismatches` is only ever 1 today since [CopyAndVerifyStep] surfaces at most one
     * [DomainError.StorageError.VerificationFailed] per call, not a per-file tally.
     */
    private fun logMoveVerified(
        operation: StorageMoveOperation.Relocate,
        outcome: Either<DomainError.StorageError, CopyReport>,
    ) {
        val filesCount = (outcome as? Either.Right)?.value?.filesCopied ?: 0
        val hashMismatches = if ((outcome as? Either.Left)?.value is DomainError.StorageError.VerificationFailed) 1 else 0
        logger.info(
            "MoveVerified graphId=${operation.graphId} source=${operation.source.kindName()} " +
                "destination=${operation.destination.kindName()} passed=${outcome is Either.Right} " +
                "files=$filesCount hashMismatches=$hashMismatches",
        )
    }

    /** Story 5.1.1: `MoveCompleted`/`MoveFailed`, fired once [state] is a terminal UI state. */
    private fun logMoveTerminal(operation: StorageMoveOperation, state: StorageMoveUiState) {
        val graphId = operation.graphId
        val sourceKind = operation.source.kindName()
        val destinationKind = operation.destination.kindName()
        val opType = operation.operationTypeName()
        when (state) {
            is StorageMoveUiState.Summary -> logger.info(
                "MoveCompleted graphId=$graphId source=$sourceKind destination=$destinationKind operation=$opType",
            )
            is StorageMoveUiState.Failed -> logger.error(
                "MoveFailed graphId=$graphId source=$sourceKind destination=$destinationKind " +
                    "operation=$opType reason=${state.reason::class.simpleName}",
            )
            is StorageMoveUiState.ReopenFailed -> logger.error(
                "MoveFailed graphId=$graphId source=$sourceKind destination=$destinationKind " +
                    "operation=$opType reason=${state.cause::class.simpleName}",
            )
            else -> {}
        }
    }

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

        logMoveStarted(operation)
        MoveInProgressFlag.setMoveInProgress(graphIdValue, true)
        try {
            send(StorageMoveUiState.Quiescing)

            // Steps 1-2. Bounded so a stuck drain surfaces as a visible failure instead of an
            // indefinite spinner (this codebase's own documented "silent indefinite hang" class).
            val quiesceOutcome: Either<DomainError.StorageError, Unit>? =
                withTimeoutOrNull(QUIESCE_TIMEOUT_MS) { quiesceStrategy.quiesce(operation) }

            if (quiesceOutcome == null) {
                // The driver was never closed on this path — no reopen call at all.
                val timeoutState = StorageMoveUiState.Failed(DomainError.StorageError.QuiesceTimedOut(QUIESCE_TIMEOUT_MS))
                logMoveTerminal(operation, timeoutState)
                send(timeoutState)
                return@channelFlow
            }
            if (quiesceOutcome is Either.Left) {
                val failedState = StorageMoveUiState.Failed(quiesceOutcome.value)
                logMoveTerminal(operation, failedState)
                send(failedState)
                return@channelFlow
            }

            // Steps 3-7: the closed-driver region. Every exit path — happy, copy failure,
            // verification failure, or cancellation — reopens the driver (step 6) before the
            // operation ends; the finally below runs under NonCancellable so it still executes
            // even if this coroutine itself has been cancelled mid-copy/verify.
            // The Right value is the actual destination root copyIntoStagingThenRepoint moved
            // files into — for StorageLocation.AppOwned this is only known after
            // resolveDestinationRoot() allocates it (FileSystem.newAppOwnedGraphPath() returns a
            // fresh, non-deterministic path on every call), so it must be threaded out and reused
            // rather than re-derived at step 7 below.
            var copyOutcome: Either<DomainError.StorageError, String> =
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
                    val successfulOutcome = copyOutcome
                    if (reopenedRepositorySet != null && successfulOutcome is Either.Right) {
                        // GraphInfo.path is the registry field GraphManager actually uses to open
                        // this graph's content (App.kt's currentGraphPath init, FilePathRootMigration,
                        // this coordinator's own resolveSourceRoot for AppOwned) — onGraphLocationDetermined
                        // alone only records storage_locations' kind/uri metadata and never touches it,
                        // so without this call a "successful" relocate would leave the app reading/
                        // writing the graph at its old path while the new copy sits untouched.
                        graphManager.updateGraphContentPath(graphId, successfulOutcome.value)
                        graphManager.onGraphLocationDetermined(graphIdValue, operation.destination)

                        // Epic 5.2: release the source's persisted access grant (e.g. a SAF tree
                        // URI permission) now that the copy is verified, repointed, reopened, and
                        // persisted. `deleteSourceAfterVerify` (StorageMoveOperation.Relocate) has
                        // no reader anywhere in this codebase today — no call site ever passes
                        // `true` — so there is no "confirmed cleanup" step to hook this to yet.
                        // Releasing here instead: the app will never touch operation.source via
                        // this graph again regardless of that flag, so the OS-level grant is safe
                        // to drop now rather than waiting on cleanup logic that doesn't exist.
                        quiesceStrategy.releaseSourceGrant(operation.source)
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
            logMoveTerminal(operation, terminalState)
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
     * Runs [operation] end to end, per Story 4.1.1's acceptance criteria — the `Link` sibling to
     * [relocate]. Deliberately **not** a branch inside [relocate]: a `Link` operation never closes
     * the driver, never copies via [copyAndVerifyStep]/[BulkCopyVerifier], and never repoints
     * [GraphManager]'s content path — OPFS stays the one live copy of the graph for the operation's
     * entire duration, with [hostLinkStep] only attaching an additional continuous mirror alongside
     * it (`research/architecture.md` §2's `LinkEstablished` transition). Keeping this as a separate
     * function, rather than threading a `when (operation)` through [relocate]'s closed-driver
     * `try`/`finally` region, is what keeps that safety-critical region's sequencing exactly as it
     * was before this Epic — see this class's own doc comment on why that region is delicate.
     *
     * State sequence: [StorageMoveUiState.Quiescing] → [StorageMoveUiState.Verifying] (standing in
     * for [hostLinkStep]'s own picker-then-reconcile walk, which has no incremental progress
     * callback to drive [StorageMoveUiState.Copying] from) → [StorageMoveUiState.Summary] on
     * success or [StorageMoveUiState.Failed] otherwise. Never [StorageMoveUiState.ReopenFailed] —
     * there is no reopen step, since the driver was never closed.
     */
    fun link(operation: StorageMoveOperation.Link): Flow<StorageMoveUiState> = channelFlow {
        val graphIdValue = operation.graphId
        val step = hostLinkStep

        logMoveStarted(operation)

        if (step == null) {
            val unsupportedState = StorageMoveUiState.Failed(
                DomainError.StorageError.DestinationNotWritable(
                    "Linking a folder is not supported on this platform/graph",
                ),
            )
            logMoveTerminal(operation, unsupportedState)
            send(unsupportedState)
            return@channelFlow
        }

        MoveInProgressFlag.setMoveInProgress(graphIdValue, true)
        try {
            send(StorageMoveUiState.Quiescing)

            val quiesceOutcome: Either<DomainError.StorageError, Unit>? =
                withTimeoutOrNull(QUIESCE_TIMEOUT_MS) { quiesceStrategy.quiesce(operation) }
            if (quiesceOutcome == null) {
                val timeoutState = StorageMoveUiState.Failed(DomainError.StorageError.QuiesceTimedOut(QUIESCE_TIMEOUT_MS))
                logMoveTerminal(operation, timeoutState)
                send(timeoutState)
                return@channelFlow
            }
            if (quiesceOutcome is Either.Left) {
                val failedState = StorageMoveUiState.Failed(quiesceOutcome.value)
                logMoveTerminal(operation, failedState)
                send(failedState)
                return@channelFlow
            }

            attachHostLink(step, operation)
        } finally {
            withContext(NonCancellable) {
                quiesceStrategy.release(operation)
                MoveInProgressFlag.setMoveInProgress(graphIdValue, false)
            }
        }
    }

    /**
     * Resolves [operation]'s source root, invokes [step], and — on success — persists the new
     * [StorageLocation] via [GraphManager.onGraphLocationDetermined]. Extracted from [link] purely
     * to keep that function's own body short; not reused anywhere else.
     */
    private suspend fun ProducerScope<StorageMoveUiState>.attachHostLink(
        step: HostLinkStep,
        operation: StorageMoveOperation.Link,
    ) {
        // Reuses resolveSourceRoot unchanged — the same AppOwned-via-GraphManager lookup
        // relocate() already depends on, not new/duplicated logic.
        val sourceRoot = when (val resolved = resolveSourceRoot(operation.source)) {
            is Either.Left -> {
                val failedState = StorageMoveUiState.Failed(resolved.value)
                logMoveTerminal(operation, failedState)
                send(failedState)
                return
            }
            is Either.Right -> resolved.value
        }

        send(StorageMoveUiState.Verifying)
        when (val linkOutcome = withContext(PlatformDispatcher.IO) { step.link(sourceRoot, operation.destination) }) {
            is Either.Left -> {
                val failedState = StorageMoveUiState.Failed(linkOutcome.value)
                logMoveTerminal(operation, failedState)
                send(failedState)
            }
            is Either.Right -> {
                // No updateGraphContentPath call, unlike relocate()'s step 7 — OPFS remains the
                // graph's content path; only storage_locations' kind/uri metadata changes.
                graphManager.onGraphLocationDetermined(operation.graphId, operation.destination)
                logMoveTerminal(operation, StorageMoveUiState.Summary)
                send(StorageMoveUiState.Summary)
            }
        }
    }

    /**
     * Steps 4-5: copy+verify into the staging directory, then atomically repoint staging to the
     * final destination. The untouched source is only ever read here, never written. Returns the
     * resolved destination root on success — the single source of truth for where the graph's
     * content actually now lives, since [StorageLocation.AppOwned] has no path of its own (it is
     * allocated fresh, once, inside [resolveDestinationRoot]).
     */
    private suspend fun ProducerScope<StorageMoveUiState>.copyIntoStagingThenRepoint(
        operation: StorageMoveOperation.Relocate,
    ): Either<DomainError.StorageError, String> = withContext(PlatformDispatcher.IO) {
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
        logMoveVerified(operation, copyResult)
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
        AtomicFileRelocationStep.relocate(fileSystem, moves).map { destinationRoot }
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
