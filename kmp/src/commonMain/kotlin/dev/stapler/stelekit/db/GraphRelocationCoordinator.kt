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
import kotlinx.coroutines.CancellationException
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
 * 4.1.1's acceptance criteria) rather than reimplementing a parallel connect flow. Android wires
 * `createAndroidHostLinkStep()` (Epic 4.2, Story 4.2.1) — for a git-cloned graph,
 * `GitShadowWorktree`'s write-back-to-SAF cache mode is already running invisibly, so that step
 * does no new work; it only lets [link] reach its happy path instead of always failing fast with
 * [DomainError.StorageError.DestinationNotWritable]. `null` (Desktop/iOS, or a host that hasn't
 * wired one) means this platform/graph has no Link implementation at all.
 */
fun interface HostLinkStep {
    suspend fun link(existingOpfsPath: String, destination: StorageLocation): Either<DomainError.StorageError, Unit>

    /**
     * Whether a successful [link] should repoint `storage_locations` to the operation's
     * destination (via [GraphManager.onGraphLocationDetermined]). Web's Link genuinely adopts
     * [StorageLocation.HostFolder] as the new location of record, so this defaults to `true`.
     * Android's Link (Story 4.2.1) is confirmatory only — "Link never repoints the location of
     * record — only Relocate does" (plan.md) — so `createAndroidHostLinkStep()` overrides this to
     * `false`.
     */
    val persistsDestinationOnSuccess: Boolean get() = true
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
    // Nullable constructor-only parameter (not a stored property) so its real default — built
    // below in the class body — can reference [insufficientSpaceCheck], a *later* constructor
    // parameter; a default expression here can't forward-reference it directly. Kept at this
    // position (4th) so every existing positional call site (`GraphRelocationCoordinator(gm, fs,
    // quiesce, someStep)`) keeps binding a real [CopyAndVerifyStep] to this parameter unchanged.
    copyAndVerifyStep: CopyAndVerifyStep? = null,
    /** See [HostLinkStep]'s doc comment. `null` (the default) means [link] always fails fast with
     * [DomainError.StorageError.DestinationNotWritable] — Desktop/iOS, or a host that hasn't wired
     * a platform [HostLinkStep] yet. */
    private val hostLinkStep: HostLinkStep? = null,
    /**
     * MAJOR finding (PR #327 review): threads a real pre-flight free-space check into the default
     * [BulkCopyVerifier] below — previously always [InsufficientSpaceCheck.NONE], so a relocate
     * could start copying into a destination with no room. `null`-equivalent default
     * [InsufficientSpaceCheck.NONE] (Desktop/iOS, or before a host wires one) matches
     * [hostLinkStep]'s "off unless a platform explicitly supplies one" convention.
     * Android/Web wire `AndroidInsufficientSpaceCheck()`/`WasmJsInsufficientSpaceCheck()` from the
     * composition root (`MainActivity.kt`/`browser/Main.kt`, via
     * [dev.stapler.stelekit.ui.StelekitAppPlatformIntegrations.insufficientSpaceCheck]).
     */
    private val insufficientSpaceCheck: InsufficientSpaceCheck = InsufficientSpaceCheck.NONE,
    /**
     * BLOCKER 1 fix (PR #327 review): flushes `GraphWriter`'s pending 500ms-debounced page saves
     * before anything else in [relocate]/[link] — including before [MoveInProgressFlag] is even
     * set — so an edit made just before the user clicks "Relocate"/"Link" is captured on disk
     * rather than lost or split between the old and new locations. `null` (the default) is a
     * no-op, matching [hostLinkStep]'s established "off unless a platform wires one" pattern.
     * Wired from App.kt's composition root to `{ graphWriter.flush() }`.
     */
    private val flushPendingSaves: (suspend () -> Unit)? = null,
) {
    private val logger = Logger("GraphRelocationCoordinator")

    // A git-cloned graph's history lives entirely in a root-level .git directory that
    // listFilesRecursiveWithModTimes's shared default (FileSystem.kt) deliberately excludes for
    // Android's shadow-worktree mirror — copyAndVerifyPaths's includeGitDirectory opt-in is what
    // puts .git back on the same real per-file SHA-256 verify BulkCopyVerifier already gives
    // markdown, closing the silent-history-loss bug a plain relocate used to hit.
    private val copyAndVerifyStep: CopyAndVerifyStep = copyAndVerifyStep ?: CopyAndVerifyStep { source, destination, onProgress ->
        val includeGitDirectory = fileSystem.directoryExists("$source/.git")
        BulkCopyVerifier(fileSystem, insufficientSpaceCheck)
            .copyAndVerifyPaths(source, destination, onProgress, includeGitDirectory)
    }

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

        // BLOCKER 2 fix (PR #327 review): reject immediately if a relocate/link is already
        // in-flight for this graph — the very first thing this function does, ahead of even the
        // flush below, so two concurrent calls for the same graph can never both proceed past this
        // point. Closes real corruption paths: RelocationStagingDirectory.stagingPath() is keyed
        // only by graphId (two concurrent relocates would share one staging dir), and
        // WasmJsGraphMoveQuiesceStrategy.quiesce() would otherwise overwrite/leak the first
        // operation's held WebLock.
        if (MoveInProgressFlag.isMoveInProgress(graphIdValue)) {
            val inFlightState = StorageMoveUiState.Failed(
                DomainError.StorageError.SourceInFlight(
                    "Another storage move is already in progress for graph $graphIdValue",
                ),
            )
            logMoveTerminal(operation, inFlightState)
            send(inFlightState)
            return@channelFlow
        }

        // BLOCKER 1 fix (PR #327 review): flush GraphWriter's pending 500ms-debounced saves before
        // anything else — an edit made just before the user clicks "Relocate" must be captured on
        // disk before quiesce/copy begins, or it can be lost or split between the old and new
        // locations.
        flushPendingSaves?.invoke()

        // Set once, read from both the explicit pre-send release below and the outer `finally` —
        // lets a normal (non-cancelled) exit release the quiesce lock and clear MoveInProgressFlag
        // BEFORE its terminal state is sent, so a collector reacting to that terminal state can
        // never observe the flag still true; `finally` only re-runs the release if cancellation
        // skipped the normal exit path entirely.
        var released = false
        suspend fun releaseOnce() {
            if (released) return
            released = true
            quiesceStrategy.release(operation)
            MoveInProgressFlag.setMoveInProgress(graphIdValue, false)
        }

        logMoveStarted(operation)
        MoveInProgressFlag.setMoveInProgress(graphIdValue, true)
        try {
            send(StorageMoveUiState.Quiescing)

            // Steps 1-2. Bounded so a stuck drain surfaces as a visible failure instead of an
            // indefinite spinner (this codebase's own documented "silent indefinite hang" class).
            val quiesceOutcome: Either<DomainError.StorageError, Unit>? =
                withTimeoutOrNull(QUIESCE_TIMEOUT_MS) {
                    quiesceCatchingThrowable(operation, graphIdValue, context = "relocate")
                }

            if (quiesceOutcome == null) {
                // The driver was never closed on this path — no reopen call at all.
                val timeoutState = StorageMoveUiState.Failed(DomainError.StorageError.QuiesceTimedOut(QUIESCE_TIMEOUT_MS))
                logMoveTerminal(operation, timeoutState)
                releaseOnce()
                send(timeoutState)
                return@channelFlow
            }
            if (quiesceOutcome is Either.Left) {
                val failedState = StorageMoveUiState.Failed(quiesceOutcome.value)
                logMoveTerminal(operation, failedState)
                releaseOnce()
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
                // Delegates to reopenAndRepersistAfterRelocate() rather than inlining a try/catch
                // here: this whole block already runs under NonCancellable specifically so cleanup
                // completes even when the coroutine was cancelled mid-copy/verify, and that helper's
                // own try/catch rethrows CancellationException while logging-and-swallowing anything
                // else — but detekt's ThrowingExceptionFromFinally flags ANY `throw` lexically inside
                // a `finally` block, deliberate rethrow included. Moving the try/catch/throw into a
                // separate named function (called from here, not inlined) keeps the exact same
                // runtime behavior — the call still throws out of this `finally` on cancellation,
                // exactly as the inlined version did — while satisfying the linter honestly instead
                // of suppressing it.
                withContext(NonCancellable) {
                    reopenedRepositorySet = reopenAndRepersistAfterRelocate(operation, graphId, graphIdValue, copyOutcome)
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
            releaseOnce()
            send(terminalState)
        } finally {
            // Step 8, shared by every exit path above (success, ordinary failure, quiesce
            // timeout/failure, or cancellation at any point) — under NonCancellable so it still
            // runs when this coroutine itself has been cancelled. Only re-runs the release when
            // the normal-exit `releaseOnce()` calls above never got a chance to (i.e. this
            // coroutine was cancelled before reaching one) — see `released`'s doc above.
            if (!released) {
                released = true
                // See the closed-driver-region finally's comment above for why this delegates to a
                // named helper instead of inlining the try/catch/rethrow here.
                withContext(NonCancellable) {
                    releaseQuiesceAndFlagLoggingErrors(operation, graphIdValue, context = "relocate")
                }
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

        // BLOCKER 2 fix (PR #327 review): same re-entrancy guard as relocate() — see its identical
        // comment for the corruption paths this closes.
        if (MoveInProgressFlag.isMoveInProgress(graphIdValue)) {
            val inFlightState = StorageMoveUiState.Failed(
                DomainError.StorageError.SourceInFlight(
                    "Another storage move is already in progress for graph $graphIdValue",
                ),
            )
            logMoveTerminal(operation, inFlightState)
            send(inFlightState)
            return@channelFlow
        }

        // BLOCKER 1 fix (PR #327 review): same pending-save flush as relocate() — see its identical
        // comment.
        flushPendingSaves?.invoke()

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

        // See relocate()'s identical `released`/`releaseOnce` doc comment — same rationale: a
        // normal exit releases the quiesce lock and clears MoveInProgressFlag BEFORE its terminal
        // state is sent, so a collector reacting to that terminal state never observes the flag
        // still true.
        var released = false
        suspend fun releaseOnce() {
            if (released) return
            released = true
            quiesceStrategy.release(operation)
            MoveInProgressFlag.setMoveInProgress(graphIdValue, false)
        }

        MoveInProgressFlag.setMoveInProgress(graphIdValue, true)
        try {
            send(StorageMoveUiState.Quiescing)

            val quiesceOutcome: Either<DomainError.StorageError, Unit>? =
                withTimeoutOrNull(QUIESCE_TIMEOUT_MS) {
                    quiesceCatchingThrowable(operation, graphIdValue, context = "link")
                }
            if (quiesceOutcome == null) {
                val timeoutState = StorageMoveUiState.Failed(DomainError.StorageError.QuiesceTimedOut(QUIESCE_TIMEOUT_MS))
                logMoveTerminal(operation, timeoutState)
                releaseOnce()
                send(timeoutState)
                return@channelFlow
            }
            if (quiesceOutcome is Either.Left) {
                val failedState = StorageMoveUiState.Failed(quiesceOutcome.value)
                logMoveTerminal(operation, failedState)
                releaseOnce()
                send(failedState)
                return@channelFlow
            }

            attachHostLink(step, operation, ::releaseOnce)
        } finally {
            if (!released) {
                released = true
                // See relocate()'s closed-driver-region finally comment for why this delegates to a
                // named helper instead of inlining the try/catch/rethrow here.
                withContext(NonCancellable) {
                    releaseQuiesceAndFlagLoggingErrors(operation, graphIdValue, context = "link")
                }
            }
        }
    }

    /**
     * Resolves [operation]'s source root, invokes [step], and — on success — persists the new
     * [StorageLocation] via [GraphManager.onGraphLocationDetermined]. Extracted from [link] purely
     * to keep that function's own body short; not reused anywhere else. [releaseOnce] is [link]'s
     * quiesce-release/flag-clear closure — called before every terminal `send` here too, for the
     * same "cleanup before terminal state" reason [link] itself does.
     */
    private suspend fun ProducerScope<StorageMoveUiState>.attachHostLink(
        step: HostLinkStep,
        operation: StorageMoveOperation.Link,
        releaseOnce: suspend () -> Unit,
    ) {
        // Reuses resolveSourceRoot unchanged — the same AppOwned-via-GraphManager lookup
        // relocate() already depends on, not new/duplicated logic.
        val sourceRoot = when (val resolved = resolveSourceRoot(operation.source)) {
            is Either.Left -> {
                val failedState = StorageMoveUiState.Failed(resolved.value)
                logMoveTerminal(operation, failedState)
                releaseOnce()
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
                releaseOnce()
                send(failedState)
            }
            is Either.Right -> {
                // No updateGraphContentPath call, unlike relocate()'s step 7 — OPFS remains the
                // graph's content path. Repointing storage_locations' kind/uri metadata is itself
                // conditional on step.persistsDestinationOnSuccess — see that property's doc for
                // why Android's step opts out (Story 4.2.1: "Link never repoints the location of
                // record — only Relocate does").
                if (step.persistsDestinationOnSuccess) {
                    graphManager.onGraphLocationDetermined(operation.graphId, operation.destination).onLeft {
                        logger.warn("link: failed to persist storage_locations for graph ${operation.graphId} after a successful link: $it")
                    }
                }
                logMoveTerminal(operation, StorageMoveUiState.Summary)
                releaseOnce()
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
        val relocateResult = AtomicFileRelocationStep.relocate(fileSystem, moves)
        if (relocateResult is Either.Right) {
            // AtomicFileRelocationStep only moves report.verifiedPaths — the staging directory's
            // own .marker file (RelocationStagingDirectory.writeMarker) is never one of those
            // paths, so without this the now-empty staging directory (bar its marker) would
            // otherwise sit orphaned until RelocationStagingDirectory.sweep()'s 7-day grace period
            // next runs. deleteFile mirrors sweep()'s own precedent for deleting a directory path.
            fileSystem.deleteFile(stagingPath)
        }
        relocateResult.map { destinationRoot }
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

    /**
     * The reopen-and-repersist cleanup that [relocate]'s closed-driver-region `finally` runs under
     * `NonCancellable`. Extracted to a named function (rather than inlined in that `finally` block)
     * solely so its `catch (e: CancellationException) { throw e }` rethrow isn't lexically inside a
     * `finally` — detekt's `ThrowingExceptionFromFinally` flags any `throw` there, deliberate or
     * not. Calling this function from `finally` still lets a thrown `CancellationException` (or any
     * other `Throwable`, for that matter) propagate out of the call expression and thus out of the
     * `finally` block exactly as the original inlined `try`/`catch` did — this is a pure syntactic
     * restructure, not a behavior change.
     *
     * Mirrors the original inlined logic exactly, including updating a *local* `reopenedRepositorySet`
     * that is returned (rather than the outer one) so that a `Throwable` thrown after the reopen
     * itself succeeded (e.g. from `updateGraphContentPath`/`onGraphLocationDetermined`/
     * `releaseSourceGrant`) still reports the graph as reopened — only a failure in `switchGraph`/
     * `awaitPendingMigration` themselves should surface as [DomainError.StorageError.ReopenFailed].
     */
    private suspend fun reopenAndRepersistAfterRelocate(
        operation: StorageMoveOperation.Relocate,
        graphId: GraphId,
        graphIdValue: String,
        copyOutcome: Either<DomainError.StorageError, String>,
    ): RepositorySet? {
        var reopenedRepositorySet: RepositorySet? = null
        try {
            // Reopen-and-confirm is one inseparable pair — switchGraph() alone only schedules the
            // reopen; awaitPendingMigration() is what actually waits for it.
            graphManager.switchGraph(graphId, forceReinit = true)
            reopenedRepositorySet = graphManager.awaitPendingMigration()

            // Step 7: only on the happy path, and only once reopen is confirmed, is the new
            // location persisted — never through a closed/unconfirmed connection.
            val successfulOutcome = copyOutcome
            if (reopenedRepositorySet != null && successfulOutcome is Either.Right) {
                // GraphInfo.path is the registry field GraphManager actually uses to open this
                // graph's content (App.kt's currentGraphPath init, FilePathRootMigration, this
                // coordinator's own resolveSourceRoot for AppOwned) — onGraphLocationDetermined
                // alone only records storage_locations' kind/uri metadata and never touches it, so
                // without this call a "successful" relocate would leave the app reading/writing the
                // graph at its old path while the new copy sits untouched.
                graphManager.updateGraphContentPath(graphId, successfulOutcome.value)
                graphManager.onGraphLocationDetermined(graphIdValue, operation.destination).onLeft {
                    logger.warn("relocate: failed to persist storage_locations for graph $graphIdValue after a successful move: $it")
                }

                // Epic 5.2: release the source's persisted access grant (e.g. a SAF tree URI
                // permission) now that the copy is verified, repointed, reopened, and persisted.
                // `deleteSourceAfterVerify` (StorageMoveOperation.Relocate) has no reader anywhere
                // in this codebase today — no call site ever passes `true` — so there is no
                // "confirmed cleanup" step to hook this to yet. Releasing here instead: the app
                // will never touch operation.source via this graph again regardless of that flag,
                // so the OS-level grant is safe to drop now rather than waiting on cleanup logic
                // that doesn't exist.
                quiesceStrategy.releaseSourceGrant(operation.source)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error("relocate: unexpected failure during reopen/repersist cleanup for graph $graphIdValue", e)
        }
        return reopenedRepositorySet
    }

    /**
     * The quiesce-release/flag-clear cleanup shared by [relocate]'s and [link]'s outer `finally`
     * blocks (both run it under `NonCancellable` when cancellation skipped their normal-exit
     * `releaseOnce()`). Extracted to a named function for the same
     * `ThrowingExceptionFromFinally`-avoidance reason as [reopenAndRepersistAfterRelocate] — see its
     * doc comment.
     */
    private suspend fun releaseQuiesceAndFlagLoggingErrors(
        operation: StorageMoveOperation,
        graphIdValue: String,
        context: String,
    ) {
        try {
            quiesceStrategy.release(operation)
            MoveInProgressFlag.setMoveInProgress(graphIdValue, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error("$context: unexpected failure releasing quiesce/flag for graph $graphIdValue", e)
        }
    }

    /**
     * BLOCKER 3 fix (PR #327 review): [quiesceStrategy] is a `commonMain` port whose real
     * implementations (`AndroidGraphMoveQuiesceStrategy`, `WasmJsGraphMoveQuiesceStrategy`) can
     * throw a raw `Throwable` rather than returning `Either.Left` — [relocate]/[link] previously
     * called [GraphMoveQuiesceStrategy.quiesce] with no try/catch at all, so such a throw would
     * propagate out of the `channelFlow` uncaught. Converts any non-cancellation `Throwable` into
     * a [DomainError.StorageError.RelocationFailed] instead, so it surfaces as a normal
     * [StorageMoveUiState.Failed] emission rather than crashing whatever scope collects this flow
     * (this codebase's own documented "uncaught coroutine Throwable kills the Android process"
     * class).
     */
    private suspend fun quiesceCatchingThrowable(
        operation: StorageMoveOperation,
        graphIdValue: String,
        context: String,
    ): Either<DomainError.StorageError, Unit> =
        try {
            quiesceStrategy.quiesce(operation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error("$context: quiesce threw unexpectedly for graph $graphIdValue", e)
            DomainError.StorageError.RelocationFailed(graphIdValue).left()
        }

    companion object {
        /**
         * 30s: long enough for a large `GitWriteBackQueue` drain under normal load, short enough
         * that a stuck flush surfaces as a visible failure within one user-patience window rather
         * than an indefinite spinner.
         */
        const val QUIESCE_TIMEOUT_MS = 30_000L
    }
}
