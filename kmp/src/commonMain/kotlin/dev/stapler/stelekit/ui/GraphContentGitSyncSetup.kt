// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/** Bundles [GraphContent]'s git-sync wiring for the active graph (Parameter Object pattern). */
internal class GraphContentGitSyncStack(
    val gitConfigRepository: dev.stapler.stelekit.git.GitConfigRepository?,
    val gitSyncGraphId: String,
    val gitSyncService: dev.stapler.stelekit.git.GitSyncService?,
)

/**
 * Wires the git sync service for the active graph. Requires a platform-specific GitRepository
 * ([deps]' `platformIntegrations.gitRepository`); no-op (all-null bundle) when none is provided.
 * Registers/unregisters [dev.stapler.stelekit.git.GitSyncService] with the [GraphManager] as the
 * composable enters/leaves composition.
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
        onDispose {
            gitSyncService?.shutdown()
            graphManager.registerGitSyncService(null)
        }
    }

    return GraphContentGitSyncStack(gitConfigRepository, gitSyncGraphId, gitSyncService)
}

private fun buildGitSyncService(
    deps: GraphContentDeps,
    graphIoStack: GraphContentGraphIoStack,
    vaultCredentialStore: dev.stapler.stelekit.git.VaultCredentialStore?,
    gitConfigRepository: dev.stapler.stelekit.git.GitConfigRepository?,
    gitSyncGraphId: String,
): dev.stapler.stelekit.git.GitSyncService? {
    val gitRepository = deps.platformIntegrations.gitRepository
    if (gitRepository == null || gitConfigRepository == null) return null
    return dev.stapler.stelekit.git.GitSyncService(
        gitRepository = gitRepository,
        graphLoader = graphIoStack.graphLoader,
        graphWriter = graphIoStack.graphWriter,
        editLock = dev.stapler.stelekit.git.EditLock(),
        configRepository = gitConfigRepository,
        networkMonitor = dev.stapler.stelekit.platform.NetworkMonitor(),
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
    )
}
