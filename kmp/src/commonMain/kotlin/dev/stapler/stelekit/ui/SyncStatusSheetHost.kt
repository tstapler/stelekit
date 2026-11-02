// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.stapler.stelekit.git.effectiveScheduledIntervalMinutes
import dev.stapler.stelekit.git.syncStatusSheetModel
import dev.stapler.stelekit.ui.components.SyncStatusSheet
import dev.stapler.stelekit.ui.components.SyncStatusSheetActions
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSettingsState

private const val DEFAULT_SHEET_INTERVAL_MINUTES = 15

/** Hosts the standalone sync status sheet; only meaningful where a sync service exists, so it renders nothing otherwise. */
@Composable
internal fun SyncStatusSheetHost(
    appState: AppState,
    viewModel: StelekitViewModel,
    gitSync: GitSyncDeps,
    scheduledSync: ScheduledSyncSettingsState?,
) {
    if (!appState.syncStatusSheetVisible || gitSync.gitSyncService == null) return
    val staleness by viewModel.syncStaleness.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val reviewPending by viewModel.firstSyncReviewPending.collectAsState()
    val nowMs = rememberMinuteClockMs()
    val interval = scheduledSync?.let {
        effectiveScheduledIntervalMinutes(it.configuredIntervalMinutes, it.minimumIntervalMinutes)
    } ?: DEFAULT_SHEET_INTERVAL_MINUTES
    val current = staleness ?: return
    val model = remember(current, syncState, reviewPending, nowMs, interval) {
        syncStatusSheetModel(current, syncState, nowMs, interval, reviewPending)
    }
    SyncStatusSheet(
        model = model,
        notice = appState.syncStatusNotice,
        actions = SyncStatusSheetActions(
            onSyncNow = viewModel::triggerSync,
            onReviewFirstSync = {
                viewModel.dismissSyncStatusSheet()
                viewModel.openFirstSyncReview()
            },
            onOpenSettings = viewModel::openSyncSettingsFromStatusSheet,
            onDismiss = viewModel::dismissSyncStatusSheet,
        ),
    )
}
