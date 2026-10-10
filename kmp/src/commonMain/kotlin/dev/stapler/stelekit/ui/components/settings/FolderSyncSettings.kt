// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Column
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
import dev.stapler.stelekit.ui.components.MutedText
import dev.stapler.stelekit.ui.components.StorageMoveChoiceDialog
import dev.stapler.stelekit.ui.components.StorageMoveConfirmDialog
import dev.stapler.stelekit.ui.components.UnifiedLocationPicker
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
    onStorageLocationChoose: (operation: StorageMoveOperation) -> Unit = {},
    /**
     * Story 3.3.3 (AppOwned→HostFolder direction): backed by [dev.stapler.stelekit.platform.FileSystem.pickHostFolderNamePreview],
     * threaded into [UnifiedLocationPicker]'s `onBrowseRequest` when the resolved source is
     * [StorageLocation.AppOwned] — see [MoveStorageLocationSection]'s doc comment. `null` (the
     * default) keeps that direction a logged no-op, so this stays backward compatible for any
     * caller that hasn't wired a picker in yet.
     */
    onBrowseRequestForMove: (suspend () -> StorageLocation?)? = null,
    /** Must run synchronously in the "Browse…" row's own click handler — same transient-user-
     * activation constraint as [UnifiedLocationPicker]'s `onBrowseClick`. */
    onBrowseClickForMove: () -> Unit = {},
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

    // Everything below emits into this single outer Column instead of directly into the caller —
    // compose-rules' MultipleEmitters check requires exactly one top-level emission source, and
    // two of these sections (the "Move storage location" entry and the "already linked" unlink
    // section right below) can legitimately render as siblings in the same composition, so this
    // isn't just a lint-satisfying wrapper. Column wraps its `content` lambda internally before
    // handing it to `Layout`, so a bare (non-local) `return` isn't legal inside it — the two
    // `return@Column`s further down only skip the rest of Column's own content, but since Column
    // is the last statement in this function that has the exact same net effect as the original
    // top-level `return`s did.
    Column(modifier = modifier) {
        // Independent of the "Enable live folder sync" gate below — a graph's storage location can
        // be moved whether or not live folder sync is available/connected. Suppressed only while an
        // "Enable live folder sync" reconciliation flow (uiState != null) is actively showing, so
        // the two async actions never compete for the same screen at once.
        if (onMoveStorageLocation != null && uiState == null) {
            MoveStorageLocationSection(
                onMoveStorageLocation = onMoveStorageLocation,
                scope = scope,
                graphName = graphName,
                onStorageLocationChoose = onStorageLocationChoose,
                supportsNativeDirectoryPicker = supportsNativeDirectoryPicker,
                onBrowseRequestForMove = onBrowseRequestForMove,
                onBrowseClickForMove = onBrowseClickForMove,
            )
        }

        // Epic 4.1 (Task 4.1.2c): the "already linked" counterpart to the "Enable live folder sync"
        // section below — that section's own early-return gate (next block) means it never renders
        // once hostAccessState leaves NotApplicable, so this is the only place a linked graph's
        // Folder Sync settings show anything at all.
        val hostIsLinked = hostAccessState != HostAccessState.NotApplicable && hostAccessState != HostAccessState.Unlinked
        if (uiState == null && onUnlink != null && hostIsLinked) {
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
        // `remember`, and only applies while there is no in-progress/terminal reconciliation screen
        // to show (`uiState == null`). `connectHostDirectory` flips `hostAccessState` to `Granted`
        // as PART OF the connect flow — before the reconciliation-progress screen is naturally
        // dismissed by the user via `onDone`/`onCancel` — so a recomposition triggered while
        // `uiState` is `Connecting`/`Summary`/`Failed` must not bail out here just because
        // `hostAccessState` has already moved past `NotApplicable`. Doing so (the original bug:
        // this check sat above the `remember` line, so it ran unconditionally on every
        // recomposition) would make the in-progress or terminal reconciliation screen disappear
        // mid-flow — exactly the scenario this composable's own doc comment calls "the
        // highest-stakes surface in the whole feature" (the UI proving the Critical Finding —
        // browser edits preserved — didn't just happen silently off-screen).
        if (uiState == null && (!supportsNativeDirectoryPicker || hostAccessState != HostAccessState.NotApplicable)) {
            return@Column
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
            )
            return@Column
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
            // Load-bearing reassurance copy (design/ux.md Surface 7) — directly targets the
            // Critical Finding's failure mode (silent destruction of browser-only edits on
            // connect). Must be shown verbatim, before the button is ever clicked, and must never
            // be cut for space.
            MutedText("Existing edits in this graph are kept — nothing is overwritten when you connect.")
        }
    }
}

