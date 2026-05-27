// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.performance.DebugBuildConfig
import dev.stapler.stelekit.performance.FrameMetric
import dev.stapler.stelekit.performance.DebugMenuState
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.ui.screens.git.GitSetupScreen
import dev.stapler.stelekit.vault.VaultError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import dev.stapler.stelekit.ui.components.CommandPalette
import dev.stapler.stelekit.ui.components.DebugMenuOverlay
import dev.stapler.stelekit.ui.components.DiskConflictDialog
import dev.stapler.stelekit.ui.components.NotificationOverlay
import dev.stapler.stelekit.ui.components.PlatformFrameTimeOverlay
import dev.stapler.stelekit.ui.components.RenamePageDialog
import dev.stapler.stelekit.ui.components.SearchDialog
import dev.stapler.stelekit.ui.components.settings.SettingsDialog
import dev.stapler.stelekit.ui.screens.SearchViewModel
import dev.stapler.stelekit.voice.VoiceSettings

/**
 * All overlay dialogs for the graph content area, composed as a single layer.
 * Extracted from GraphContent so dialog additions don't modify the layout tree.
 * See ADR-001.
 */
@Composable
internal fun GraphDialogLayer(
    appState: AppState,
    searchViewModel: SearchViewModel,
    viewModel: StelekitViewModel,
    notificationManager: NotificationManager,
    fileSystem: FileSystem,
    frameMetric: StateFlow<FrameMetric>,
    voiceSettings: VoiceSettings? = null,
    onRebuildVoicePipeline: (() -> Unit)? = null,
    deviceSttAvailable: Boolean = false,
    deviceLlmAvailable: Boolean = false,
    debugState: DebugMenuState = DebugMenuState(),
    loadPageBlocks: (String) -> Flow<Either<DomainError, List<Block>>> = { flowOf(Either.Right(emptyList())) },
    onDebugStateChange: (DebugMenuState) -> Unit = {},
    isParanoidMode: Boolean = false,
    isVaultUnlocked: Boolean = false,
    onCreateVault: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    onAddKeyslot: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    onRemoveKeyslot: (suspend (Int) -> Either<VaultError, Unit>)? = null,
    onLockVault: (() -> Unit)? = null,
    onListActiveSlots: (suspend () -> List<Int>)? = null,
    gitSyncService: GitSyncService? = null,
    gitRepository: GitRepository? = null,
    gitConfigRepository: GitConfigRepository? = null,
    activeGraphId: String? = null,
) {
    val scope = rememberCoroutineScope()

    CommandPalette(
        visible = appState.commandPaletteVisible,
        commands = appState.commands,
        onDismiss = { viewModel.setCommandPaletteVisible(false) }
    )

    val indexingProgress by viewModel.indexingProgress.collectAsState()
    SearchDialog(
        visible = appState.searchDialogVisible,
        viewModel = searchViewModel,
        onDismiss = { viewModel.setSearchDialogVisible(false) },
        onNavigateToPage = { viewModel.navigateToPageByUuid(it) },
        onNavigateToBlock = { viewModel.navigateToBlock(it) },
        onCreatePage = { viewModel.navigateToPageByName(it) },
        initialQuery = appState.searchDialogInitialQuery,
        isIndexing = indexingProgress is IndexingState.InProgress,
        loadPageBlocks = loadPageBlocks
    )

    SettingsDialog(
        visible = appState.settingsVisible,
        onDismiss = { viewModel.setSettingsVisible(false) },
        currentTheme = appState.themeMode,
        onThemeChange = { viewModel.setThemeMode(it) },
        currentLanguage = appState.language,
        onLanguageChange = { viewModel.setLanguage(it) },
        onReindex = {
            viewModel.triggerReindex()
            viewModel.setSettingsVisible(false)
        },
        isLeftHanded = appState.isLeftHanded,
        onLeftHandedChange = { viewModel.setLeftHanded(it) },
        voiceSettings = voiceSettings,
        onRebuildVoicePipeline = onRebuildVoicePipeline,
        deviceSttAvailable = deviceSttAvailable,
        deviceLlmAvailable = deviceLlmAvailable,
        isParanoidMode = isParanoidMode,
        isVaultUnlocked = isVaultUnlocked,
        onCreateVault = onCreateVault,
        onAddKeyslot = onAddKeyslot,
        onRemoveKeyslot = onRemoveKeyslot,
        onLockVault = onLockVault,
        onListActiveSlots = onListActiveSlots,
    )

    val canShowGitSetup = appState.gitSetupVisible &&
        gitSyncService != null && gitRepository != null && gitConfigRepository != null
    if (canShowGitSetup) {
        GitSetupScreen(
            graphId = activeGraphId ?: "",
            gitRepository = gitRepository,
            gitConfigRepository = gitConfigRepository,
            gitSyncService = gitSyncService,
            onDismiss = { viewModel.dismissGitSetup() },
            onSave = { viewModel.dismissGitSetup() },
        )
    }

    if (appState.conflictResolutionVisible) {
        val liveSyncState by viewModel.syncState.collectAsState()
        val conflictFiles = if (liveSyncState is SyncState.ConflictPending)
            (liveSyncState as SyncState.ConflictPending).conflicts.map { it.filePath }
        else emptyList()

        AlertDialog(
            onDismissRequest = { viewModel.dismissConflictResolution() },
            title = { Text("Merge Conflict") },
            text = {
                Column {
                    Text(
                        "Git detected merge conflicts in ${conflictFiles.size} file(s). " +
                        "Resolve them in your editor or use the options below."
                    )
                    if (conflictFiles.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        conflictFiles.forEach { path ->
                            Text(
                                "• $path",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissConflictResolution() }) {
                    Text("Dismiss")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissConflictResolution()
                        viewModel.openGitSetup()
                    }
                ) {
                    Text("Open Git Setup")
                }
            },
        )
    }

    appState.diskConflict?.let { conflict ->
        DiskConflictDialog(
            conflict = conflict,
            onKeepLocal = { viewModel.keepLocalChanges() },
            onUseDisk = { viewModel.acceptDiskVersion() },
            onSaveAsNew = { viewModel.saveAsNewBlock() },
            onManualResolve = { viewModel.manualResolve() }
        )
    }

    appState.renameDialogPage?.let { page ->
        RenamePageDialog(
            page = page,
            busy = appState.renameDialogBusy,
            error = appState.renameDialogError,
            onConfirm = { newName -> viewModel.renamePage(page, newName) },
            onDismiss = { viewModel.dismissRenameDialog() }
        )
    }

    NotificationOverlay(
        notificationManager = notificationManager,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    )

    // Frame-time debug overlay — shown in top corner when enabled, regardless of dialog state.
    PlatformFrameTimeOverlay(isEnabled = debugState.isFrameOverlayEnabled, frameMetric = frameMetric)

    if (appState.isDebugMenuVisible && DebugBuildConfig.isDebugBuild) {
        DebugMenuOverlay(
            state = debugState,
            onStateChange = { newState -> onDebugStateChange(newState) },
            onExportBugReport = {
                val json = viewModel.exportBugReport()
                if (json == null) {
                    notificationManager.show("Bug report unavailable — OTel not initialized")
                } else {
                    scope.launch {
                        val path = fileSystem.pickSaveFileAsync("stelekit-bug-report.json", "application/json")
                        when {
                            path == null -> { /* user cancelled */ }
                            fileSystem.writeFile(path, json) ->
                                notificationManager.show("Bug report saved to ${fileSystem.displayNameForPath(path)}")
                            else ->
                                notificationManager.show("Failed to save bug report. Check storage permissions.")
                        }
                    }
                }
            },
            onDismiss = { viewModel.dismissDebugMenu() }
        )
    }

}
