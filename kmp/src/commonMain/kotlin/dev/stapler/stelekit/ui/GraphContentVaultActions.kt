// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import arrow.core.Either
import dev.stapler.stelekit.db.GraphEpoch
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.security.CredentialStore
import dev.stapler.stelekit.vault.CryptoLayer
import dev.stapler.stelekit.vault.VaultError
import dev.stapler.stelekit.vault.VaultManager
import dev.stapler.stelekit.vault.VaultNamespace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Non-state collaborators the vault action handlers need (bundled for parameter-count relief). */
internal class GraphContentVaultEnv(val scope: CoroutineScope, val graphContentLogger: Logger)

/** Everything [unlockVault]/[createVault] need about the current graph (bundled for parameter-count relief). */
private class VaultActionContext(
    val vaultSetup: GraphContentVaultSetup,
    val graphIoStack: GraphContentGraphIoStack,
    val deps: GraphContentDeps,
    val graphContentLogger: Logger,
)

/** Bundles [GraphContent]'s vault settings callbacks — threaded into SettingsDialog via GraphDialogLayer. */
internal class GraphContentVaultActions(
    val onVaultUnlock: (passphrase: CharArray, namespace: VaultNamespace) -> Unit,
    val onCreateVault: (suspend (CharArray) -> Either<VaultError, Unit>)?,
    val onAddKeyslot: (suspend (CharArray) -> Either<VaultError, Unit>)?,
    val onRemoveKeyslot: (suspend (Int) -> Either<VaultError, Unit>)?,
    val onLockVault: (() -> Unit)?,
    val onListActiveSlots: (suspend () -> List<Int>)?,
)

/**
 * Builds the vault unlock/create/keyslot/lock handlers for [GraphContent], plus the two
 * lifecycle effects that react to vault state: closing the CryptoLayer when the vault locks (so
 * loader/writer don't use the zeroed DEK VaultManager.lock() leaves behind), and bootstrapping
 * `loadGraph` once a paranoid-mode graph is unlocked (or immediately, for a non-paranoid graph —
 * see [BootstrapGraphLoad]).
 */
