// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.toSyncErrorMessage
import dev.stapler.stelekit.git.CloneProgress
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.GitCredentialConnectionStore
import dev.stapler.stelekit.git.GitHubDeviceFlowClient
import dev.stapler.stelekit.git.GitRepoHistoryStore
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.GitTransportRetryState
import dev.stapler.stelekit.git.model.CloneDepthState
import dev.stapler.stelekit.git.model.DEFAULT_CLONE_DEPTH
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.GitRepoHistoryKind
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

internal val gitSetupLogger = Logger("GitSetupScreen")

/**
 * Snapshot of the wizard's Step 2–4 form fields, read once at the moment "Test connection" or
 * "Save configuration" is clicked. Step 5 disables Back/Save while a test or save is in flight, so
 * none of these fields are editable again until the in-flight operation completes — a snapshot
 * taken up front is equivalent to re-reading each field after every suspend point.
 */
internal data class GitSetupFormSnapshot(
    val graphId: String,
    val cloneMode: CloneMode,
    val cloneUrl: String,
    val repoRoot: String,
    val wikiSubdir: String,
    val graphName: String,
    val graphDescription: String,
    val cloneStorageLocation: StorageLocation?,
    val authType: GitAuthType,
    val sshKeyPath: String,
    val sshPassphrase: String,
    val httpsToken: String,
    val oauthConnectedAs: String?,
    val selectedHttpsConnectionId: String?,
    val selectedOauthConnectionId: String?,
    val remoteBranch: String,
    val pollIntervalMinutes: Int,
)

/**
 * Tests the wizard's current configuration without saving it. Cloning a new repo (vs. pointing at
 * one already cloned on disk): no local repository exists at `form.repoRoot` yet — it's only
 * created by the clone on Save. `fetch()` requires `Git.open()`-ing an existing repo, so it would
 * always fail here with a "repository not found" error unrelated to the actual remote/auth being
 * tested. `testRemote()` checks the remote directly instead (ls-remote semantics — no local repo
 * needed).
 */
internal suspend fun testGitConnection(
    form: GitSetupFormSnapshot,
    gitRepository: GitRepository,
    credentialStore: CredentialStore,
): Either<DomainError.GitError, Unit> {
    if (form.cloneMode == CloneMode.CloneNewRepository) {
        val cloneAuth = buildCloneAuth(
            form.authType, form.httpsToken, form.sshKeyPath, form.sshPassphrase, form.graphId, credentialStore,
        )
        return gitRepository.testRemote(form.cloneUrl, cloneAuth)
    }

    val testHttpsTokenKey = if (form.authType == GitAuthType.HTTPS_TOKEN && form.httpsToken.isNotBlank()) {
        val key = "git_https_token_${form.graphId}"
        credentialStore.store(key, form.httpsToken)
        key
    } else null
    val testSshPassphraseKey = if (form.authType == GitAuthType.SSH_KEY && form.sshPassphrase.isNotBlank()) {
        val key = "git_ssh_passphrase_${form.graphId}"
        credentialStore.store(key, form.sshPassphrase)
        key
    } else null
    val testOauthTokenKey = if (form.authType == GitAuthType.GITHUB_OAUTH && form.oauthConnectedAs != null) {
        "git_github_oauth_${form.graphId}"
    } else null
    val config = buildConfig(
        form.graphId, form.repoRoot, form.wikiSubdir, form.authType,
        form.sshKeyPath, form.remoteBranch, form.pollIntervalMinutes,
        httpsTokenKey = testHttpsTokenKey,
        sshKeyPassphraseKey = testSshPassphraseKey,
        oauthTokenKey = testOauthTokenKey,
    )
    return gitRepository.fetch(config).map { Unit }
}

/** Outcome of [performCloneAndSave], reported back to the composable for UI/state updates. */
internal sealed class CloneAndSaveOutcome {
    data class CloneFailed(val message: String) : CloneAndSaveOutcome()
    data class Saved(val newGraphId: String) : CloneAndSaveOutcome()
    data class SaveFailed(val newGraphId: String) : CloneAndSaveOutcome()

