// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.vault.VaultRootMigration
import dev.stapler.stelekit.platform.DemoFileSystem
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath

internal fun graphContentFileSystem(isDemo: Boolean, fileSystem: FileSystem): FileSystem =
    if (isDemo) DemoFileSystem() else fileSystem

/** Bundles [GraphContent]'s per-graph vault/crypto setup (Parameter Object pattern). */
internal class GraphContentVaultSetup(
    val activeGraphInfo: GraphInfo?,
    val activeGraphPath: String,
    val activeRepoRoot: RepoRoot?,
    val effectiveFileSystem: FileSystem,
    val isParanoidModeState: MutableState<Boolean>,
    val vaultStateState: MutableState<VaultState>,
    val vaultManagerState: MutableState<dev.stapler.stelekit.vault.VaultManager?>,
    val vaultCredentialStore: dev.stapler.stelekit.git.VaultCredentialStore?,
) {
    /** The active graph's notes folder as a typed value, for page writers; null when no graph is active. */
    val activeNotesPath: EffectiveNotesPath? get() = activeGraphInfo?.effectiveNotesPath
}

internal fun migrateAndResolveRepoRoot(info: GraphInfo?, fileSystem: FileSystem): RepoRoot? =
    info?.takeIf { !it.isDemo }?.let {
        VaultRootMigration(fileSystem).migrate(it.repoRootObj, it.effectiveNotesPath)
        it.repoRootObj
    }

/**
 * Resolves the active graph's info/path, picks the effective [FileSystem] (swapped for
 * [DemoFileSystem] when the active graph is the demo graph), and sets up paranoid-mode vault
 * state: whether paranoid mode is on (a `.stele-vault` file exists and a crypto engine is
 * available), the [VaultState] gating the unlock screen, the
 * [dev.stapler.stelekit.vault.VaultManager], and the vault-integrated credential store swapped
 * into `gitRepository.credentialAccess` on vault unlock/lock. Registers [vaultCredentialStore]
 * with [graphManager] as a side effect.
 */
@Composable
internal fun rememberGraphContentVaultSetup(
    graphManager: GraphManager,
    fileSystem: FileSystem,
    cryptoEngine: dev.stapler.stelekit.vault.CryptoEngine?,
): GraphContentVaultSetup {
    val graphRegistry by graphManager.graphRegistry.collectAsState()
    val activeGraphInfo = remember(graphRegistry) { graphManager.getActiveGraphInfo() }
    val activeGraphPath = activeGraphInfo?.effectiveNotesPath?.value ?: ""
    // Vault files live at the repo root (ADR-020); move any left in the notes folder before they are read.
    val activeRepoRoot: RepoRoot? = remember(activeGraphInfo) { migrateAndResolveRepoRoot(activeGraphInfo, fileSystem) }

    val effectiveFileSystem: FileSystem = remember(activeGraphInfo?.isDemo, fileSystem) {
        graphContentFileSystem(activeGraphInfo?.isDemo == true, fileSystem)
    }

    val vault = rememberVaultState(graphManager, fileSystem, cryptoEngine, activeRepoRoot)

    return GraphContentVaultSetup(
        activeGraphInfo = activeGraphInfo,
        activeGraphPath = activeGraphPath,
        activeRepoRoot = activeRepoRoot,
        effectiveFileSystem = effectiveFileSystem,
        isParanoidModeState = vault.isParanoidModeState,
        vaultStateState = vault.vaultStateState,
        vaultManagerState = vault.vaultManagerState,
        vaultCredentialStore = vault.vaultCredentialStore,
    )
}

/** The paranoid-mode/vault portion of [rememberGraphContentVaultSetup] — split out purely for length. */
private class VaultCoordinatorState(
    val isParanoidModeState: MutableState<Boolean>,
    val vaultStateState: MutableState<VaultState>,
    val vaultManagerState: MutableState<dev.stapler.stelekit.vault.VaultManager?>,
    val vaultCredentialStore: dev.stapler.stelekit.git.VaultCredentialStore?,
)

@Composable
private fun rememberVaultState(
    graphManager: GraphManager,
    fileSystem: FileSystem,
    cryptoEngine: dev.stapler.stelekit.vault.CryptoEngine?,
    activeRepoRoot: RepoRoot?,
): VaultCoordinatorState {
    // Paranoid mode: true when a .stele-vault file exists for this graph and a crypto engine is available.
    val isParanoidModeState = remember {
        mutableStateOf(
            cryptoEngine != null && activeRepoRoot != null &&
                fileSystem.fileExists(dev.stapler.stelekit.vault.VaultManager.vaultFilePath(activeRepoRoot))
        )
    }
    val isParanoidMode = isParanoidModeState.value

    // Vault state drives the unlock screen and gates graph loading.
    val vaultStateState = remember {
        mutableStateOf<VaultState>(
            if (isParanoidMode) VaultState.Locked else VaultState.Unlocked(dev.stapler.stelekit.vault.VaultNamespace.OUTER)
        )
    }

    val (vaultManagerState, vaultCredentialStore) =
        rememberVaultManagerAndCredentialStore(graphManager, fileSystem, cryptoEngine, activeRepoRoot, isParanoidModeState)

    return VaultCoordinatorState(isParanoidModeState, vaultStateState, vaultManagerState, vaultCredentialStore)
}

/** The [dev.stapler.stelekit.vault.VaultManager] + credential-store half of [rememberVaultState]. */
@Composable
private fun rememberVaultManagerAndCredentialStore(
    graphManager: GraphManager,
    fileSystem: FileSystem,
    cryptoEngine: dev.stapler.stelekit.vault.CryptoEngine?,
    activeRepoRoot: RepoRoot?,
    isParanoidModeState: androidx.compose.runtime.State<Boolean>,
): Pair<MutableState<dev.stapler.stelekit.vault.VaultManager?>, dev.stapler.stelekit.git.VaultCredentialStore?> {
    val isParanoidMode = isParanoidModeState.value
    val vaultManagerState = remember(isParanoidMode, cryptoEngine, fileSystem) {
        mutableStateOf(
            if (!isParanoidMode || cryptoEngine == null) null
            else dev.stapler.stelekit.vault.VaultManager(
                crypto = cryptoEngine,
                fileReadBytes = { path -> fileSystem.readFileBytes(path) },
                fileWriteBytes = { path, data -> fileSystem.writeFileBytes(path, data) },
            )
        )
    }

    // Vault-integrated credential store — non-null when paranoid mode is on and CryptoEngine is available.
    // Swapped into gitRepository.credentialAccess on vault unlock/lock.
    val vaultCredentialStore = remember(activeRepoRoot, isParanoidMode, cryptoEngine) {
        if (isParanoidMode && cryptoEngine != null && activeRepoRoot != null) {
            dev.stapler.stelekit.git.VaultCredentialStore(activeRepoRoot, cryptoEngine, fileSystem)
        } else null
    }

    LaunchedEffect(vaultCredentialStore) {
        graphManager.registerVaultCredentialStore(vaultCredentialStore)
    }

    return vaultManagerState to vaultCredentialStore
}
