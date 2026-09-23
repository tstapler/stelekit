// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.GitCredentialConnectionStore
import dev.stapler.stelekit.git.GitHubDeviceFlowClient
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.platform.security.CredentialStore
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
}

/** Outcome of [performSaveExistingConfig], reported back to the composable for UI/state updates. */
internal sealed class SaveConfigOutcome {
    data object Saved : SaveConfigOutcome()
    data object Failed : SaveConfigOutcome()
}

/**
 * Resolves credential keys for [graphId] (reusing [existingConfig]'s keys as the fallback when a
 * field wasn't touched — null when there's no prior config, i.e. the clone-and-add path) and
 * persists a [GitConfig] built from [form]. Shared by [performCloneAndSave] (`existingConfig =
 * null`, `graphId` = the just-created graph) and [performSaveExistingConfig] (`graphId =
 * form.graphId`) — both paths resolve credential keys and call `saveConfig` identically; only what
 * happens after a successful save differs (a background fetch, vs. an `onCloneComplete` callback).
 */
private suspend fun resolveAndSaveConfig(
    graphId: String,
    form: GitSetupFormSnapshot,
    existingConfig: GitConfig?,
    credentialStore: CredentialStore,
    connectionStore: GitCredentialConnectionStore,
    gitConfigRepository: GitConfigRepository,
): Either<DomainError, Unit> {
    val httpsTokenKey = if (form.authType == GitAuthType.HTTPS_TOKEN) {
        resolveHttpsTokenKey(
            graphId = graphId,
            httpsToken = form.httpsToken,
            cloneUrl = form.cloneUrl,
            selectedConnectionId = form.selectedHttpsConnectionId,
            connectionStore = connectionStore,
            credentialStore = credentialStore,
            fallbackKey = existingConfig?.httpsTokenKey,
        )
    } else {
        existingConfig?.httpsTokenKey
    }
    val sshPassphraseKey = if (form.authType == GitAuthType.SSH_KEY && form.sshPassphrase.isNotBlank()) {
        val passphraseKey = "git_ssh_passphrase_$graphId"
        credentialStore.store(passphraseKey, form.sshPassphrase)
        passphraseKey
    } else {
        existingConfig?.sshKeyPassphraseKey
    }
    val oauthTokenKey = if (form.authType == GitAuthType.GITHUB_OAUTH) {
        resolveOauthTokenKey(
            graphId = graphId,
            selectedConnectionId = form.selectedOauthConnectionId,
            connectionStore = connectionStore,
            credentialStore = credentialStore,
            fallbackKey = existingConfig?.oauthTokenKey ?: "git_github_oauth_$graphId",
        )
    } else {
        null
    }
    // Web git write-back fix (PR #239 review): the wasmJs configResolver (browser/Main.kt) reads
    // credentials from PlatformSettings, not from CredentialStore (a no-op on web) — populate it
    // here so a real web user's saved PAT is actually reachable. See persistWebGitCredentials'
    // KDoc for the reload-to-take-effect caveat and the GITHUB_OAUTH gap. cloneUrl is blank on the
    // "use existing clone" / edit path, where this call no-ops.
    persistWebGitCredentials(form.cloneUrl, form.remoteBranch, form.authType, form.httpsToken)
    val config = buildConfig(
        graphId, form.repoRoot, form.wikiSubdir, form.authType,
        form.sshKeyPath, form.remoteBranch, form.pollIntervalMinutes,
        httpsTokenKey = httpsTokenKey,
        sshKeyPassphraseKey = sshPassphraseKey,
        oauthTokenKey = oauthTokenKey,
    )
    return gitConfigRepository.saveConfig(config)
}

/**
 * Clones [form.cloneUrl] to [form.repoRoot] via [onCloneAndAdd], then persists a [GitConfig] for
 * the newly created graph. [onCloneProgress]/[onCloneInProgressChange] are invoked at the exact
 * points the original inline implementation flipped `cloneProgress`/`cloneInProgress`, so the "Test
 * and save" step's progress UI is unaffected by this extraction.
 */
internal suspend fun performCloneAndSave(
    form: GitSetupFormSnapshot,
    credentialStore: CredentialStore,
    connectionStore: GitCredentialConnectionStore,
    gitConfigRepository: GitConfigRepository,
    onCloneAndAdd: suspend (
        url: String,
        localPath: String,
        auth: GitAuth,
        location: StorageLocation?,
        displayName: String?,
        description: String,
        onProgress: (String) -> Unit,
    ) -> Either<DomainError.GitError, String>,
    onCloneProgress: (String) -> Unit,
    onCloneInProgressChange: (Boolean) -> Unit,
): CloneAndSaveOutcome {
    onCloneInProgressChange(true)
    val cloneAuth = buildCloneAuth(
        form.authType, form.httpsToken, form.sshKeyPath, form.sshPassphrase, form.graphId, credentialStore,
    )
    val cloneResult = onCloneAndAdd(
        form.cloneUrl,
        form.repoRoot,
        cloneAuth,
        form.cloneStorageLocation,
        form.graphName.ifBlank { repoNameFromUrl(form.cloneUrl) ?: "" }.takeIf { it.isNotBlank() },
        form.graphDescription,
    ) { progress -> onCloneProgress(progress) }
    onCloneInProgressChange(false)

    if (cloneResult.isLeft()) {
        return CloneAndSaveOutcome.CloneFailed("Clone failed: ${(cloneResult as Either.Left).value.message}")
    }
    val newGraphId = (cloneResult as Either.Right).value

    val saveResult = resolveAndSaveConfig(newGraphId, form, null, credentialStore, connectionStore, gitConfigRepository)
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
    credentialStore: CredentialStore,
    connectionStore: GitCredentialConnectionStore,
    gitConfigRepository: GitConfigRepository,
    gitSyncService: GitSyncService,
): SaveConfigOutcome {
    val result = resolveAndSaveConfig(form.graphId, form, existingConfig, credentialStore, connectionStore, gitConfigRepository)
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
