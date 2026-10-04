// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.ui.components.BrowserOnlySyncBanner
import dev.stapler.stelekit.ui.components.HostReconnectBanner
import dev.stapler.stelekit.ui.components.git.GitDetectionBanner
import kotlinx.coroutines.CoroutineScope

/**
 * Everything [GraphContentMainArea] needs beyond [GraphContentDeps] (Parameter Object pattern) —
 * almost entirely already-built stacks from other GraphContent setup steps.
 */
internal class GraphContentMainAreaInputs(
    val appState: AppState,
    val activeGraphId: GraphId?,
    val graphRegistry: GraphRegistry,
    val hostAccessState: HostAccessState,
    val hostWriteStuck: Boolean,
    val hostWritePendingCount: Int,
    val gitConfigRepository: GitConfigRepository?,
    val graphIoStack: GraphContentGraphIoStack,
    val viewModelStack: GraphContentViewModelStack,
    val supportingViewModels: GraphContentSupportingViewModels,
    val tagVoiceStack: GraphContentTagVoiceStack,
    val perfTelemetry: GraphContentPerformanceTelemetry,
    val scope: CoroutineScope,
    val graphContentLogger: Logger,
)

/**
 * The main content area: connectivity banners (git-detected, browser-only-sync, host-reconnect),
 * [ScreenRouter], and the camera-capture dialogs (see GraphContentCameraCapture.kt). Split out
 * from [GraphContent] purely for length/nesting — see ADR-001-style decomposition rationale at
 * [GraphContent]'s own doc.
 */
@Composable
internal fun GraphContentMainArea(
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    inputs: GraphContentMainAreaInputs,
) {
    val activeGraphInfo = inputs.graphRegistry.graphs.firstOrNull { it.id == inputs.activeGraphId }
    // appState.gitConfig used to default to null and never get assigned anywhere (verified via
    // repo-wide grep), so every UI element gated on it — the sidebar "git configured" indicator,
    // this banner's suppression check — was permanently wrong regardless of the graph's real
    // GitConfigRepository state. Reload it from the repository whenever the active graph changes.
    LaunchedEffect(inputs.activeGraphId) {
        reloadGitConfig(inputs, viewModel, activeGraphInfo)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GraphContentBanners(deps, viewModel, inputs, activeGraphInfo)
        Box(modifier = Modifier.weight(1f)) {
            GraphContentScreenAndCapture(deps, viewModel, inputs)
        }
    }
}

private suspend fun reloadGitConfig(
    inputs: GraphContentMainAreaInputs,
    viewModel: StelekitViewModel,
    activeGraphInfo: dev.stapler.stelekit.model.GraphInfo?,
) {
    val gid = inputs.activeGraphId?.value ?: return
    val repoConfig = inputs.gitConfigRepository?.getConfig(gid)?.getOrNull()
    inputs.graphContentLogger.info(
        "gitConfig loaded graph=$gid configured=${repoConfig != null} " +
            "detectedRepoRoot=${activeGraphInfo?.detectedRepoRoot}"
    )
    viewModel.setGitConfig(repoConfig)
}

/** Which of [GraphContentBanners]' banners should show, plus the "degraded" reason for the badge. */
private class BannerVisibility(
    val showGitBanner: Boolean,
    val showContentMismatchBanner: Boolean,
    val showBrowserOnlySyncBanner: Boolean,
    val hostSyncDegraded: Boolean,
    val showHostReconnectBanner: Boolean,
)