/**
 * Task 3.3.3a: "Move storage location…" entry point — design/ux.md Surface 4, Web side (Android's
 * equivalent, Story 3.2.2, lives in `Sidebar.kt`'s "Edit Graph" dialog instead). Clicking calls
 * [onMoveStorageLocation] (the caller's `StorageLocationResolver.resolveOrBackfill(graphId)`) to
 * derive/persist this graph's current [StorageLocation] before anything else runs, per Story
 * 3.3.3's second acceptance criterion.
 *
 * Epic 3.4 wires [StorageMoveChoiceDialog]/[StorageMoveConfirmDialog] in for both directions.
 * When the resolved source is already a [StorageLocation.HostFolder] (a live-linked graph), the
 * destination is unambiguously [StorageLocation.AppOwned] — no new location needs to be chosen,
 * so the dialogs open directly. When the resolved source is [StorageLocation.AppOwned] and
 * [onBrowseRequestForMove] is supplied, [UnifiedLocationPicker] opens first so the user can pick
 * a real destination folder via the browser's `showDirectoryPicker()`, mirroring Android's
 * `onBrowseRequestForMove` wiring in `App.kt`'s `LeftSidebar` call site. A `null`
 * [onBrowseRequestForMove] (the default) falls back to the previous logged no-op.
 *
 * **Known remaining gap**: choosing "Relocate" for the AppOwned→HostFolder direction still fails
 * — `GraphRelocationCoordinator` resolves `AppOwned` roots via `GraphManager`/
 * `FileSystem.newAppOwnedGraphPath()`, but `HostFolder` content lives behind an opaque
 * `FileSystemDirectoryHandle` (Web `HostDirectorySync`), not a filesystem path, and has no
 * copy-based resolution path yet — see `GraphRelocationCoordinator.hostFolderUnsupported()`.
 * "Link" already works for this direction: it runs through `HostLinkStep` →
 * `HostDirectorySync.connectHostDirectory`, the same mechanism Epic 4.1 built.
 */
@Composable
private fun MoveStorageLocationSection(
    onMoveStorageLocation: suspend () -> StorageLocation,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    graphName: String = "this graph",
    onStorageLocationChoose: (StorageMoveOperation) -> Unit = {},
    supportsNativeDirectoryPicker: Boolean = false,
    onBrowseRequestForMove: (suspend () -> StorageLocation?)? = null,
    onBrowseClickForMove: () -> Unit = {},
) {
    var isResolving by remember { mutableStateOf(false) }
    var pickingDestinationForSource by remember { mutableStateOf<StorageLocation.AppOwned?>(null) }
    var choosingMoveForSource by remember { mutableStateOf<StorageLocation?>(null) }
    var choosingMoveDestination by remember { mutableStateOf<StorageLocation?>(null) }
    var confirmingMove by remember { mutableStateOf<StorageMoveOperation?>(null) }

    SettingsSection("Storage") {
        Button(
            onClick = {
                isResolving = true
                scope.launch {
                    try {
                        val location = onMoveStorageLocation()
                        when {
                            location is StorageLocation.HostFolder -> {
                                choosingMoveForSource = location
                                choosingMoveDestination = StorageLocation.AppOwned(location.graphId)
                            }
                            location is StorageLocation.AppOwned && onBrowseRequestForMove != null ->
                                pickingDestinationForSource = location
                            else ->
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

    // Story 3.3.3 (AppOwned→HostFolder direction): picks a real destination before the same
    // Relocate/Link choice below opens — see this function's doc comment.
    val pickingSource = pickingDestinationForSource
    if (pickingSource != null && onBrowseRequestForMove != null) {
        UnifiedLocationPicker(
            title = "Move \"$graphName\" to…",
            graphId = pickingSource.graphId,
            appStorageSubtitle = "Kept inside SteleKit only — not visible in your device's file manager.",
            platformCapabilities = supportsNativeDirectoryPicker,
            onBrowseClick = onBrowseClickForMove,
            onBrowseRequest = onBrowseRequestForMove,
            onConfirm = { destination ->
                choosingMoveForSource = pickingSource
                choosingMoveDestination = destination
                pickingDestinationForSource = null
            },
            onDismiss = { pickingDestinationForSource = null },
        )
    }

    val choosingSource = choosingMoveForSource
    val destination = choosingMoveDestination
    if (choosingSource != null && destination != null) {
        StorageMoveChoiceDialog(
            graphName = graphName,
            source = choosingSource,
            destination = destination,
            // True only for the HostFolder→AppOwned direction (see StorageMoveChoiceDialog's own
            // doc) — AppOwned→HostFolder is establishing a new link, not reversing one.
            isUnlinking = choosingSource is StorageLocation.HostFolder,
            onRelocateChoose = {
                confirmingMove = StorageMoveOperation.Relocate(
                    graphId = choosingSource.graphId,
                    source = choosingSource,
                    destination = destination,
                    // AC34: never auto-delete the source here either — same cleanup-prompt rule
                    // Android's Sidebar.kt wiring follows.
                    deleteSourceAfterVerify = false,
                )
                choosingMoveForSource = null
                choosingMoveDestination = null
            },
            onLinkChoose = {
                confirmingMove = StorageMoveOperation.Link(
                    graphId = choosingSource.graphId,
                    source = choosingSource,
                    destination = destination,
                )
                choosingMoveForSource = null
                choosingMoveDestination = null
            },
            onDismissRequest = {
                choosingMoveForSource = null
                choosingMoveDestination = null
            },
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
                onStorageLocationChoose(confirming)
                confirmingMove = null
            },
            onDismissRequest = { confirmingMove = null },
        )
    }
}

private val logger = Logger("FolderSyncSettings")
