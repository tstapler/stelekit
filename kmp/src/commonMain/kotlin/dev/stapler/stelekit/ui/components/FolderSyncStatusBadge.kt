// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.HostAccessState

/** Amber warning treatment, matching [Sidebar.kt]'s `DiskConflictWarningColor`/[SyncStatusBadge]'s
 * `ConflictPending` tint exactly, for visual consistency between all "needs attention" badges. */
private val FolderSyncWarningColor = Color(0xFFF59E0B)

/**
 * Pure text/clickability derivation for [FolderSyncStatusBadge] — extracted from the composable so
 * the "distinct copy per state" contract (design/ux.md AC22, Story 2.5.3) is directly unit-testable
 * without a Compose UI test harness (this project's `wasmJsTest` source set has no
 * `ui-test`-equivalent dependency wired up for the web target). `null` means "render nothing"
 * ([HostAccessState.NotApplicable] only).
 *
 * State → copy mapping mirrors `project_plans/web-local-folder-livesync/design/ux.md` Surface 3's
 * precedence table:
 * - [HostAccessState.Disconnected]: "Folder not found — Reconnect" — re-locate (re-run the
 *   directory picker), never "reconnect"/"grant access", per ux.md's reconnect-vs-conflict split.
 * - [HostAccessState.Denied]: "Folder access declined — Grant access" — distinct from
 *   [HostAccessState.PromptNeeded]'s copy so a user who explicitly clicked "Don't allow"
 *   understands *why* they're being asked again.
 * - [HostAccessState.PromptNeeded]: "Reconnect folder".
 * - [HostAccessState.Granted] with [pendingWriteCount] > 0: "N changes syncing to `<dirName>`"
 *   (informational, not clickable). The `SyncDegraded` variant of this row (design/ux.md's
 *   "N changes not yet synced" copy for a stuck queue) is Phase 4's Task 4.4.1c — deferred, not
 *   rendered here, since the queue that would ever make this condition true doesn't exist yet.
 * - [HostAccessState.Granted] with zero pending writes: "Synced to `<dirName>`".
 */
data class FolderSyncBadgeContent(
    val text: String,
    val clickable: Boolean,
)

fun folderSyncBadgeContent(
    state: HostAccessState,
    dirName: String?,
    pendingWriteCount: Int,
): FolderSyncBadgeContent? = when (state) {
    is HostAccessState.NotApplicable -> null

    is HostAccessState.Disconnected -> FolderSyncBadgeContent(
        text = "Folder not found — Reconnect",
        clickable = true,
    )

    is HostAccessState.Denied -> FolderSyncBadgeContent(
        text = "Folder access declined — Grant access",
        clickable = true,
    )

    is HostAccessState.PromptNeeded -> FolderSyncBadgeContent(
        text = "Reconnect folder",
        clickable = true,
    )

    is HostAccessState.Granted -> if (pendingWriteCount > 0) {
        FolderSyncBadgeContent(
            text = "$pendingWriteCount changes syncing to ${dirName ?: "folder"}",
            clickable = false,
        )
    } else {
        FolderSyncBadgeContent(
            text = "Synced to ${dirName ?: "folder"}",
            clickable = false,
        )
    }
}

private fun folderSyncBadgeIcon(state: HostAccessState): ImageVector = when (state) {
    is HostAccessState.Disconnected -> Icons.Default.FolderOff
    is HostAccessState.Granted -> Icons.Default.FolderOpen
    else -> Icons.Default.Folder
}

/**
 * Epic 2.3 (Story 2.3.1): sidebar badge for [HostAccessState] — the web-local-folder-livesync
 * counterpart to [SyncStatusBadge], rendered alongside it (a sibling badge, not merged into it —
 * distinct subsystem, distinct icon family; uses a folder icon, never `Computer`/`Cloud`, per
 * design/ux.md §0's "false-friend reuse" warning).
 *
 * See [folderSyncBadgeContent] for the exact per-state copy contract this composable renders.
 * [HostAccessState.NotApplicable] renders nothing (no broken affordance on platforms/graphs with
 * no host directory connected).
 *
 * Accessibility (Task 2.3.1b): the status *text* carries `liveRegion = Polite` semantics (state
 * transitions announced without interrupting typing); the reconnect affordance is a real
 * `Role.Button` + `clickable` row (Tab-reachable, Enter/Space-activatable), matching
 * [SyncStatusBadge]'s `CredentialExpired` "tap to re-connect" precedent — never a bare [Text] with
 * a click modifier and no semantics.
 *
 * @param state Current [HostAccessState] for the active graph's host directory connection.
 * @param dirName Display name of the connected host directory, or null if never connected. Only
 *   read when [state] is [HostAccessState.Granted].
 * @param pendingWriteCount Count of edits queued for push to the host directory (Phase 4's
 *   write-through queue; always `0` until that queue exists).
 * @param onReconnect Called when the user taps the reconnect/grant-access affordance. The caller
 *   is responsible for routing this to `requestHostDirectoryAccess` (permission re-grant, used by
 *   [HostAccessState.PromptNeeded]/[HostAccessState.Denied]) or a directory re-pick (used by
 *   [HostAccessState.Disconnected]) — this composable does not distinguish which, since both
 *   converge on the same single click here per design/ux.md Surface 4's "no modal-before-the-modal"
 *   decision.
 */
@Composable
fun FolderSyncStatusBadge(
    state: HostAccessState,
    dirName: String?,
    pendingWriteCount: Int,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = folderSyncBadgeContent(state, dirName, pendingWriteCount) ?: return
    val tint = if (content.clickable) FolderSyncWarningColor else MaterialTheme.colorScheme.onSurfaceVariant

    val rowModifier = modifier.padding(horizontal = 4.dp).let {
        if (content.clickable) {
            it.semantics { role = Role.Button }.clickable(onClick = onReconnect)
        } else {
            it
        }
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = folderSyncBadgeIcon(state),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = content.text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