    /** Story 4.1.4: the user tapped Cancel mid-clone — `classifyGitFailure` saw the resulting
     * `CanceledException`/`GitFailureClass.Cancelled`, so `onCloneAndAdd` rethrew a
     * `kotlinx.coroutines.CancellationException` rather than returning an `Either.Left`. Not a
     * failure the user needs to react to — Step 5 shows "Cancelled — your progress is saved." */
    data object Cancelled : CloneAndSaveOutcome()
}

/** Outcome of [performSaveExistingConfig], reported back to the composable for UI/state updates. */
internal sealed class SaveConfigOutcome {
    data object Saved : SaveConfigOutcome()
    data object Failed : SaveConfigOutcome()
}

/**
 * Bundles the app-wide store dependencies [resolveAndSaveConfig] and its two callers share — one
 * param instead of four, so adding [repoHistoryStore] didn't push those functions over the
 * long-parameter-list check.
 */
internal data class GitSetupStores(
    val credentialStore: CredentialStore,
    val connectionStore: GitCredentialConnectionStore,
    val gitConfigRepository: GitConfigRepository,
    val repoHistoryStore: GitRepoHistoryStore,
)

/**
 * Resolves credential keys for [graphId] (reusing [existingConfig]'s keys as the fallback when a
 * field wasn't touched — null when there's no prior config, i.e. the clone-and-add path) and
 * persists a [GitConfig] built from [form]. Shared by [performCloneAndSave] (`existingConfig =
 * null`, `graphId` = the just-created graph) and [performSaveExistingConfig] (`graphId =
 * form.graphId`) — both paths resolve credential keys and call `saveConfig` identically; only what
 * happens after a successful save differs (a background fetch, vs. an `onCloneComplete` callback).
 *
 * [cloneDepthState] defaults to preserving [existingConfig]'s own value (or [CloneDepthState.None]
 * when there is none) — [performSaveExistingConfig] relies on this default so re-saving an
 * already-cloned graph's settings never resets its checkpoint state. [performCloneAndSave] passes
 * an explicit [CloneDepthState.Shallow] instead, since a fresh clone just ran (Task 2.1.2f).
 */
private suspend fun resolveAndSaveConfig(
    graphId: String,
    form: GitSetupFormSnapshot,
    existingConfig: GitConfig?,
    stores: GitSetupStores,
    cloneDepthState: CloneDepthState = existingConfig?.cloneDepthState ?: CloneDepthState.None,
): Either<DomainError, Unit> {
    val httpsTokenKey = if (form.authType == GitAuthType.HTTPS_TOKEN) {
        resolveHttpsTokenKey(
            graphId = graphId,
            httpsToken = form.httpsToken,
            cloneUrl = form.cloneUrl,
            selectedConnectionId = form.selectedHttpsConnectionId,
            connectionStore = stores.connectionStore,
            credentialStore = stores.credentialStore,
            fallbackKey = existingConfig?.httpsTokenKey,
        )
    } else {
        existingConfig?.httpsTokenKey
    }
    val sshPassphraseKey = if (form.authType == GitAuthType.SSH_KEY) {
        resolveSshPassphraseKey(graphId, form.sshPassphrase, existingConfig, stores.credentialStore)
    } else {
        existingConfig?.sshKeyPassphraseKey
    }
    val oauthTokenKey = if (form.authType == GitAuthType.GITHUB_OAUTH) {
        resolveOauthTokenKey(
            graphId = graphId,
            selectedConnectionId = form.selectedOauthConnectionId,
            connectionStore = stores.connectionStore,
            credentialStore = stores.credentialStore,
            fallbackKey = existingConfig?.oauthTokenKey ?: "git_github_oauth_$graphId",
        )
    } else {
        null
    }
    val config = buildConfig(
        graphId, form.repoRoot, form.wikiSubdir, form.authType,
        form.sshKeyPath, form.remoteBranch, form.pollIntervalMinutes,
        httpsTokenKey = httpsTokenKey,
        sshKeyPassphraseKey = sshPassphraseKey,
        oauthTokenKey = oauthTokenKey,
        cloneDepthState = cloneDepthState,
    )
    return stores.gitConfigRepository.saveConfig(config).onRight { recordRepoHistory(form, stores.repoHistoryStore) }
}