private fun computeBannerVisibility(
    deps: GraphContentDeps,
    inputs: GraphContentMainAreaInputs,
    activeGraphInfo: dev.stapler.stelekit.model.GraphInfo?,
    hostReconnectBannerDismissedFor: Pair<String?, String?>?,
): BannerVisibility {
    // Git detection banner (existing)
    val showGitBanner = activeGraphInfo?.detectedRepoRoot != null &&
        inputs.appState.gitConfig == null &&
        activeGraphInfo.gitDetectionDismissed == false

    // Content mismatch banner - show whenever mismatch is detected on a non-demo graph and not dismissed
    val showContentMismatchBanner = activeGraphInfo != null &&
        activeGraphInfo.isDemo == false &&
        activeGraphInfo.contentMismatchDetected == true &&
        activeGraphInfo.contentMismatchBannerDismissed == false

    // Browser-only sync banner (existing)
    val showBrowserOnlySyncBanner = activeGraphInfo != null &&
        activeGraphInfo.isDemo == false &&
        inputs.hostAccessState == HostAccessState.NotApplicable &&
        deps.fileSystem.supportsNativeDirectoryPicker &&
        deps.webSyncDeps.onConnectHostDirectory != null &&
        activeGraphInfo.browserOnlySyncBannerDismissed == false

    // SyncDegraded: permission still reads as Granted, but the write-through queue is stuck — the
    // startup log line ("reconnectHostDirectory(...): Granted") looks like sync is working, and
    // nothing else prints a warning, so this condition was previously visible only in the small
    // sidebar badge.
    val hostSyncDegraded = inputs.hostAccessState is HostAccessState.Granted &&
        inputs.hostWriteStuck &&
        inputs.hostWritePendingCount > 0

    val hostReconnectBannerConditionKind = if (hostSyncDegraded) "degraded" else inputs.hostAccessState::class.simpleName
    val showHostReconnectBanner = activeGraphInfo != null &&
        (inputs.hostAccessState is HostAccessState.PromptNeeded ||
            inputs.hostAccessState is HostAccessState.Denied ||
            hostSyncDegraded) &&
        hostReconnectBannerDismissedFor != (inputs.activeGraphId?.value to hostReconnectBannerConditionKind)

    return BannerVisibility(showGitBanner, showContentMismatchBanner, showBrowserOnlySyncBanner, hostSyncDegraded, showHostReconnectBanner)
}

/** Connectivity banners: git-detected, browser-only-sync, host-reconnect (in that stacking order). */
@Composable
private fun GraphContentBanners(
    deps: GraphContentDeps,
    viewModel: StelekitViewModel,
    inputs: GraphContentMainAreaInputs,
    activeGraphInfo: dev.stapler.stelekit.model.GraphInfo?,
) {
    // Keyed by (graphId, condition kind), not just graphId: dismissing the banner for one failure
    // kind (e.g. Denied) must not suppress it for a later, unrelated one (e.g. SyncDegraded) on
    // the same graph.
    var hostReconnectBannerDismissedFor by remember { mutableStateOf<Pair<String?, String?>?>(null) }
    val visibility = computeBannerVisibility(deps, inputs, activeGraphInfo, hostReconnectBannerDismissedFor)

    if (visibility.showGitBanner) {
        GitDetectionBanner(
            repoRoot = activeGraphInfo!!.detectedRepoRoot!!,
            onSetupSync = { viewModel.openGitSetup() },
            onDismiss = {
                val gid = inputs.activeGraphId ?: return@GitDetectionBanner
                viewModel.dismissGitDetection(gid.value)
            },
        )
    }
    if (visibility.showContentMismatchBanner) {
        val mismatchInfo = activeGraphInfo?.directoryScanCandidates?.firstOrNull() ?: dev.stapler.stelekit.diagnostics.DirectoryScanResult(
            path = activeGraphInfo?.effectivePath ?: activeGraphInfo?.path ?: "",
            pages = true,
            journals = true,
            name = activeGraphInfo?.detectedWikiSubdir ?: activeGraphInfo?.displayName ?: "",
        )
        val configuredPath = inputs.appState.gitConfig?.wikiSubdir ?: activeGraphInfo?.detectedWikiSubdir ?: ""
        WikiSubdirFixBanner(
            mismatchInfo = mismatchInfo,
            configuredPath = configuredPath,
            onDismiss = {
                val gid = inputs.activeGraphId ?: return@WikiSubdirFixBanner
                viewModel.dismissContentMismatchBanner(gid.value)
            },
            onResolveClick = {
                viewModel.openWikiSubdirFixDialog()
            },
        )
    }
    if (visibility.showBrowserOnlySyncBanner) {
        BrowserOnlySyncBanner(
            onEnableSync = { viewModel.setSettingsVisible(true) },
            onDismiss = {
                val gid = inputs.activeGraphId ?: return@BrowserOnlySyncBanner
                viewModel.dismissBrowserOnlySyncBanner(gid.value)
            },
        )
    }
    if (visibility.showHostReconnectBanner) {
        HostReconnectBanner(
            state = inputs.hostAccessState,
            degraded = visibility.hostSyncDegraded,
            onReconnect = { deps.webSyncDeps.onReconnectHostDirectory?.invoke() },
            onDismiss = {
                val kind = if (visibility.hostSyncDegraded) "degraded" else inputs.hostAccessState::class.simpleName
                hostReconnectBannerDismissedFor = inputs.activeGraphId?.value to kind
            },
        )
    }
}
