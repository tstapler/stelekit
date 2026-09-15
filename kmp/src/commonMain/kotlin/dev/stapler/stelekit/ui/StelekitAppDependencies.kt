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
import dev.stapler.stelekit.capture.HotkeyRegistrationFailure
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphMoveQuiesceStrategy
import dev.stapler.stelekit.db.StorageLocationResolver
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
    /** Called once the [NotificationManager] instance is ready — Desktop wires this to
     * `CaptureController.attachNotificationManager` so capture saves can surface a toast. */
    val onNotificationManagerReady: ((NotificationManager) -> Unit)? = null,
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
    /**
     * Platform quiesce port for Story 3.1.3/3.1.5's `GraphRelocationCoordinator` — pass
     * `createAndroidGraphMoveQuiesceStrategy(...)` on Android, `createWasmJsGraphMoveQuiesceStrategy(...)`
     * on Web. Null (Desktop/iOS, or before a host wires one) means [GraphContent] never constructs a
     * coordinator, so the "Move storage location…" entry points in [Sidebar]/[FolderSyncSettings]
     * call their `onStorageLocationChoose` no-op default instead.
     */
    val graphMoveQuiesceStrategy: GraphMoveQuiesceStrategy? = null,
    /**
     * Epic 4.1 (Task 4.1.1a): platform seam for [GraphRelocationCoordinator.link] — pass
     * `createWasmJsHostLinkStep(fileSystem.hostDirectorySync)` on Web, `createAndroidHostLinkStep()`
     * on Android (Epic 4.2, Story 4.2.1 — a no-op success for `GitShadowWorktree`'s already-running
     * write-back-to-SAF cache mode, with `persistsDestinationOnSuccess = false` so Link never
     * repoints `storage_locations`). Null (Desktop/iOS, or a host that hasn't wired one) means a
     * `Link` operation always fails fast.
     */
    val hostLinkStep: dev.stapler.stelekit.db.HostLinkStep? = null,
    /**
     * Resolves/backfills a graph's real [StorageLocation] before "Move storage location…" opens —
     * Story 3.2.2/3.3.3. Pass `createAndroidStorageLocationResolver(...)` on Android,
     * `createWasmJsStorageLocationResolver(...)` on Web. Null leaves the button working (per
     * `GraphSwitcher`'s own doc) but falls back to an `AppOwned` placeholder source.
     */
    val storageLocationResolver: StorageLocationResolver? = null,
    /**
     * MAJOR finding (PR #327 review): pre-flight free-space check for
     * `GraphRelocationCoordinator`'s default `BulkCopyVerifier` — pass `AndroidInsufficientSpaceCheck()`
     * on Android, `WasmJsInsufficientSpaceCheck()` on Web. `null` (Desktop/iOS, or before a host
     * wires one) means the coordinator never checks free space before copying, mirroring
     * [graphMoveQuiesceStrategy]/[hostLinkStep]'s "off unless a platform explicitly supplies one"
     * convention.
     */
    val insufficientSpaceCheck: dev.stapler.stelekit.db.InsufficientSpaceCheck? = null,
    /**
     * CRITICAL finding (PR #327 review): the shared [dev.stapler.stelekit.git.GitSyncBusyCounter]
     * instance [GraphContent] must inject into the active graph's `GitSyncService(...)` — it must
     * be the SAME instance a host passes as `gitSyncBusyCounter` to
     * `createAndroidGraphMoveQuiesceStrategy(...)` (Android) so
     * `AndroidGraphMoveQuiesceStrategy.quiesce()` actually observes real sync activity instead of
     * awaiting an always-idle counter nobody increments. Construct it once at the composition root
     * (`MainActivity.kt`, same `remember` scope as [graphMoveQuiesceStrategy]) and pass the same
     * reference to both construction sites. `null` (default; Desktop/iOS, or before a host wires
     * one) makes `GitSyncService` fall back to its own private instance, mirroring
     * [graphMoveQuiesceStrategy]/[insufficientSpaceCheck]'s "off unless a platform explicitly
     * supplies one" convention.
     */
    val gitSyncBusyCounter: dev.stapler.stelekit.git.GitSyncBusyCounter? = null,
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
    /**
     * Epic 4.1 (Task 4.1.2c): "Unlink folder" affordance — invoked from `SettingsDialog`'s
     * `FolderSyncSettings` section for a graph that's currently linked. Should perform
     * `HostDirectorySync.unlinkHostDirectory()` and, on success, persist the graph's
     * `storage_locations` row back to `StorageLocation.AppOwned` via
     * `GraphManager.onGraphLocationDetermined` (`unlinkHostDirectoryAndPersist` on web). Pass a
     * lambda wrapping that on web. When null, `FolderSyncSettings`'s "Unlink folder" section
     * renders nothing.
     */
    val onUnlinkHostDirectory: (suspend () -> Unit)? = null,
)

/**
 * Desktop quick-capture hotkey wiring (Stories 1.4.2/1.4.3) — every field defaults to "feature
 * absent" (no failure ever surfaced, a literal fallback label) since `GlobalHotkeyListener` is
 * jvmMain-only and this file is commonMain. A real desktop host wires
 * `hotkeyComboLabel = GlobalHotkeyListener.DEFAULT_COMBO_LABEL` and
 * `hotkeyRegistrationFailure = jKeymasterHotkeyListener.registrationFailure` from jvmMain, where
 * both types are visible — see `design/ux.md` Surface 2's "never hand-typed separately" note.
 */
data class StelekitAppCaptureDeps(
    val hotkeyComboLabel: String = "Ctrl+Shift+Space",
    val hotkeyRegistrationFailure: StateFlow<HotkeyRegistrationFailure?>? = null,
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
    val captureDeps: StelekitAppCaptureDeps = StelekitAppCaptureDeps(),
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
    /** Survives graph switches (created once in [StelekitApp], above the `key(activeGraphId)`
     * that tears [GraphContent] down and recreates it) so a page snapshot taken on one graph
     * is still there after switching to the merge target. */
    val graphMergeService: dev.stapler.stelekit.transfer.GraphMergeService = dev.stapler.stelekit.transfer.GraphMergeService(),
    /** Threaded to `SettingsDialog`'s "Keyboard Shortcuts" row — see [StelekitAppCaptureDeps]. */
    val hotkeyComboLabel: String? = null,
)
