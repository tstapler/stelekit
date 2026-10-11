// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.stapler.stelekit.db.GraphRelocationCoordinator
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.StorageMoveUiState
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * Owns "Move storage location…" (Epic 3.4/4.1/4.2) for [GraphContent]: the
 * [GraphRelocationCoordinator], the in-flight [StorageMoveUiState], and the callbacks wired to
 * the sidebar's GraphSwitcher / FolderSyncSettings, and
 * [dev.stapler.stelekit.ui.components.StorageMoveProgressDialog].
 */
internal class GraphContentStorageMoveController(
    val stateState: MutableState<StorageMoveUiState?>,
    val graphNameState: MutableState<String>,
    val onStorageLocationChoose: (StorageMoveOperation) -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onAcknowledge: () -> Unit,
)

/** The mutable state backing a [GraphContentStorageMoveController] (bundled for parameter-count relief). */
private class StorageMoveMutableState(
    val stateState: MutableState<StorageMoveUiState?> = mutableStateOf(null),
    val graphNameState: MutableState<String> = mutableStateOf("this graph"),
    val jobState: MutableState<Job?> = mutableStateOf(null),
    val lastOperationState: MutableState<StorageMoveOperation?> = mutableStateOf(null),
)

/** Non-state collaborators [runStorageMove] needs (bundled for parameter-count relief). */
private class StorageMoveEnv(val scope: CoroutineScope, val graphContentLogger: Logger)

/**
 * Builds the composition root for "Move storage location…" — the only place a real
 * [GraphRelocationCoordinator] gets constructed, since it needs [deps]' own
 * graphManager/fileSystem plus the platform-supplied quiesce port. A null
 * `graphMoveQuiesceStrategy` (Desktop/iOS, or a host that hasn't wired one) means no coordinator
 * exists, so [GraphContentStorageMoveController.onStorageLocationChoose] falls back to a snackbar
 * instead of hanging.
 */
@Composable
internal fun rememberGraphContentStorageMoveController(
    deps: GraphContentDeps,
    graphWriter: GraphWriter,
    scope: CoroutineScope,
    graphContentLogger: Logger,
    viewModel: StelekitViewModel,
): GraphContentStorageMoveController {
    val graphManager = deps.graphManager
    val graphRelocationCoordinator = rememberGraphRelocationCoordinator(deps, graphWriter)
    val state = remember { StorageMoveMutableState() }
    val env = StorageMoveEnv(scope, graphContentLogger)

    fun startStorageMove(operation: StorageMoveOperation, graphName: String) {
        runStorageMove(graphRelocationCoordinator, state, env, operation, graphName)
    }

    // Both Sidebar's GraphSwitcher (Android) and FolderSyncSettings (Web) hand off through this
    // one callback (see GraphRelocationCoordinator.kt's own composition-root doc). A null
    // coordinator (no graphMoveQuiesceStrategy wired — Desktop/iOS today) still can't run either
    // operation, so it falls back to a snackbar instead of hanging.
    val onStorageLocationChoose: (StorageMoveOperation) -> Unit = { operation ->
        if (graphRelocationCoordinator == null) {
            viewModel.sendSnackbar("Moving storage location isn't available on this platform yet")
        } else {
            val graphName = graphManager.getGraphInfo(GraphId(operation.graphId))?.displayName ?: "this graph"
            startStorageMove(operation, graphName)
        }
    }

    return GraphContentStorageMoveController(
        stateState = state.stateState,
        graphNameState = state.graphNameState,
        onStorageLocationChoose = onStorageLocationChoose,
        onCancel = {
            state.jobState.value?.cancel()
            state.jobState.value = null
            state.stateState.value = null
        },
        onRetry = { state.lastOperationState.value?.let { startStorageMove(it, state.graphNameState.value) } },
        onAcknowledge = { state.stateState.value = null },
    )
}

