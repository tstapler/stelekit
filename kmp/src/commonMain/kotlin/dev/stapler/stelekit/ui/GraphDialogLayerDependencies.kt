// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.export.ExportService
import dev.stapler.stelekit.export.ShareProvider
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.performance.DebugMenuState
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.platform.google.DriveUploader
import dev.stapler.stelekit.platform.google.GoogleAuthManager
import dev.stapler.stelekit.tags.TagSettings
import dev.stapler.stelekit.ui.components.settings.ReconciliationUiState
import dev.stapler.stelekit.vault.VaultError
import dev.stapler.stelekit.voice.VoiceSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Everything [SettingsDialog] needs that [GraphDialogLayer] doesn't otherwise use — voice, LLM,
 * vault, Google auth, tags, and web-local-folder-livesync — bundled into one value object
 * (Parameter Object pattern, Fowler "Refactoring" §Introduce Parameter Object, matching
 * [StelekitViewModelDependencies]'s precedent for this codebase).
 */
data class SettingsDialogDeps(
    val voiceSettings: VoiceSettings? = null,
    val llmCredentialStore: LlmCredentialStore? = null,
    val llmProviderRegistry: LlmProviderRegistry? = null,
    val llmSettings: LlmSettings? = null,
    val onLlmCredentialsChange: () -> Unit = {},
    val onRebuildVoicePipeline: (() -> Unit)? = null,
    val deviceSttAvailable: Boolean = false,
    val deviceLlmAvailable: Boolean = false,
    val isParanoidMode: Boolean = false,
    val isVaultUnlocked: Boolean = false,
    val onCreateVault: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    val onAddKeyslot: (suspend (CharArray) -> Either<VaultError, Unit>)? = null,
    val onRemoveKeyslot: (suspend (Int) -> Either<VaultError, Unit>)? = null,
    val onLockVault: (() -> Unit)? = null,
    val onListActiveSlots: (suspend () -> List<Int>)? = null,
    val isGoogleAuthenticated: Boolean = false,
    val googleConnectedEmail: String? = null,
    val isGoogleConnecting: Boolean = false,
    val googleAuthError: String? = null,
    val onConnectGoogle: (() -> Unit)? = null,
    val onDisconnectGoogle: (() -> Unit)? = null,
    val tagSettings: TagSettings? = null,
    val hasLlmKey: Boolean = false,
    // web-local-folder-livesync (Task 3.1.1c): null/NotApplicable/false on JVM/Android/iOS, which
    // keeps SettingsDialog's FolderSyncSettings call site un-rendered there.
    val hostAccessState: HostAccessState = HostAccessState.NotApplicable,
    val onConnectHostDirectory: (suspend () -> ReconciliationUiState)? = null,
    // Desktop-only quick-capture hotkey (Story 1.4.2) — null on platforms with no global hotkey.
    val hotkeyComboLabel: String? = null,
)

/**
 * Everything the git-setup wizard and conflict-resolution dialog need, bundled together since
 * both act on the same [activeGraphId]'s git state.
 */
data class GitSyncDeps(
    val gitSyncService: GitSyncService? = null,
    val gitRepository: GitRepository? = null,
    val gitConfigRepository: GitConfigRepository? = null,
    val activeGraphId: String? = null,
    val onCloneAndAdd: (suspend (url: String, localPath: String, auth: GitAuth, onProgress: (String) -> Unit) -> Either<DomainError.GitError, String>)? = null,
    val graphPath: String = "",
    // Auto-detected by GraphManager.detectGitRoot() (walks up from graphPath looking for `.git`);
    // threaded through so GitSetupScreen can prefill Step2RepoPath instead of discarding detection
    // the app already surfaced via GitDetectionBanner. Null/empty (e.g. Android SAF paths, which
    // detectGitRoot can't inspect) falls back to the wizard's own graphPath-based default.
    val detectedRepoRoot: String? = null,
    val detectedWikiSubdir: String? = null,
    val onCloneComplete: ((String) -> Unit)? = null,
    val onAuthError: (() -> Unit)? = null,
)

/** Everything [ShareDialog] needs to export/share the current page or selection. */
data class ShareDialogDeps(
    val shareProvider: ShareProvider? = null,
    val exportService: ExportService? = null,
    val driveClient: DriveUploader? = null,
    val shareGoogleAuthManager: GoogleAuthManager? = null,
    val currentPage: Page? = null,
    val currentBlocks: List<Block> = emptyList(),
    val selectedBlockUuids: Set<String> = emptySet(),
)

/**
 * All non-universal [GraphDialogLayer] dependencies, grouped by which dialog consumes them.
 * [appState], [viewModel], [searchViewModel], [notificationManager], [fileSystem], and
 * [frameMetric] stay as direct parameters on [GraphDialogLayer] since nearly every dialog reads
 * them; everything else — one feature's worth of settings, callbacks, and read-only state per
 * dialog — lives here instead.
 */
data class GraphDialogLayerDeps(
    val settings: SettingsDialogDeps = SettingsDialogDeps(),
    val gitSync: GitSyncDeps = GitSyncDeps(),
    val share: ShareDialogDeps = ShareDialogDeps(),
    val debugState: DebugMenuState = DebugMenuState(),
    val loadPageBlocks: (String) -> Flow<Either<DomainError, List<Block>>> = { flowOf(Either.Right(emptyList())) },
    val onDebugStateChange: (DebugMenuState) -> Unit = {},
)