/**
 * Remembers [form]'s repository location once it's actually been saved successfully — the
 * destination clone URL when cloning a new repo, the local path when pointing at one already on
 * disk (see `GitRepoHistoryStore`'s KDoc for why these are tracked separately).
 */
private fun recordRepoHistory(form: GitSetupFormSnapshot, repoHistoryStore: GitRepoHistoryStore) {
    val (value, kind) = if (form.cloneMode == CloneMode.CloneNewRepository) {
        form.cloneUrl to GitRepoHistoryKind.CLONE_URL
    } else {
        form.repoRoot to GitRepoHistoryKind.LOCAL_PATH
    }
    repoHistoryStore.record(value, kind, form.wikiSubdir, Clock.System.now().toEpochMilliseconds())
}

/**
 * Clones [form.cloneUrl] to [form.repoRoot] via [onCloneAndAdd], then persists a [GitConfig] for
 * the newly created graph. [onCloneProgress]/[onCloneInProgressChange] are invoked at the exact
 * points the original inline implementation flipped `cloneProgress`/`cloneInProgress`, so the "Test
 * and save" step's progress UI is unaffected by this extraction.
 *
 * [onCloneAndAdd] stays an opaque platform-agnostic callback (git-sync-resilience Story 3.1.3):
 * Android's DI wiring (`App.kt`) supplies an implementation backed by `GitCloneWorkerLauncher` so
 * the clone survives backgrounding via `GitCloneWorker`'s `dataSync` foreground service; Desktop's
 * calls `GitRepository.clone()` directly, unchanged. This function's own body needs no platform
 * branching either way.
 */
internal suspend fun performCloneAndSave(
    form: GitSetupFormSnapshot,
    stores: GitSetupStores,
    onCloneAndAdd: suspend (
        url: String,
        localPath: String,
        auth: GitAuth,
        location: StorageLocation?,
        displayName: String?,
        description: String,
        onProgress: (CloneProgress) -> Unit,
        onStateChange: (GitTransportRetryState) -> Unit,
    ) -> Either<DomainError.GitError, String>,
    onCloneProgress: (CloneProgress) -> Unit,
    onCloneInProgressChange: (Boolean) -> Unit,
    onRetryStateChange: (GitTransportRetryState) -> Unit = {},
): CloneAndSaveOutcome {
    onCloneInProgressChange(true)
    val cloneAuth = buildCloneAuth(
        form.authType, form.httpsToken, form.sshKeyPath, form.sshPassphrase, form.graphId, stores.credentialStore,
    )
    // Task 4.1.4d: a manual cancel surfaces as a thrown CancellationException (not an
    // Either.Left) — see CloneAndSaveOutcome.Cancelled's kdoc. Catching it here, rather than
    // letting it propagate, is deliberate: it only means the *launcher's own* child job was
    // cancelled (AndroidGitCloneWorkerLauncher.cancel()/JvmGitCloneWorkerLauncher.cancel()), not
    // that this function's own ambient coroutine was — continuing to run afterward (to reset
    // cloneInProgress and report the outcome) is exactly the desired UX, not a violation of
    // structured concurrency.
    val cloneResult = try {
        onCloneAndAdd(
            form.cloneUrl,
            form.repoRoot,
            cloneAuth,
            form.cloneStorageLocation,
            form.graphName.ifBlank { repoNameFromUrl(form.cloneUrl) ?: "" }.takeIf { it.isNotBlank() },
            form.graphDescription,
            { progress -> onCloneProgress(progress) },
            { state -> onRetryStateChange(state) },
        )
    } catch (e: CancellationException) {
        onCloneInProgressChange(false)
        return CloneAndSaveOutcome.Cancelled
    }
    onCloneInProgressChange(false)

    if (cloneResult.isLeft()) {
        return CloneAndSaveOutcome.CloneFailed(cloneFailureCopy((cloneResult as Either.Left).value))
    }
    val newGraphId = (cloneResult as Either.Right).value

    // Task 2.1.2f: a clone that just ran defaults to shallow (Story 2.1.1) — stamp that checkpoint
    // now, since this is the first moment a GitConfig row exists for this graph at all.
    val saveResult = resolveAndSaveConfig(
        newGraphId, form, null, stores,
        cloneDepthState = CloneDepthState.Shallow(DEFAULT_CLONE_DEPTH),
    )
    return if (saveResult.isRight()) {
        gitSetupLogger.info("saveConfig succeeded (clone-and-add) graphId=$newGraphId")
        CloneAndSaveOutcome.Saved(newGraphId)
    } else {
        gitSetupLogger.error(
            "saveConfig failed (clone-and-add) graphId=$newGraphId error=${saveResult.leftOrNull()}"
        )
        CloneAndSaveOutcome.SaveFailed(newGraphId)
    }
}

