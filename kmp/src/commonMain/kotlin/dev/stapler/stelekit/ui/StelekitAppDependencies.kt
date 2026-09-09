// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.performance.NoOpSpanRecorder
import dev.stapler.stelekit.performance.SpanRecorder
import dev.stapler.stelekit.platform.DefaultEncryptionManager
import dev.stapler.stelekit.platform.EncryptionManager
import dev.stapler.stelekit.platform.HostAccessState
import dev.stapler.stelekit.platform.PluginHost
import dev.stapler.stelekit.domain.NoOpUrlFetcher
import dev.stapler.stelekit.domain.UrlFetcher
import dev.stapler.stelekit.stats.LibraryStatsProvider
import dev.stapler.stelekit.stats.NoOpLibraryStatsProvider
import dev.stapler.stelekit.ui.components.settings.ReconciliationUiState
import dev.stapler.stelekit.voice.VoicePipelineConfig
import dev.stapler.stelekit.voice.VoiceSettings
import kotlinx.coroutines.flow.StateFlow
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.RepositorySet

/**
 * Platform-agnostic infrastructure services [StelekitApp] wires up, all with working defaults.
 * Bundled into one value object (Parameter Object pattern, matching
 * [StelekitViewModelDependencies]'s precedent) since these are all "give me a sensible default
 * unless a host overrides it" — unlike the platform-integration points below, which are `null`
 * (feature disabled) unless a host explicitly supplies one.
 *
 * [StelekitApp] must default this parameter via `remember { StelekitAppCoreServices() }`, not a
 * plain `= StelekitAppCoreServices()`, to preserve the per-composition-stable identity the
 * individual `remember { PluginHost() }`-style defaults had before this grouping.
 */
data class StelekitAppCoreServices(
    val pluginHost: PluginHost = PluginHost(),
    val encryptionManager: EncryptionManager = DefaultEncryptionManager(),
    val urlFetcher: UrlFetcher = NoOpUrlFetcher(),
    val libraryStatsProvider: LibraryStatsProvider = NoOpLibraryStatsProvider,
    val voicePipeline: VoicePipelineConfig = VoicePipelineConfig(),
    val spanRecorder: SpanRecorder = NoOpSpanRecorder,
)

/** Voice capture configuration threaded down to [StelekitApp]'s settings and capture UI. */
data class StelekitAppVoiceConfig(
    val voiceSettings: VoiceSettings? = null,
    val onRebuildVoicePipeline: (() -> Unit)? = null,
    val deviceSttAvailable: Boolean = false,
    val deviceLlmAvailable: Boolean = false,
)

/**
 * Callbacks a host Activity/window registers once at startup to hook into GraphManager
 * lifecycle and OS memory-pressure events (Android's `onTrimMemory`, primarily).
 */
data class StelekitAppLifecycleHooks(
    /** Called once the GraphManager instance is ready, so a host can wire up onTrimMemory handling. */
    val onGraphManagerReady: ((dev.stapler.stelekit.db.GraphManager) -> Unit)? = null,
    /**
     * Registers a memory-pressure handler. The lambda receives a `() -> Unit` callback to store
     * and invoke when onTrimMemory fires, mirroring [onGraphManagerReady]'s pattern.
     */
    val onMemoryPressure: (((() -> Unit) -> Unit))? = null,
)

/**
 * Platform-specific integrations that are entirely optional — each feature they unlock is
 * disabled (not stubbed) when its field is `null`.
 */
data class StelekitAppPlatformIntegrations(
    /**
     * Platform-specific git implementation. Pass `JvmGitRepository` on Desktop,
     * `AndroidGitRepository` on Android. When null, git sync is disabled.
     */
    val gitRepository: dev.stapler.stelekit.git.GitRepository? = null,
    /**
     * Platform-specific crypto engine for paranoid-mode vault operations.
     * Pass `JvmCryptoEngine` on Desktop. Android support is pending an AndroidCryptoEngine.
     * When null, paranoid mode is unavailable.
     */
    val cryptoEngine: dev.stapler.stelekit.vault.CryptoEngine? = null,
    /**
     * Platform-specific media attachment service. When non-null the attach-image button is shown
     * in `MobileBlockToolbar` on the PageView screen. Pass `JvmMediaAttachmentService` on Desktop,
     * the Android service (from `rememberAndroidMediaAttachmentService`) on Android, or null
     * (default) to hide the button entirely.
     */
    val attachmentService: dev.stapler.stelekit.service.MediaAttachmentService? = null,
    /**
     * Platform-specific Google OAuth manager. When non-null the Google Account settings panel
     * becomes interactive (Connect / Disconnect buttons are wired up). Pass
     * `AndroidGoogleAuthManager` on Android, `JvmGoogleAuthManager` on Desktop. When null
     * (default), the panel is rendered but the buttons are no-ops.
     */
    val googleAuthManager: dev.stapler.stelekit.platform.google.GoogleAuthManager? = null,
    val requestCameraPermission: (suspend () -> Boolean)? = null,
)

