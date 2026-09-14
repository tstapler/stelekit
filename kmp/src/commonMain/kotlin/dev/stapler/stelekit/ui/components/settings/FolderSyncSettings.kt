// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.ui.components.StorageMoveChoiceDialog
import dev.stapler.stelekit.ui.components.StorageMoveConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Task 3.1.1b: "Enable live folder sync" affordance for a graph that has never had live sync
 * connected — Surface 7 in design/ux.md. Shown only when [supportsNativeDirectoryPicker] and
 * [hostAccessState] is [HostAccessState.NotApplicable] (never shown once already connected, and
 * never shown at all on browsers without the File System Access API — the established "don't show
 * a broken affordance" convention from Onboarding).
 *
 * [onConnect] is a caller-supplied suspend lambda that performs the real
 * `showDirectoryPicker → HostDirectorySync.connectHostDirectory → runHostReconciliation` sequence
 * and returns the terminal [ReconciliationUiState] ([ReconciliationUiState.Summary] or
 * [ReconciliationUiState.Failed] — never [ReconciliationUiState.Connecting], which this composable
 * sets locally the instant the button is clicked, before awaiting [onConnect]). This shape
 * deliberately differs from plan.md Task 3.1.1b's literal `suspend () -> Unit` signature: Task
 * 3.1.2b additionally requires the four per-category reconciliation counts to reach this
 * composable, and this codebase has no existing precedent for a one-shot async operation result
 * threaded through a continuous `StateFlow` (the `localChangesCountFlow` precedent is for
 * continuously-updating data, not a single operation's outcome) — a return-value-carrying
 * callback is the smallest change that satisfies both tasks without inventing new state-flow
 * plumbing. See this dispatch's final report for the full rationale.
 */
@Composable
fun FolderSyncSettings(
    hostAccessState: HostAccessState,
    supportsNativeDirectoryPicker: Boolean,
    onConnect: suspend () -> ReconciliationUiState,
    modifier: Modifier = Modifier,
    // Story 3.3.3: Web equivalent of Android's Surface 4 "Move storage location…" entry point
    // (Story 3.2.2). `null` hides the entry entirely (same "don't show a broken affordance"
    // convention as this composable's other optional entry points). Non-null, the caller supplies
    // a suspend lambda that performs `StorageLocationResolver.resolveOrBackfill(graphId)` — see
    // [MoveStorageLocationSection]'s doc comment for what happens with the result.
    onMoveStorageLocation: (suspend () -> StorageLocation)? = null,
    /** Epic 3.4: display name for [StorageMoveChoiceDialog]/[StorageMoveConfirmDialog] — no
     * graph name flows into this composable today (its `SettingsDialog` caller has none either),
     * so this defaults to a generic phrase rather than blocking the dialogs on that composition-
     * root gap. */
    graphName: String = "this graph",
    /** Epic 3.4: fires once the user confirms in [StorageMoveConfirmDialog] — see
     * [MoveStorageLocationSection]'s doc comment for which direction this covers today. */
    onStorageLocationChosen: (operation: StorageMoveOperation) -> Unit = {},
    /**
     * Epic 4.1 (Task 4.1.2c): detaches a currently-linked host folder while keeping the graph on
     * OPFS — should invoke `HostDirectorySync.unlinkHostDirectory()` (plus persisting the
     * resulting [dev.stapler.stelekit.model.StorageLocation.AppOwned] row). `null` hides the
     * "Unlink folder" action entirely (same "don't show a broken affordance" convention as this
     * composable's other optional entry points). Shown whenever [hostAccessState] indicates a link
     * exists in some state (anything but [HostAccessState.NotApplicable]/[HostAccessState.Unlinked]),
     * independent of the "Enable live folder sync" gate below, which only ever applies pre-link.
     */
    onUnlink: (suspend () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<ReconciliationUiState?>(null) }
    var isUnlinking by remember { mutableStateOf(false) }

    // Independent of the "Enable live folder sync" gate below — a graph's storage location can be
    // moved whether or not live folder sync is available/connected. Suppressed only while an
    // "Enable live folder sync" reconciliation flow (uiState != null) is actively showing, so the
    // two async actions never compete for the same screen at once.
    if (onMoveStorageLocation != null && uiState == null) {
        MoveStorageLocationSection(onMoveStorageLocation, scope, modifier, graphName, onStorageLocationChosen)
    }

    // Epic 4.1 (Task 4.1.2c): the "already linked" counterpart to the "Enable live folder sync"
    // section below — that section's own early-return gate (next block) means it never renders
    // once hostAccessState leaves NotApplicable, so this is the only place a linked graph's
    // Folder Sync settings show anything at all.
    if (uiState == null && onUnlink != null &&
        hostAccessState != HostAccessState.NotApplicable && hostAccessState != HostAccessState.Unlinked
    ) {
        SettingsSection("Folder Sync") {
            Text(
                "This graph is linked to a folder on your computer. Unlinking stops syncing to " +
                    "that folder — your notes stay safely in the browser.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    isUnlinking = true
                    scope.launch {
                        try {
                            onUnlink()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logger.warn("Unlink folder failed: ${e.message}", e)
                        } finally {
                            isUnlinking = false
                        }
                    }
                },
                enabled = !isUnlinking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Unlink folder")
            }
        }
    }

    // Bug fix (code-review repair loop): this guard now runs AFTER `uiState` is read via
    // `remember`, and only applies while there is no in-progress/terminal reconciliation screen to
    // show (`uiState == null`). `connectHostDirectory` flips `hostAccessState` to `Granted` as
    // PART OF the connect flow — before the reconciliation-progress screen is naturally dismissed
    // by the user via `onDone`/`onCancel` — so a recomposition triggered while `uiState` is
    // `Connecting`/`Summary`/`Failed` must not bail out here just because `hostAccessState` has
    // already moved past `NotApplicable`. Doing so (the original bug: this check sat above the
    // `remember` line, so it ran unconditionally on every recomposition) would make the in-progress
    // or terminal reconciliation screen disappear mid-flow — exactly the scenario this composable's
    // own doc comment calls "the highest-stakes surface in the whole feature" (the UI proving the
    // Critical Finding — browser edits preserved — didn't just happen silently off-screen).
    if (uiState == null && (!supportsNativeDirectoryPicker || hostAccessState != HostAccessState.NotApplicable)) {
        return
    }

    fun startConnect() {
        uiState = ReconciliationUiState.Connecting
        scope.launch {
            uiState = try {
                onConnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ReconciliationUiState.Failed(e.message ?: "Couldn't finish comparing your files")
            }
        }
    }

    val currentUiState = uiState
    if (currentUiState != null) {
        FolderSyncReconciliationProgress(
            state = currentUiState,
            onDone = { uiState = null },
            onRetry = { startConnect() },
            onCancel = { uiState = null },
            modifier = modifier,
        )
        return
    }

    SettingsSection("Folder Sync") {
        Text(
            "This graph is stored in your browser only. You can connect it to a folder on your " +
                "computer so edits made here are written straight to your files — no export, " +
                "no git required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = ::startConnect, modifier = Modifier.fillMaxWidth()) {
            Text("Enable live folder sync")
        }
        Spacer(Modifier.height(8.dp))
        // Load-bearing reassurance copy (design/ux.md Surface 7) — directly targets the Critical
        // Finding's failure mode (silent destruction of browser-only edits on connect). Must be
        // shown verbatim, before the button is ever clicked, and must never be cut for space.
        Text(
            "Existing edits in this graph are kept — nothing is overwritten when you connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Task 3.3.3a: "Move storage location…" entry point — design/ux.md Surface 4, Web side (Android's
 * equivalent, Story 3.2.2, lives in `Sidebar.kt`'s "Edit Graph" dialog instead). Clicking calls
 * [onMoveStorageLocation] (the caller's `StorageLocationResolver.resolveOrBackfill(graphId)`) to
 * derive/persist this graph's current [StorageLocation] before anything else runs, per Story
 * 3.3.3's second acceptance criterion.
 *
 * Epic 3.4 wires [StorageMoveChoiceDialog]/[StorageMoveConfirmDialog] in for the one direction
 * this entry point can offer without a native directory picker: when the resolved source is
 * already a [StorageLocation.HostFolder] (a live-linked graph), the destination is unambiguously
 * [StorageLocation.AppOwned] — no new location needs to be chosen, so the dialogs open directly.
 *
 * **Remaining gap (still open after the App.kt composition-root wiring dispatch)**: when the
 * resolved source is [StorageLocation.AppOwned], there is still no destination to hand off to —
 * connecting a *new* host folder requires the browser's `showDirectoryPicker()`, and this
 * composable has no callback for that (unlike Android's `onBrowseRequestedForMove`, now wired in
 * `App.kt`'s `LeftSidebar` call site). Adding one here means re-opening Epic 2.1/2.3's
 * directory-connect/reconciliation picker machinery inside this settings surface — real scope
 * creep for a wiring-only dispatch — so it stays a logged no-op rather than a fabricated
 * destination. Even with a destination picker wired in, a move *from* a `HostFolder` source would
 * still fail: `GraphRelocationCoordinator` resolves `AppOwned` roots via `GraphManager`/
 * `FileSystem.newAppOwnedGraphPath()`, but `HostFolder` content lives behind an opaque
 * `FileSystemDirectoryHandle` (Web `HostDirectorySync`), not a filesystem path, and has no
 * resolution path yet — see `GraphRelocationCoordinator.hostFolderUnsupported()`.
 */
@Composable
private fun MoveStorageLocationSection(
    onMoveStorageLocation: suspend () -> StorageLocation,
    scope: CoroutineScope,
    modifier: Modifier,
    graphName: String = "this graph",
    onStorageLocationChosen: (StorageMoveOperation) -> Unit = {},
) {
    var isResolving by remember { mutableStateOf(false) }
    var choosingMoveForSource by remember { mutableStateOf<StorageLocation?>(null) }
    var confirmingMove by remember { mutableStateOf<StorageMoveOperation?>(null) }

    SettingsSection("Storage") {
        Button(
            onClick = {
                isResolving = true
                scope.launch {
                    try {
                        val location = onMoveStorageLocation()
                        if (location is StorageLocation.HostFolder) {
                            choosingMoveForSource = location
                        } else {
                            // TODO(Story 3.3.3): no destination-picker wiring yet for an AppOwned
                            // source — see this function's doc comment.
                            logger.warn(
                                "Move storage location: no destination picker wired for " +
                                    "AppOwned source (graphId=${location.graphId})",
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.warn("resolveOrBackfill failed for Move storage location: ${e.message}", e)
                    } finally {
                        isResolving = false
                    }
                }
            },
            enabled = !isResolving,
            modifier = modifier.fillMaxWidth(),
        ) {
            Text("Move storage location…")
        }
    }

    val choosingSource = choosingMoveForSource
    if (choosingSource != null) {
        val destination = StorageLocation.AppOwned(choosingSource.graphId)
        StorageMoveChoiceDialog(
            graphName = graphName,
            source = choosingSource,
            destination = destination,
            isUnlinking = true,
            onRelocateChosen = {
                confirmingMove = StorageMoveOperation.Relocate(
                    graphId = choosingSource.graphId,
                    source = choosingSource,
                    destination = destination,
                    // AC34: never auto-delete the source here either — same cleanup-prompt rule
                    // Android's Sidebar.kt wiring follows.
                    deleteSourceAfterVerify = false,
                )
                choosingMoveForSource = null
            },
            onLinkChosen = {
                confirmingMove = StorageMoveOperation.Link(
                    graphId = choosingSource.graphId,
                    source = choosingSource,
                    destination = destination,
                )
                choosingMoveForSource = null
            },
            onDismissRequest = { choosingMoveForSource = null },
        )
    }

    val confirming = confirmingMove
    if (confirming != null) {
        StorageMoveConfirmDialog(
            graphName = graphName,
            source = confirming.source,
            destination = confirming.destination,
            confirmLabel = if (confirming is StorageMoveOperation.Relocate) "Move" else "Link",
            onConfirm = {
                onStorageLocationChosen(confirming)
                confirmingMove = null
            },
            onDismissRequest = { confirmingMove = null },
        )
    }
}

private val logger = Logger("FolderSyncSettings")