@Composable
internal fun rememberGraphContentVaultActions(
    vaultSetup: GraphContentVaultSetup,
    graphIoStack: GraphContentGraphIoStack,
    deps: GraphContentDeps,
    env: GraphContentVaultEnv,
    viewModel: StelekitViewModel,
): GraphContentVaultActions {
    val ctx = VaultActionContext(vaultSetup, graphIoStack, deps, env.graphContentLogger)
    var isParanoidMode by vaultSetup.isParanoidModeState
    var vaultState by vaultSetup.vaultStateState
    var vaultManager by vaultSetup.vaultManagerState
    val cryptoEngine = deps.platformIntegrations.cryptoEngine
    val activeGraphPath = vaultSetup.activeGraphPath

    BootstrapGraphLoad(vaultSetup, viewModel)
    ObserveVaultLockCleanup(ctx)

    val onVaultUnlock: (CharArray, VaultNamespace) -> Unit = handler@{ passphrase, _ ->
        val vm = vaultManager ?: run { passphrase.fill(' '); return@handler }
        val engine = cryptoEngine ?: run { passphrase.fill(' '); return@handler }
        vaultState = VaultState.Unlocking
        env.scope.launch {
            // Throwable (not just Exception) is caught below: an uncaught Throwable on this plain
            // rememberCoroutineScope() (no CoroutineExceptionHandler) would otherwise kill the
            // Android process — see GraphContentCameraCapture's saveCapturedImage for the same
            // pattern/rationale.
            vaultState = try {
                unlockVault(ctx, vm, engine, passphrase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                env.graphContentLogger.error("Vault unlock crashed: ${e.message}", e)
                VaultState.Error(VaultError.InvalidCredential("Unlock failed — try again"))
            }
        }
    }

    val onCreateVault: (suspend (CharArray) -> Either<VaultError, Unit>)? =
        if (cryptoEngine != null && activeGraphPath.isNotEmpty()) {
            { passphrase -> createVault(ctx, cryptoEngine, passphrase) { newManager -> vaultManager = newManager; isParanoidMode = true } }
        } else null

    // Gate once here — the four handlers below all need "vault manager, only when paranoid mode
    // is actually on" and take it pre-gated rather than a separate isParanoidMode flag argument.
    val activeVaultManager = vaultManager.takeIf { isParanoidMode }

    return GraphContentVaultActions(
        onVaultUnlock = onVaultUnlock,
        onCreateVault = onCreateVault,
        onAddKeyslot = buildAddKeyslotHandler(activeVaultManager, activeGraphPath),
        onRemoveKeyslot = buildRemoveKeyslotHandler(activeVaultManager, activeGraphPath),
        onLockVault = buildLockVaultHandler(activeVaultManager, ctx, env.scope),
        onListActiveSlots = buildListActiveSlotsHandler(activeVaultManager, activeGraphPath),
    )
}

private fun buildAddKeyslotHandler(
    vaultManager: VaultManager?,
    activeGraphPath: String,
): (suspend (CharArray) -> Either<VaultError, Unit>)? {
    if (vaultManager == null) return null
    return { passphrase ->
        val dek = vaultManager.currentDek()
        if (dek == null) Either.Left(VaultError.InvalidCredential("Vault is locked"))
        else vaultManager.addKeyslot(activeGraphPath, dek, passphrase)
    }
}

private fun buildRemoveKeyslotHandler(
    vaultManager: VaultManager?,
    activeGraphPath: String,
): (suspend (Int) -> Either<VaultError, Unit>)? {
    if (vaultManager == null) return null
    return { slotIndex -> vaultManager.removeKeyslot(activeGraphPath, slotIndex) }
}

private fun buildLockVaultHandler(
    vaultManager: VaultManager?,
    ctx: VaultActionContext,
    scope: CoroutineScope,
): (() -> Unit)? {
    if (vaultManager == null) return null
    return {
        scope.launch {
            // Throwable (not just Exception) is caught below: an uncaught Throwable on this plain
            // rememberCoroutineScope() (no CoroutineExceptionHandler) would otherwise kill the
            // Android process — see GraphContentCameraCapture's saveCapturedImage for the same
            // pattern/rationale.
            try {
                ctx.graphIoStack.graphWriter.flush()
                ctx.graphIoStack.graphLoader.closeAndClearCryptoLayer()
                ctx.graphIoStack.graphWriter.closeAndClearCryptoLayer()
                vaultManager.lock()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ctx.graphContentLogger.error("Vault lock crashed: ${e.message}", e)
            }
        }
        Unit
    }
}

private fun buildListActiveSlotsHandler(
    vaultManager: VaultManager?,
    activeGraphPath: String,
): (suspend () -> List<Int>)? {
    if (vaultManager == null) return null
    return { vaultManager.listActiveKeyslotIndices(activeGraphPath) }
}

/**
 * Bootstrap loadGraph when the ViewModel has no persisted path but GraphManager has an active
 * graph. For paranoid-mode graphs, loading is deferred until after unlock (the second effect
 * below) so the CryptoLayer is in place before any file reads.
 */
@Composable
private fun BootstrapGraphLoad(vaultSetup: GraphContentVaultSetup, viewModel: StelekitViewModel) {
    val isParanoidMode = vaultSetup.isParanoidModeState.value
    LaunchedEffect(Unit) {
        if (!isParanoidMode && viewModel.uiState.value.currentGraphPath == null) {
            vaultSetup.activeGraphPath.ifEmpty { null }?.let { viewModel.setGraphPath(it) }
        }
    }
    // After successful vault unlock, inject CryptoLayer into loader/writer then load graph.
    val vaultState = vaultSetup.vaultStateState.value
    LaunchedEffect(vaultState) {
        if (vaultState is VaultState.Unlocked && isParanoidMode && viewModel.uiState.value.currentGraphPath == null) {
            val path = vaultSetup.activeGraphPath.ifEmpty { null } ?: return@LaunchedEffect
            viewModel.setGraphPath(path)
        }
    }
}

/**
 * When the vault locks, null out CryptoLayer references so loader/writer do not use the zeroed
 * DEK left behind by VaultManager.lock(). Subscribes to vaultEvents so the cleanup runs even when
 * lock() is triggered programmatically (not via vaultState).
 */
@Composable
private fun ObserveVaultLockCleanup(ctx: VaultActionContext) {
    val vaultManager = ctx.vaultSetup.vaultManagerState.value
    val gitRepository = ctx.deps.platformIntegrations.gitRepository
    LaunchedEffect(vaultManager) {
        vaultManager?.vaultEvents?.collect { event ->
            if (event is dev.stapler.stelekit.vault.VaultManager.VaultEvent.Locked) {
                // DEK is already zeroed at this point — do not flush (would write with zero-key).
                // The primary lock path (user-initiated) already flushed before calling lock().
                // closeAndClearCryptoLayer() zeroes the CryptoLayer's owned DEK copy then nulls it.
                ctx.graphIoStack.graphLoader.closeAndClearCryptoLayer()
                ctx.graphIoStack.graphWriter.closeAndClearCryptoLayer()
                ctx.vaultSetup.vaultCredentialStore?.onVaultLocked()
                // Revert git repository to PBKDF2 fallback store
                gitRepository?.setCredentialAccess(CredentialStore())
                ctx.vaultSetup.vaultStateState.value = VaultState.Locked   // show lock/unlock screen; gates graph content
            }
        }
    }
}

/**
 * Unlock handler body — called from VaultUnlockScreen via [rememberGraphContentVaultActions]'s
 * `onVaultUnlock`. The namespace arg is UI-only (OUTER vs HIDDEN button); VaultManager determines
 * the actual namespace from the keyslot that decrypts.
 */
private suspend fun unlockVault(
    ctx: VaultActionContext,
    vaultManager: VaultManager,
    cryptoEngine: dev.stapler.stelekit.vault.CryptoEngine,
    passphrase: CharArray,
): VaultState = when (val result = vaultManager.unlock(ctx.vaultSetup.activeGraphPath, passphrase)) {
    is Either.Right -> {
        val unlockResult = result.value
        val layer = CryptoLayer(cryptoEngine, unlockResult.dek)
        // Set graph paths before cryptoLayer so any concurrent reader that observes cryptoLayer
        // != null will also see the correct graphPath (used as AAD base).
        seedGraphEpochForVaultEvent(ctx, "onVaultUnlock")
        ctx.graphIoStack.graphLoader.setGraphPath(ctx.vaultSetup.activeGraphPath)
        ctx.graphIoStack.graphLoader.setCryptoLayer(layer)
        ctx.graphIoStack.graphWriter.setCryptoLayer(layer)
        ctx.vaultSetup.vaultCredentialStore?.onVaultUnlocked(unlockResult.dek)
        ctx.deps.platformIntegrations.gitRepository?.setCredentialAccess(ctx.vaultSetup.vaultCredentialStore ?: CredentialStore())
        VaultState.Unlocked(unlockResult.namespace)
    }
    is Either.Left -> VaultState.Error(result.value)
}

/**
 * GraphId is sourced only from GraphManager.getActiveGraphInfo()?.id — never derived from
 * activeGraphPath — per the GraphId smart-constructor discipline. Shared by [unlockVault] and
 * [applyCreatedVault] (identical rationale).
 */
private fun seedGraphEpochForVaultEvent(ctx: VaultActionContext, callerName: String) {
    val activeGraphId = ctx.vaultSetup.activeGraphInfo?.id
    if (activeGraphId == null) {
        ctx.graphContentLogger.error("$callerName: no active graph — cannot establish GraphEpoch")
        return
    }
    val writer = ctx.graphIoStack.graphWriter
    writer.currentEpoch = GraphEpoch(
        graphId = activeGraphId,
        graphPath = ctx.vaultSetup.activeGraphPath,
        sequence = (writer.currentEpoch?.sequence ?: 0L) + 1,
    )
}

private suspend fun createVault(
    ctx: VaultActionContext,
    cryptoEngine: dev.stapler.stelekit.vault.CryptoEngine,
    passphrase: CharArray,
    onVaultCreated: (VaultManager) -> Unit,
): Either<VaultError, Unit> {
    val tempManager = VaultManager(
        crypto = cryptoEngine,
        fileReadBytes = { path -> ctx.deps.fileSystem.readFileBytes(path) },
        fileWriteBytes = { path, data -> ctx.deps.fileSystem.writeFileBytes(path, data) },
    )
    return when (val result = tempManager.createVault(ctx.vaultSetup.activeGraphPath, passphrase)) {
        is Either.Right -> {
            applyCreatedVault(ctx, tempManager, cryptoEngine, result.value, onVaultCreated)
            Either.Right(Unit)
        }
        is Either.Left -> result
    }
}

private fun applyCreatedVault(
    ctx: VaultActionContext,
    tempManager: VaultManager,
    cryptoEngine: dev.stapler.stelekit.vault.CryptoEngine,
    unlockResult: VaultManager.UnlockResult,
    onVaultCreated: (VaultManager) -> Unit,
) {
    val layer = CryptoLayer(cryptoEngine, unlockResult.dek)
    // GraphId sourced only from GraphManager.getActiveGraphInfo()?.id — see
    // unlockVault's identical rationale above.
    seedGraphEpochForVaultEvent(ctx, "onCreateVault")
    ctx.graphIoStack.graphLoader.setGraphPath(ctx.vaultSetup.activeGraphPath)
    ctx.graphIoStack.graphLoader.setCryptoLayer(layer)
    ctx.graphIoStack.graphWriter.setCryptoLayer(layer)
    ctx.vaultSetup.vaultCredentialStore?.onVaultUnlocked(unlockResult.dek)
    // Swap git repository credential access to vault store
    ctx.deps.platformIntegrations.gitRepository?.setCredentialAccess(ctx.vaultSetup.vaultCredentialStore ?: CredentialStore())
    // Migrate existing credentials from PBKDF2 store into vault
    val graphId = ctx.deps.graphManager.getActiveGraphId()
    if (graphId != null) {
        ctx.vaultSetup.vaultCredentialStore?.migrateFrom(
            source = CredentialStore(),
            keys = listOf("git_https_token_${graphId.value}", "git_ssh_passphrase_${graphId.value}"),
        )
    }
    onVaultCreated(tempManager)
    ctx.vaultSetup.vaultStateState.value = VaultState.Unlocked(unlockResult.namespace)
}
