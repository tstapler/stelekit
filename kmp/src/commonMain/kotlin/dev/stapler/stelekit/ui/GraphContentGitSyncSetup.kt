// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.GitSyncServiceRegistry
import dev.stapler.stelekit.git.ScheduledSyncPreferences
import dev.stapler.stelekit.git.ScheduledSyncStatus
import dev.stapler.stelekit.ui.components.settings.ScheduledSyncSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import dev.stapler.stelekit.git.effectiveScheduledIntervalMinutes
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Bundles [GraphContent]'s git-sync wiring for the active graph (Parameter Object pattern). */
internal class GraphContentGitSyncStack(
    val gitConfigRepository: GitConfigRepository?,
    val gitSyncGraphId: String,
    val gitSyncService: GitSyncService?,
    val scheduledSync: ScheduledSyncControls = ScheduledSyncControls.Unavailable,
)

/**
 * Test seams for the sync service the stack builds. Production leaves the defaults: the real
 * network probe and the service's own dispatcher.
 */
class GitSyncServiceSeams(
    val periodicSyncContext: CoroutineContext = EmptyCoroutineContext,
    val isOnline: (() -> Boolean)? = null,
    val wallClockMs: (() -> Long)? = null,
)

/**
 * What Settings needs to show and drive scheduled sync. [Unavailable] when the platform has no git.
 */
internal class ScheduledSyncControls(
    val hasRemote: Boolean,
    val enabled: Boolean,
    val configuredIntervalMinutes: Int,
    /** Shortest interval this platform honours (15 on Android because of WorkManager). */
    val minimumIntervalMinutes: Int,
    /** True when checks continue while the app is closed (an Android WorkManager job exists). */
    val closedAppChecks: Boolean,
    val backgroundRestricted: Boolean,
    val setEnabled: (Boolean) -> Unit,
    /** Persists the new poll interval to the graph's git config; the loop restarts from the config flow. */
    val setIntervalMinutes: suspend (Int) -> Unit,
) {
    companion object {
        val Unavailable = ScheduledSyncControls(
            hasRemote = false, enabled = false, configuredIntervalMinutes = 0, minimumIntervalMinutes = 1,
            closedAppChecks = false, backgroundRestricted = false, setEnabled = {}, setIntervalMinutes = {},
        )
    }
}

/**
 * Wires the git sync service for the active graph. Requires a platform-specific GitRepository
 * ([deps]' `platformIntegrations.gitRepository`); no-op (all-null bundle) when none is provided.
 * Registers/unregisters [GitSyncService] with the [GraphManager] and [GitSyncServiceRegistry] as the
 * composable enters/leaves composition, and starts the scheduled-sync loop and closed-app job when
 * `scheduled_sync_enabled` is on.
 */
@Composable
internal fun rememberGraphContentGitSyncStack(
    deps: GraphContentDeps,
    graphIoStack: GraphContentGraphIoStack,
    vaultCredentialStore: dev.stapler.stelekit.git.VaultCredentialStore?,
): GraphContentGitSyncStack {
    val gitRepository = deps.platformIntegrations.gitRepository
    val graphManager = deps.graphManager

    val gitConfigRepository = remember(gitRepository) {
        if (gitRepository == null) null else graphManager.createGitConfigRepository()
    }
    // Snapshot only — GraphContent is torn down and recreated by the parent's key(activeGraphId)
    // whenever the active graph changes, so this can't change during this instance's lifetime.
    val gitSyncGraphId = remember { graphManager.graphRegistry.value.activeGraphId?.value ?: "" }
    val gitSyncService = remember(gitConfigRepository) {
        buildGitSyncService(deps, graphIoStack, vaultCredentialStore, gitConfigRepository, gitSyncGraphId)
    }
    DisposableEffect(gitSyncService) {
        graphManager.registerGitSyncService(gitSyncService)
        // Keyed on the graph id captured at composition (not the live active graph) and removed only
        // while it still maps to this instance, so switching graphs leaves exactly the new entry.
        if (gitSyncService != null && gitSyncGraphId.isNotEmpty()) {
            GitSyncServiceRegistry.register(gitSyncGraphId, gitSyncService)
        }
        onDispose {
            gitSyncService?.shutdown()
            if (gitSyncService != null && gitSyncGraphId.isNotEmpty()) {
                GitSyncServiceRegistry.unregister(gitSyncGraphId, gitSyncService)
            }
            graphManager.registerGitSyncService(null)
        }
    }

    val scheduledSync = rememberGraphContentScheduledSyncControls(deps, gitSyncService, gitConfigRepository, gitSyncGraphId)
    return GraphContentGitSyncStack(gitConfigRepository, gitSyncGraphId, gitSyncService, scheduledSync)
}

/**
 * Settings model for the Scheduled sync section, or null when this platform has no git. Reads the
 * loop's status from the service; the buttons only forward to it, so no state lives here.
 */
@Composable
internal fun rememberScheduledSyncSettingsState(
    stack: GraphContentGitSyncStack,
    onSetUpGit: () -> Unit,
): ScheduledSyncSettingsState? {
    val controls = stack.scheduledSync
    val service = stack.gitSyncService
    val idle = remember { MutableStateFlow(ScheduledSyncStatus()) }
    val status by (service?.scheduledSyncStatus ?: idle).collectAsState()
    val scope = rememberCoroutineScope()
    if (service == null) return null
    return ScheduledSyncSettingsState(
        hasRemote = controls.hasRemote,
        enabled = controls.enabled,
        configuredIntervalMinutes = controls.configuredIntervalMinutes,
        minimumIntervalMinutes = controls.minimumIntervalMinutes,
        closedAppChecks = controls.closedAppChecks,
        backgroundRestricted = controls.backgroundRestricted,
        status = status,
        onEnabledChange = controls.setEnabled,
        onIntervalChange = { minutes -> scope.launch { controls.setIntervalMinutes(minutes) } },
        onResume = { scope.launch { service.resumeScheduledSync() } },
        onSetUpGit = onSetUpGit,
        onFix = onSetUpGit,
    )
}

