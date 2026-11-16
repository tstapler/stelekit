// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.PausedRetryInfo
import dev.stapler.stelekit.git.SyncRunInfo
import dev.stapler.stelekit.git.SyncSheetState
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.syncAnnouncement
import dev.stapler.stelekit.git.syncSheetState
import kotlinx.coroutines.flow.MutableStateFlow
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
    val service = gitSync.gitSyncService
    val inFlight by service.inFlight.collectAsState()
    val pausedRetry by service.pausedRetry.collectAsState()
    val current = staleness ?: return
    val model = remember(current, syncState, reviewPending, nowMs, interval, inFlight, pausedRetry) {
        syncStatusSheetModel(
            current, syncState, nowMs, interval, reviewPending,
            inFlight = inFlight, pausedRetry = pausedRetry, retryNow = viewModel::retrySyncNow,
        )
    }
    val announcement = rememberSyncAnnouncement(model.state, viewModel)
    SyncStatusSheet(
        model = model,
        notice = appState.syncStatusNotice,
        announcement = announcement,
        actions = SyncStatusSheetActions(
            onSyncNow = viewModel::triggerSync,
            onReviewFirstSync = {
                viewModel.dismissSyncStatusSheet()
                viewModel.openFirstSyncReview()
            },
            onOpenSettings = viewModel::openSyncSettingsFromStatusSheet,
            onDismiss = viewModel::dismissSyncStatusSheet,
            onOpenProblem = {
                viewModel.dismissSyncStatusSheet()
                viewModel.openSyncDetails()
            },
        ),
    )
}

private val NO_RUN = MutableStateFlow<SyncRunInfo?>(null)
private val NO_RETRY = MutableStateFlow<PausedRetryInfo?>(null)

/** The sync row's state for the sidebar; null while there is no service to describe. */
@Composable
internal fun rememberSyncSheetState(service: GitSyncService?, syncState: SyncState): SyncSheetState? {
    val inFlight by (service?.inFlight ?: NO_RUN).collectAsState()
    val pausedRetry by (service?.pausedRetry ?: NO_RETRY).collectAsState()
    val nowMs = rememberMinuteClockMs()
    if (service == null) return null
    return syncSheetState(syncState, inFlight, lastVerdict = null, pausedRetry = pausedRetry, nowMs = nowMs)
}

/**
 * The one polite announcement for the sheet: entering Paused or Failed (not each retry tick, and not the brief
 * Running flash a retry shows) or a coalesced press. A trailing zero-width mark lets a repeat of the same text
 * still change the live region.
 */
@Composable
private fun rememberSyncAnnouncement(state: SyncSheetState, viewModel: StelekitViewModel): String? {
    var message by remember { mutableStateOf<String?>(null) }
    var settled by remember { mutableStateOf<SyncSheetState?>(null) }
    var repeats by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        if (state is SyncSheetState.Running) return@LaunchedEffect
        syncAnnouncement(settled, state)?.let { message = it }
        settled = state
    }
    LaunchedEffect(viewModel) {
        viewModel.syncEvents.collect {
            repeats++
            message = it.announcement + if (repeats % 2 == 0) "​" else ""
        }
    }
    return message
}
