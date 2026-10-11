// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId

/**
 * UI-facing state for an in-flight [GraphRelocationCoordinator.relocate] run (Story 3.1.5,
 * `design/ux.md` Surfaces 6-9).
 *
 * [ReopenFailed] is a structural **sibling** of [Failed], not one of its cases — a `when` over
 * [StorageMoveUiState] is therefore forced to handle the two separately. This matters because a
 * reopen failure means the coordinator cannot currently prove the graph is editable at all, unlike
 * every other failure kind (where the source is provably untouched and the driver is provably
 * reopened before `Failed` is ever shown) — the UI must not offer `Failed`'s ordinary
 * "Retry"/"Cancel" affordances or its "your files were not touched" reassurance for this state.
 */
sealed interface StorageMoveUiState {
    /** Steps 1-2: waiting on [GraphMoveQuiesceStrategy.quiesce]. */
    data object Quiescing : StorageMoveUiState

    /** Step 4: bulk-copying into the staging directory. [total] is 0 until the first batch reports. */
    data class Copying(val count: Int, val total: Int) : StorageMoveUiState

    /**
     * Content-hash verification, rendered from inside the same copy-and-verify call [Copying] is
     * (Story 3.1.1's `BulkCopyVerifier` interleaves copy and verify per file) — a UI-level
     * distinction, not a separately cancellable coordinator phase.
     */
    data object Verifying : StorageMoveUiState

    /** Happy path: copy, verify, and repoint all succeeded and the driver is confirmed reopened. */
    data object Summary : StorageMoveUiState

    /**
     * An ordinary failure (quiesce timeout, copy failure, verification failure) — emitted only
     * once the driver has been reopened and confirmed usable again, so this state is never shown
     * while the graph is uneditable.
     */
    data class Failed(val reason: DomainError.StorageError) : StorageMoveUiState

    /**
     * The driver's post-relocate reopen itself failed — [GraphManager.awaitPendingMigration]
     * returned `null`. Worse than [Failed]: the coordinator cannot confirm the graph is editable,
     * so no "Retry" affordance and no "untouched" reassurance are offered for this state.
     */
    data class ReopenFailed(val graphId: GraphId, val cause: DomainError.StorageError.ReopenFailed) : StorageMoveUiState
}