private sealed interface ConfigLoad {
    data object Loading : ConfigLoad
    data class Loaded(val config: GitConfig?) : ConfigLoad
}

/**
 * Starts, restarts and stops the in-app loop and the closed-app job from `(service, flag, interval)`.
 * One composition owns one loop: an interval edit restarts it (the next check is one new interval
 * away), turning the switch off stops both, and disposal stops the loop through the service's
 * `shutdown()`. The closed-app job deliberately outlives the composition.
 */
@Composable
internal fun rememberGraphContentScheduledSyncControls(
    deps: GraphContentDeps,
    service: GitSyncService?,
    configRepository: GitConfigRepository?,
    graphId: String,
): ScheduledSyncControls {
    if (configRepository == null) return ScheduledSyncControls.Unavailable

    val factory = deps.platformIntegrations.backgroundSyncSchedulerFactory
    val probe = deps.platformIntegrations.backgroundRestrictionProbe
    val prefs = remember(deps.platformSettings) { ScheduledSyncPreferences(deps.platformSettings) }
    val enabled by prefs.enabled.collectAsState()
    val configLoad: State<ConfigLoad> = remember(configRepository, graphId) {
        configRepository.observeConfig(graphId).map { ConfigLoad.Loaded(it.getOrNull()) }
    }.collectAsState(ConfigLoad.Loading)
    val config = (configLoad.value as? ConfigLoad.Loaded)?.config
    val loaded = configLoad.value is ConfigLoad.Loaded
    val interval = config?.pollIntervalMinutes?.let { effectiveScheduledIntervalMinutes(it, factory.minimumIntervalMinutes) }
    val active = enabled && interval != null && interval > 0

    val loopRunning = remember(service) { booleanArrayOf(false) }
    LaunchedEffect(service, active, interval) {
        if (service == null || !loaded) return@LaunchedEffect
        if (active && interval != null) {
            // First start after enable/open checks right away; an interval edit waits one new interval.
            service.startPeriodicSync(graphId, interval, immediateFirstTick = !loopRunning[0])
            loopRunning[0] = true
        } else {
            service.stopPeriodicSync()
            loopRunning[0] = false
        }
    }

    val scheduler = remember(factory, graphId) { factory.create(graphId) }
    LaunchedEffect(scheduler, active, interval, loaded) {
        if (scheduler == null || !loaded) return@LaunchedEffect
        if (active && interval != null) scheduler.schedule(interval) else scheduler.cancel()
    }

    val restricted by produceState(false, probe) {
        if (probe == null) return@produceState
        while (true) {
            value = probe.isRestricted()
            delay(RESTRICTION_POLL_MILLIS)
        }
    }

    return ScheduledSyncControls(
        hasRemote = config != null,
        enabled = enabled,
        configuredIntervalMinutes = config?.pollIntervalMinutes ?: 0,
        minimumIntervalMinutes = factory.minimumIntervalMinutes,
        closedAppChecks = scheduler != null,
        backgroundRestricted = restricted,
        setEnabled = prefs::setEnabled,
        setIntervalMinutes = { minutes ->
            configRepository.getConfig(graphId).getOrNull()?.let { configRepository.saveConfig(it.copy(pollIntervalMinutes = minutes)) }
        },
    )
}

private const val RESTRICTION_POLL_MILLIS = 30_000L

private fun buildGitSyncService(
    deps: GraphContentDeps,
    graphIoStack: GraphContentGraphIoStack,
    vaultCredentialStore: dev.stapler.stelekit.git.VaultCredentialStore?,
    gitConfigRepository: GitConfigRepository?,
    gitSyncGraphId: String,
): GitSyncService? {
    val gitRepository = deps.platformIntegrations.gitRepository
    if (gitRepository == null || gitConfigRepository == null) return null
    val seams = deps.gitSyncSeams
    val networkMonitor = dev.stapler.stelekit.platform.NetworkMonitor()
    return GitSyncService(
        gitRepository = gitRepository,
        graphLoader = graphIoStack.graphLoader,
        graphWriter = graphIoStack.graphWriter,
        editLock = dev.stapler.stelekit.git.EditLock(),
        configRepository = gitConfigRepository,
        networkMonitor = networkMonitor,
        fileSystem = deps.fileSystem,
        credentialAccessProvider = { vaultCredentialStore ?: dev.stapler.stelekit.platform.security.CredentialStore() },
        graphId = gitSyncGraphId,
        settings = deps.platformSettings,
        // CRITICAL finding (PR #327 review): must be the same instance a host passes as
        // gitSyncBusyCounter to createAndroidGraphMoveQuiesceStrategy(...), or the quiesce
        // strategy's awaitIdle() never observes this service's sync() activity — see
        // StelekitAppPlatformIntegrations.gitSyncBusyCounter's doc. Falling back to a fresh
        // instance (Desktop/iOS, or before a host wires one) matches GitSyncService's own default.
        gitSyncBusyCounter = deps.platformIntegrations.gitSyncBusyCounter ?: dev.stapler.stelekit.git.GitSyncBusyCounter(),
        isOnline = seams.isOnline ?: { networkMonitor.isOnline },
        periodicSyncContext = seams.periodicSyncContext,
        wallClockMs = seams.wallClockMs ?: { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    )
}
