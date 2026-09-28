// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.performance.getDeviceInfo
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.openInBrowser
import dev.stapler.stelekit.ui.appStorageSubtitleFor
import dev.stapler.stelekit.ui.components.UnifiedLocationPicker

/**
 * Presentational wrapper around [GitHubOAuthDialog] — [onRetry] is one of
 * [GitSetupScreen]'s `startGithubOAuthFlowFresh`/`retryGithubOAuthFlow` local functions, kept
 * distinct there since the initial-connect and dialog-retry entry points prime dialog state
 * differently before launching the same `startOAuthFlow`.
 */
@Composable
internal fun GitSetupOAuthDialogHost(
    state: OAuthDialogState,
    clipboardManager: ClipboardManager,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    GitHubOAuthDialog(
        state = state,
        onCopyCode = { code ->
            clipboardManager.setText(AnnotatedString(code))
        },
        onOpenBrowser = { url ->
            openInBrowser(url)
        },
        onCancel = onCancel,
        onRetry = onRetry,
        onDone = onDone,
    )
}

/**
 * Story 2.2.2: clone-destination picker for "clone a remote repository". Only ever shown when
 * [FileSystem.supportsAppOwnedStorage] (Android today), so this dialog never appears on
 * Desktop/iOS, matching those platforms' pre-feature "Browse…"-only behavior.
 */
@Composable
internal fun GitSetupCloneLocationPickerHost(
    fileSystem: FileSystem,
    pendingAppOwnedGraphPath: String,
    onPendingSafRepoRootChange: (String) -> Unit,
    onConfirm: (StorageLocation?) -> Unit,
    onDismiss: () -> Unit,
) {
    UnifiedLocationPicker(
        title = "Choose where to clone this repository",
        graphId = graphIdFromPath(fileSystem.expandTilde(pendingAppOwnedGraphPath)),
        appStorageSubtitle = appStorageSubtitleFor(getDeviceInfo().platform),
        platformCapabilities = fileSystem.supportsNativeDirectoryPicker,
        onBrowseClick = {
            // Must run synchronously here, not inside onBrowseRequest's scope.launch — see
            // UnifiedLocationPicker's onBrowseClick doc / stack.md §3's Chrome requirement.
            fileSystem.requestDirectoryPickerNow()
        },
        onBrowseRequest = {
            val path = fileSystem.pickDirectoryAsync()
            path?.let {
                val expanded = fileSystem.expandTilde(it)
                onPendingSafRepoRootChange(expanded)
                // SafFolder's treeUri only needs to uniquely address the picked tree — the
                // encoded segment right after "saf://" (see PlatformFileSystem.toSafRoot) —
                // not the fully-decoded content:// form AndroidStorageLocationResolver
                // reconstructs for a pre-existing graph's lazy backfill.
                val treeUri = expanded.removePrefix("saf://").substringBefore("/")
                StorageLocation.SafFolder(graphIdFromPath(expanded), treeUri)
            }
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
