// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.platform.EncryptionManager
import dev.stapler.stelekit.platform.PluginHost
import dev.stapler.stelekit.ui.components.VoiceCaptureButton
import dev.stapler.stelekit.vault.VaultManager
import dev.stapler.stelekit.voice.VoiceCaptureState
import dev.stapler.stelekit.voice.VoiceCaptureViewModel
import dev.stapler.stelekit.voice.VoicePipelineConfig

/** Non-state collaborators [GraphContentDesktopStatusRow] needs (bundled for parameter-count relief). */
internal class GraphContentStatusRowInputs(
    val appState: AppState,
    val encryptionManager: EncryptionManager,
    val activeGraphInfo: GraphInfo?,
    val pluginHost: PluginHost,
    val activeVaultManager: VaultManager?,
    val graphIoStack: GraphContentGraphIoStack,
)

/**
 * The desktop status bar row: encryption/graph-name/status-message plus a lock-vault button when
 * an unlocked vault manager is active. [GraphContentStatusRowInputs.activeVaultManager] is
 * pre-gated by `isParanoidMode` at the call site rather than taken as a separate flag argument.
 * Split out from [GraphContent] purely for length/nesting — see ADR-001-style decomposition
 * rationale at [GraphContent]'s own doc; the mobile-vs-desktop `isMobile` check stays at that call
 * site for the same reason [EncryptionState] avoids a boolean *parameter* branched on directly.
 */
@Composable
internal fun GraphContentDesktopStatusRow(viewModel: StelekitViewModel, inputs: GraphContentStatusRowInputs) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusBarContent(
            encryptionState = if (inputs.encryptionManager.isEncryptionEnabled(inputs.appState.currentGraphPath.orEmpty())) {
                EncryptionState.ENCRYPTED
            } else {
                EncryptionState.UNENCRYPTED
            },
            statusMessage = inputs.appState.statusMessage,
            activeGraphName = inputs.activeGraphInfo?.displayName ?: "",
            pluginCount = inputs.pluginHost.getAllPlugins().size,
            modifier = Modifier.weight(1f),
        )
        val activeVaultManager = inputs.activeVaultManager
        if (activeVaultManager != null) {
            IconButton(onClick = {
                viewModel.flushAndLockVault(inputs.graphIoStack.graphLoader, inputs.graphIoStack.graphWriter, activeVaultManager)
            }) {
                Icon(imageVector = Icons.Filled.Lock, contentDescription = "Lock vault")
            }
        }
    }
}

/** Non-state collaborators [GraphContentBottomBar] needs (bundled for parameter-count relief). */
internal class GraphContentBottomBarInputs(
    val viewModel: StelekitViewModel,
    val voiceCaptureViewModel: VoiceCaptureViewModel,
    val voiceCaptureState: VoiceCaptureState,
    val voicePipeline: VoicePipelineConfig,
)

/** The bottom navigation bar (mobile) with its embedded voice-capture button. */
@Composable
internal fun GraphContentBottomBar(
    appState: AppState,
    inputs: GraphContentBottomBarInputs,
    closeSidebarIfMobile: () -> Unit,
) {
    val viewModel = inputs.viewModel
    PlatformBottomBar(
        currentScreen = appState.currentScreen,
        onNavigate = { screen ->
            viewModel.navigateTo(screen)
            closeSidebarIfMobile()
        },
        onSearch = { viewModel.setSearchDialogVisible(true) },
        onToggleSidebar = { viewModel.toggleSidebar() },
        isLeftHanded = appState.isLeftHanded,
        voiceCaptureButton = {
            VoiceCaptureButton(
                state = inputs.voiceCaptureState,
                onTap = { inputs.voiceCaptureViewModel.onMicTapped() },
                onDismissError = { inputs.voiceCaptureViewModel.dismissError() },
                onAutoReset = { inputs.voiceCaptureViewModel.resetToIdle() },
                amplitudeFlow = inputs.voicePipeline.effectiveAmplitudeFlow,
                isSupported = inputs.voicePipeline.isSupported,
            )
        },
    )
}