@Composable
private fun rememberGraphRelocationCoordinator(deps: GraphContentDeps, graphWriter: GraphWriter): GraphRelocationCoordinator? {
    val graphManager = deps.graphManager
    val fileSystem = deps.fileSystem
    val platform = deps.platformIntegrations
    return remember(
        graphManager, fileSystem, platform.graphMoveQuiesceStrategy, platform.hostLinkStep,
        platform.insufficientSpaceCheck, graphWriter,
    ) {
        platform.graphMoveQuiesceStrategy?.let {
            GraphRelocationCoordinator(
                graphManager,
                fileSystem,
                it,
                hostLinkStep = platform.hostLinkStep,
                // MAJOR finding (PR #327 review): wires the real per-platform pre-flight
                // free-space check when one is supplied; InsufficientSpaceCheck.NONE (never
                // checks) otherwise — see StelekitAppPlatformIntegrations.insufficientSpaceCheck's
                // doc for why this is the composition root for that seam.
                insufficientSpaceCheck = platform.insufficientSpaceCheck ?: dev.stapler.stelekit.db.InsufficientSpaceCheck.NONE,
                // BLOCKER 1 fix (PR #327 review): flushes this graph's GraphWriter — the same
                // instance StelekitViewModel/GitSyncService above already write through — before
                // relocate()/link() quiesces/closes the driver, so a pending 500ms-debounced save
                // is captured on disk first instead of being lost or split across the move.
                flushPendingSaves = { graphWriter.flush() },
            )
        }
    }
}

/**
 * Epic 4.1: dispatches to the coordinator's Relocate or Link entry point — see
 * [GraphRelocationCoordinator.link]'s doc comment for why Link is a separate function rather than
 * a branch inside relocate() itself. A null hostLinkStep (every non-Web platform) still reaches
 * coordinator.link(), which fails fast with a real Failed(DestinationNotWritable) state shown in
 * StorageMoveProgressDialog — a more honest UI than a generic snackbar.
 */
private fun runStorageMove(
    graphRelocationCoordinator: GraphRelocationCoordinator?,
    state: StorageMoveMutableState,
    env: StorageMoveEnv,
    operation: StorageMoveOperation,
    graphName: String,
) {
    val coordinator = graphRelocationCoordinator ?: return
    state.lastOperationState.value = operation
    state.graphNameState.value = graphName
    val previousJob = state.jobState.value
    val states = when (operation) {
        is StorageMoveOperation.Relocate -> coordinator.relocate(operation)
        is StorageMoveOperation.Link -> coordinator.link(operation)
    }
    state.jobState.value = env.scope.launch {
        // BLOCKER 2 fix (PR #327 review): await the previous job's full cancellation —
        // including GraphRelocationCoordinator's own NonCancellable cleanup (reopen, quiesce
        // release, MoveInProgressFlag clear) — before this job starts collecting, so a stale
        // in-flight relocate/link (e.g. WasmJsGraphMoveQuiesceStrategy's still-mid-flight
        // quiesce()) can never race a freshly-started one. The coordinator's own
        // MoveInProgressFlag check (relocate()/link()'s first step) is the authoritative guard
        // for genuinely concurrent callers from different entry points; this closes the common
        // rapid-retry/double-click case cleanly instead of relying on that race alone.
        previousJob?.cancelAndJoin()
        try {
            states.collect { s -> state.stateState.value = s }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // BLOCKER 3 fix (PR #327 review): mirrors the camera-capture scope.launch pattern
            // below — an uncaught Throwable on this plain rememberCoroutineScope() (no
            // CoroutineExceptionHandler) would otherwise kill the Android process even though
            // GraphRelocationCoordinator itself now guards its own quiesce call.
            env.graphContentLogger.error("Storage move collection crashed: ${e.message}", e)
            state.stateState.value = StorageMoveUiState.Failed(
                dev.stapler.stelekit.error.DomainError.StorageError.RelocationFailed(operation.graphId),
            )
        }
    }
}