/**
 * `web-local-folder-livesync` state and callbacks — every field is web-only and stays `null`
 * (its dependent UI renders nothing) on JVM/Android/iOS.
 */
data class StelekitAppWebSyncDeps(
    /**
     * Count of locally-dirty files not yet synced to the remote. When non-null,
     * [StelekitViewModel.syncState] upgrades an otherwise-idle sync state to
     * `LocalChangesPending` while this count is nonzero. Pass
     * `PlatformFileSystem.dirtyFileCountFlow` on web.
     */
    val localChangesCountFlow: StateFlow<Int>? = null,
    /**
     * Current [HostAccessState] for the active graph's host directory connection. Pass
     * `PlatformFileSystem.hostDirectorySync.hostAccessStateFlow` on web. When null,
     * `FolderSyncStatusBadge` renders nothing.
     */
    val hostAccessStateFlow: StateFlow<HostAccessState>? = null,
    /**
     * Count of edits queued for push to the connected host directory. Pass
     * `PlatformFileSystem.hostDirectorySync.hostWritePendingCountFlow` on web. When null,
     * `FolderSyncStatusBadge` treats the pending count as zero.
     */
    val hostWritePendingCountFlow: StateFlow<Int>? = null,
    /**
     * `true` while a write-through flush is stuck (transient failure, permission nominally still
     * `Granted`). Pass `PlatformFileSystem.hostDirectorySync.hostWriteStuckFlow` on web. When
     * null, `FolderSyncStatusBadge` never renders the `SyncDegraded` row.
     */
    val hostWriteStuckFlow: StateFlow<Boolean>? = null,
    /**
     * Called when the user taps `FolderSyncStatusBadge`'s reconnect/grant-access affordance —
     * should invoke `PlatformFileSystem.hostDirectorySync.requestHostDirectoryAccess`. When null,
     * the badge's click affordance is disabled (it never renders anyway, since
     * [hostAccessStateFlow] stays null on those platforms).
     */
    val onReconnectHostDirectory: (() -> Unit)? = null,
    /**
     * "Enable live folder sync" affordance for an already-populated graph — invoked from
     * `SettingsDialog`'s `FolderSyncSettings` section. Should perform the real
     * `showDirectoryPicker → HostDirectorySync.connectHostDirectory → runHostReconciliation`
     * sequence and resolve to the terminal [ReconciliationUiState]. Pass a lambda wrapping
     * `PlatformFileSystem.hostDirectorySync.connectHostDirectory` on web. When null,
     * `FolderSyncSettings`'s call site renders nothing.
     */
    val onConnectHostDirectory: (suspend () -> ReconciliationUiState)? = null,
)

/**
 * Every optional [StelekitApp] dependency, grouped by theme. [StelekitApp] must default this
 * parameter via `remember { StelekitAppDeps() }`, not a plain `= StelekitAppDeps()` — see
 * [StelekitAppCoreServices]'s doc for why.
 */
data class StelekitAppDeps(
    val graphManager: GraphManager? = null,
    val coreServices: StelekitAppCoreServices = StelekitAppCoreServices(),
    val voiceConfig: StelekitAppVoiceConfig = StelekitAppVoiceConfig(),
    val lifecycleHooks: StelekitAppLifecycleHooks = StelekitAppLifecycleHooks(),
    val platformIntegrations: StelekitAppPlatformIntegrations = StelekitAppPlatformIntegrations(),
    val webSyncDeps: StelekitAppWebSyncDeps = StelekitAppWebSyncDeps(),
)

/**
 * Every [GraphContent] dependency. [repos], [fileSystem], [platformSettings], [graphManager], and
 * [notificationManager] have no sensible default (each active graph's composition root needs its
 * own real instances); everything else reuses [StelekitApp]'s own dependency groups, since
 * [GraphContent] is a straight pass-through for them.
 */
data class GraphContentDeps(
    val repos: RepositorySet,
    val fileSystem: FileSystem,
    val platformSettings: Settings,
    val graphManager: GraphManager,
    val notificationManager: NotificationManager,
    val onMemoryPressure: (((() -> Unit) -> Unit))? = null,
    val coreServices: StelekitAppCoreServices = StelekitAppCoreServices(),
    val voiceConfig: StelekitAppVoiceConfig = StelekitAppVoiceConfig(),
    val platformIntegrations: StelekitAppPlatformIntegrations = StelekitAppPlatformIntegrations(),
    val webSyncDeps: StelekitAppWebSyncDeps = StelekitAppWebSyncDeps(),
)