/** Persists a [GitConfig] for the "use existing clone" / edit-an-existing-graph path. */
internal suspend fun performSaveExistingConfig(
    form: GitSetupFormSnapshot,
    existingConfig: GitConfig?,
    stores: GitSetupStores,
    gitSyncService: GitSyncService,
): SaveConfigOutcome {
    val result = resolveAndSaveConfig(form.graphId, form, existingConfig, stores)
    return if (result.isRight()) {
        gitSetupLogger.info("saveConfig succeeded graphId=${form.graphId}")
        // Trigger an immediate background fetch
        gitSyncService.fetchOnly(form.graphId)
        SaveConfigOutcome.Saved
    } else {
        gitSetupLogger.error("saveConfig failed graphId=${form.graphId} error=${result.leftOrNull()}")
        SaveConfigOutcome.Failed
    }
}

/**
 * Runs the OAuth device flow: requests code, polls for token, fetches username.
 * Designed to run in the composable's coroutine scope.
 */
internal suspend fun startOAuthFlow(
    deviceFlowClient: GitHubDeviceFlowClient?,
    graphId: String,
    credentialStore: CredentialStore,
    onDialogStateChange: (OAuthDialogState) -> Unit,
    onShowDialog: () -> Unit,
    onConnected: (username: String, token: String) -> Unit,
) {
    if (deviceFlowClient == null) {
        onDialogStateChange(OAuthDialogState.Error("GitHub OAuth is not available on this platform"))
        return
    }

    onShowDialog()
    onDialogStateChange(OAuthDialogState.Loading)

    val deviceCodeResult = deviceFlowClient.requestDeviceCode()
    if (deviceCodeResult.isLeft()) {
        val err = (deviceCodeResult as Either.Left).value
        onDialogStateChange(OAuthDialogState.Error(err.message))
        return
    }
    val response = (deviceCodeResult as Either.Right).value
    val expiresAt = Clock.System.now().toEpochMilliseconds() + response.expiresIn * 1000L
    onDialogStateChange(OAuthDialogState.ShowCode(response.userCode, response.verificationUri, expiresAt))

    val tokenResult = deviceFlowClient.pollForToken(
        deviceCode = response.deviceCode,
        expiresIn = response.expiresIn,
        initialInterval = response.interval,
        onStateChange = { _ ->
            onDialogStateChange(
                OAuthDialogState.Polling(
                    userCode = response.userCode,
                    verificationUri = response.verificationUri,
                    expiresAt = expiresAt,
                )
            )
        },
    )

    if (tokenResult.isLeft()) {
        val err = (tokenResult as Either.Left).value
        onDialogStateChange(OAuthDialogState.Error(err.message))
        return
    }

    val token = (tokenResult as Either.Right).value
    val key = "git_github_oauth_$graphId"
    credentialStore.store(key, token)

    val username = deviceFlowClient.fetchUsername(token) ?: "GitHub User"
    onConnected(username, token)
    onDialogStateChange(OAuthDialogState.Success(username))
}


/** Authored copy only (UX AC9): raw transport text such as "Software caused connection abort" never reaches the UI. */
internal fun cloneFailureCopy(error: DomainError): String =
    (error as? DomainError.GitError)?.toSyncErrorMessage() ?: "Clone failed — check your connection and try again"
