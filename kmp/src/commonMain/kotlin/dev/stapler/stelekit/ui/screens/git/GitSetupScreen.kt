// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.GitHostAdapter
import dev.stapler.stelekit.git.GitHubDeviceFlowClient
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Multi-step wizard for configuring git sync on a graph.
 *
 * Step 1: Clone mode — use existing clone or clone new repo.
 * Step 2: Repo path and wiki subdirectory.
 * Step 3: Auth type (SSH key / HTTPS token / GitHub OAuth / None) and credentials.
 * Step 4: Branch name and poll interval.
 * Step 5: Test connection then save.
 *
 * @param graphId ID of the graph being configured.
 * @param existingConfig Pre-filled when editing an existing config.
 * @param gitRepository Platform-specific git implementation.
 * @param gitConfigRepository Persistence for [GitConfig].
 * @param gitSyncService Active service; used for immediate fetchOnly after save.
 * @param fileSystem Platform file system for directory/file pickers.
 * @param deviceFlowClient GitHub OAuth device flow client; null disables the OAuth option.
 * @param onDismiss Called when the user cancels the wizard.
 * @param onSaved Called after configuration is saved and initial fetch succeeds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitSetupScreen(
    graphId: String,
    gitRepository: GitRepository,
    gitConfigRepository: GitConfigRepository,
    gitSyncService: GitSyncService,
    fileSystem: FileSystem,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    existingConfig: GitConfig? = null,
    initialStep: Int = 1,
    initialUseExistingClone: Boolean = true,
    graphPath: String = "",
    onSave: () -> Unit = {},
    onCloneAndAdd: (suspend (url: String, localPath: String, auth: GitAuth, onProgress: (String) -> Unit) -> Either<DomainError.GitError, String>)? = null,
    onCloneComplete: ((String) -> Unit)? = null,
    deviceFlowClient: GitHubDeviceFlowClient? = null,
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var step by remember { mutableIntStateOf(initialStep) }

    // Form state
    var useExistingClone by remember { mutableStateOf(if (!initialUseExistingClone) false else true) }
    var cloneUrl by remember { mutableStateOf("") }
    var repoRoot by remember {
        mutableStateOf(
            existingConfig?.repoRoot ?: if (initialUseExistingClone) graphPath else ""
        )
    }
    var sshPassphrase by remember { mutableStateOf("") }
    var wikiSubdir by remember { mutableStateOf(existingConfig?.wikiSubdir ?: "") }
    var authType by remember { mutableStateOf(existingConfig?.authType ?: GitAuthType.NONE) }
    var sshKeyPath by remember { mutableStateOf(existingConfig?.sshKeyPath ?: "") }
    val credentialStore = remember { CredentialStore() }
    var httpsToken by remember {
        mutableStateOf(
            existingConfig?.httpsTokenKey?.let { key -> credentialStore.retrieve(key) } ?: ""
        )
    }
    var remoteBranch by remember { mutableStateOf(existingConfig?.remoteBranch ?: "main") }
    var pollIntervalMinutes by remember { mutableStateOf(existingConfig?.pollIntervalMinutes ?: 5) }

    // OAuth flow state
    var showOAuthDialog by remember { mutableStateOf(false) }
    var oauthDialogState by remember { mutableStateOf<OAuthDialogState?>(null) }
    var oauthConnectedAs by remember { mutableStateOf<String?>(null) }
    var oauthJob by remember { mutableStateOf<Job?>(null) }

    // Step 5: connection test state
    var testInProgress by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }

    // Save state
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Clone state
    var cloneInProgress by remember { mutableStateOf(false) }
    var cloneProgress by remember { mutableStateOf("") }
    var cloneError by remember { mutableStateOf<String?>(null) }

    val stepLabel = when (step) {
        1 -> "Repository mode"
        2 -> "Repository path"
        3 -> "Authentication"
        4 -> "Sync settings"
        5 -> "Test & save"
        else -> "Git Sync Setup"
    }

    DisposableEffect(deviceFlowClient) {
        onDispose {
            deviceFlowClient?.close()
        }
    }

    if (showOAuthDialog && oauthDialogState != null) {
        GitHubOAuthDialog(
            state = oauthDialogState!!,
            onCopyCode = { code ->
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(code))
            },
            onOpenBrowser = { url ->
                dev.stapler.stelekit.platform.openInBrowser(url)
            },
            onCancel = {
                oauthJob?.cancel()
                showOAuthDialog = false
                oauthDialogState = null
            },
            onRetry = {
                showOAuthDialog = false
                oauthDialogState = null
                // Restart OAuth flow
                oauthJob?.cancel()
                oauthJob = scope.launch {
                    startOAuthFlow(
                        deviceFlowClient = deviceFlowClient,
                        graphId = graphId,
                        credentialStore = credentialStore,
                        onDialogStateChange = { oauthDialogState = it },
                        onShowDialog = { showOAuthDialog = true },
                        onConnected = { username -> oauthConnectedAs = username },
                    )
                }
            },
            onDone = {
                showOAuthDialog = false
                oauthDialogState = null
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Git Sync — $stepLabel") },
                actions = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(
                progress = { step / 5f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))

            when (step) {
                1 -> Step1CloneMode(
                    useExistingClone = useExistingClone,
                    onUseExistingClone = { useExistingClone = it },
                    onNext = { step = 2 },
                )

                2 -> Step2RepoPath(
                    useExistingClone = useExistingClone,
                    repoRoot = repoRoot,
                    onRepoRootChange = { repoRoot = it },
                    cloneUrl = cloneUrl,
                    onCloneUrlChange = { cloneUrl = it },
                    wikiSubdir = wikiSubdir,
                    onWikiSubdirChange = { wikiSubdir = it },
                    onBack = { step = 1 },
                    onNext = { step = 3 },
                    nextEnabled = repoRoot.isNotBlank() && (useExistingClone || cloneUrl.isNotBlank()),
                    onBrowseRepoRoot = {
                        scope.launch {
                            val path = fileSystem.pickDirectoryAsync()
                            if (path != null) repoRoot = path
                        }
                    },
                )

                3 -> {
                    var tokenVisible by remember { mutableStateOf(false) }
                    Step3Auth(
                        authType = authType,
                        onAuthTypeChange = { newType ->
                            if (authType == GitAuthType.GITHUB_OAUTH && newType != GitAuthType.GITHUB_OAUTH) {
                                // Delete stored OAuth token when switching away
                                credentialStore.delete("git_github_oauth_$graphId")
                                oauthConnectedAs = null
                            }
                            authType = newType
                        },
                        sshKeyPath = sshKeyPath,
                        onSshKeyPathChange = { sshKeyPath = it },
                        httpsToken = httpsToken,
                        onHttpsTokenChange = { httpsToken = it },
                        tokenVisible = tokenVisible,
                        onToggleTokenVisible = { tokenVisible = !tokenVisible },
                        sshPassphrase = sshPassphrase,
                        onSshPassphraseChange = { sshPassphrase = it },
                        onBack = { step = 2 },
                        onNext = { step = 4 },
                        oauthConnectedAs = oauthConnectedAs,
                        onStartOAuthFlow = {
                            showOAuthDialog = true
                            oauthDialogState = OAuthDialogState.Loading
                            oauthJob?.cancel()
                            oauthJob = scope.launch {
                                startOAuthFlow(
                                    deviceFlowClient = deviceFlowClient,
                                    graphId = graphId,
                                    credentialStore = credentialStore,
                                    onDialogStateChange = { oauthDialogState = it },
                                    onShowDialog = { showOAuthDialog = true },
                                    onConnected = { username -> oauthConnectedAs = username },
                                )
                            }
                        },
                        showOAuthDialog = showOAuthDialog,
                        deviceFlowEnabled = deviceFlowClient != null,
                        onBrowseSshKey = {
                            scope.launch {
                                val path = fileSystem.pickFileAsync()
                                if (path != null) sshKeyPath = path
                            }
                        },
                    )
                }

                4 -> Step4Branch(
                    remoteBranch = remoteBranch,
                    onRemoteBranchChange = { remoteBranch = it },
                    pollIntervalMinutes = pollIntervalMinutes,
                    onPollIntervalChange = { pollIntervalMinutes = it },
                    onBack = { step = 3 },
                    onNext = { step = 5 },
                )

                5 -> Step5TestAndSave(
                    testInProgress = testInProgress,
                    testResult = testResult,
                    testSuccess = testSuccess,
                    saving = saving,
                    saveError = saveError,
                    onBack = { step = 4 },
                    onTestConnection = {
                        scope.launch {
                            testInProgress = true
                            testResult = null
                            val testHttpsTokenKey = if (authType == GitAuthType.HTTPS_TOKEN && httpsToken.isNotBlank()) {
                                val key = "git_https_token_$graphId"
                                credentialStore.store(key, httpsToken)
                                key
                            } else null
                            val testSshPassphraseKey = if (authType == GitAuthType.SSH_KEY && sshPassphrase.isNotBlank()) {
                                val key = "git_ssh_passphrase_$graphId"
                                credentialStore.store(key, sshPassphrase)
                                key
                            } else null
                            val testOauthTokenKey = if (authType == GitAuthType.GITHUB_OAUTH && oauthConnectedAs != null) {
                                "git_github_oauth_$graphId"
                            } else null
                            val config = buildConfig(
                                graphId, repoRoot, wikiSubdir, authType,
                                sshKeyPath, remoteBranch, pollIntervalMinutes,
                                httpsTokenKey = testHttpsTokenKey,
                                sshKeyPassphraseKey = testSshPassphraseKey,
                                oauthTokenKey = testOauthTokenKey,
                            )
                            val result = gitRepository.fetch(config)
                            testInProgress = false
                            if (result.isRight()) {
                                testSuccess = true
                                testResult = "Connection successful."
                            } else {
                                testSuccess = false
                                val errMsg = (result as? Either.Left)?.value?.message ?: "Unknown error"
                                testResult = "Connection failed: $errMsg"
                            }
                        }
                    },
                    cloneInProgress = cloneInProgress,
                    cloneProgress = cloneProgress,
                    cloneError = cloneError,
                    onSave = {
                        scope.launch {
                            saving = true
                            saveError = null
                            cloneError = null

                            // If cloning a new repo, clone first
                            if (!useExistingClone && onCloneAndAdd != null) {
                                cloneInProgress = true
                                cloneProgress = ""
                                val cloneAuth = when (authType) {
                                    GitAuthType.HTTPS_TOKEN -> GitAuth.HttpsToken(
                                        username = "",
                                        tokenProvider = { httpsToken.takeIf { it.isNotBlank() } }
                                    )
                                    GitAuthType.SSH_KEY -> GitAuth.SshKey(
                                        keyPath = sshKeyPath,
                                        passphraseProvider = { sshPassphrase.takeIf { it.isNotBlank() } },
                                    )
                                    GitAuthType.GITHUB_OAUTH -> GitAuth.HttpsToken(
                                        username = "x-oauth-basic",
                                        tokenProvider = {
                                            credentialStore.retrieve("git_github_oauth_$graphId")
                                        }
                                    )
                                    GitAuthType.NONE -> GitAuth.None
                                }
                                val cloneResult = onCloneAndAdd(cloneUrl, repoRoot, cloneAuth) { progress ->
                                    cloneProgress = progress
                                }
                                cloneInProgress = false
                                if (cloneResult.isLeft()) {
                                    cloneError = "Clone failed: ${(cloneResult as Either.Left).value.message}"
                                    saving = false
                                    return@launch
                                }
                                val newGraphId = (cloneResult as Either.Right).value
                                val httpsTokenKey = if (authType == GitAuthType.HTTPS_TOKEN && httpsToken.isNotBlank()) {
                                    val tokenKey = "git_https_token_$newGraphId"
                                    credentialStore.store(tokenKey, httpsToken)
                                    tokenKey
                                } else {
                                    null
                                }
                                val sshPassphraseKey = if (authType == GitAuthType.SSH_KEY && sshPassphrase.isNotBlank()) {
                                    val passphraseKey = "git_ssh_passphrase_$newGraphId"
                                    credentialStore.store(passphraseKey, sshPassphrase)
                                    passphraseKey
                                } else {
                                    null
                                }
                                val oauthTokenKey = if (authType == GitAuthType.GITHUB_OAUTH) {
                                    "git_github_oauth_$newGraphId"
                                } else null
                                // Web git write-back fix (PR #239 review): the wasmJs configResolver
                                // (browser/Main.kt) reads credentials from PlatformSettings, not from
                                // CredentialStore (a no-op on web) — populate it here so a real web
                                // user's saved PAT is actually reachable. See persistWebGitCredentials
                                // KDoc for the reload-to-take-effect caveat and the GITHUB_OAUTH gap.
                                persistWebGitCredentials(cloneUrl, remoteBranch, authType, httpsToken)
                                val config = buildConfig(
                                    newGraphId,
                                    repoRoot, wikiSubdir, authType, sshKeyPath, remoteBranch, pollIntervalMinutes,
                                    httpsTokenKey = httpsTokenKey,
                                    sshKeyPassphraseKey = sshPassphraseKey,
                                    oauthTokenKey = oauthTokenKey,
                                )
                                val saveResult = gitConfigRepository.saveConfig(config)
                                saving = false
                                if (saveResult.isRight()) {
                                    onCloneComplete?.invoke(newGraphId)
                                    onSave()
                                } else {
                                    saveError = "Failed to save configuration."
                                }
                                return@launch
                            }

                            val httpsTokenKey = if (authType == GitAuthType.HTTPS_TOKEN && httpsToken.isNotBlank()) {
                                val tokenKey = "git_https_token_$graphId"
                                credentialStore.store(tokenKey, httpsToken)
                                tokenKey
                            } else {
                                existingConfig?.httpsTokenKey
                            }
                            val sshPassphraseKey = if (authType == GitAuthType.SSH_KEY && sshPassphrase.isNotBlank()) {
                                val passphraseKey = "git_ssh_passphrase_$graphId"
                                credentialStore.store(passphraseKey, sshPassphrase)
                                passphraseKey
                            } else {
                                existingConfig?.sshKeyPassphraseKey
                            }
                            val oauthTokenKey = if (authType == GitAuthType.GITHUB_OAUTH) {
                                existingConfig?.oauthTokenKey ?: "git_github_oauth_$graphId"
                            } else null
                            // Web git write-back fix (PR #239 review): same PlatformSettings wiring as
                            // the clone-and-add path above. cloneUrl is typically blank here (this is
                            // the "use existing clone" / edit path, where Step2RepoPath never shows the
                            // remote-URL field) — persistWebGitCredentials no-ops on a blank cloneUrl,
                            // so the user's initial-setup PlatformSettings values are left untouched.
                            persistWebGitCredentials(cloneUrl, remoteBranch, authType, httpsToken)
                            val config = buildConfig(
                                graphId, repoRoot, wikiSubdir, authType,
                                sshKeyPath, remoteBranch, pollIntervalMinutes,
                                httpsTokenKey = httpsTokenKey,
                                sshKeyPassphraseKey = sshPassphraseKey,
                                oauthTokenKey = oauthTokenKey,
                            )
                            val result = gitConfigRepository.saveConfig(config)
                            saving = false
                            if (result.isRight()) {
                                // Trigger an immediate background fetch
                                gitSyncService.fetchOnly(graphId)
                                onSave()
                            } else {
                                saveError = "Failed to save configuration."
                            }
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Runs the OAuth device flow: requests code, polls for token, fetches username.
 * Designed to run in the composable's coroutine scope.
 */
private suspend fun startOAuthFlow(
    deviceFlowClient: GitHubDeviceFlowClient?,
    graphId: String,
    credentialStore: CredentialStore,
    onDialogStateChange: (OAuthDialogState) -> Unit,
    onShowDialog: () -> Unit,
    onConnected: (String) -> Unit,
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
    onConnected(username)
    onDialogStateChange(OAuthDialogState.Success(username))
}

@Composable
private fun Step1CloneMode(
    useExistingClone: Boolean,
    onUseExistingClone: (Boolean) -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Repository mode", style = MaterialTheme.typography.titleMedium)
        Text("Do you already have a local git clone of your notes repository?", style = MaterialTheme.typography.bodyMedium)

        Row(
            modifier = Modifier.fillMaxWidth().selectable(selected = useExistingClone, role = Role.RadioButton, onClick = { onUseExistingClone(true) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = useExistingClone, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Use existing clone")
        }
        Row(
            modifier = Modifier.fillMaxWidth().selectable(selected = !useExistingClone, role = Role.RadioButton, onClick = { onUseExistingClone(false) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = !useExistingClone, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clone a remote repository")
        }

        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text("Next")
        }
    }
}

@Composable
private fun Step2RepoPath(
    useExistingClone: Boolean,
    repoRoot: String,
    onRepoRootChange: (String) -> Unit,
    cloneUrl: String,
    onCloneUrlChange: (String) -> Unit,
    wikiSubdir: String,
    onWikiSubdirChange: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean = repoRoot.isNotBlank(),
    onBrowseRepoRoot: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Repository path", style = MaterialTheme.typography.titleMedium)

        if (!useExistingClone) {
            OutlinedTextField(
                value = cloneUrl,
                onValueChange = onCloneUrlChange,
                label = { Text("Remote URL (HTTPS or SSH)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        OutlinedTextField(
            value = repoRoot,
            onValueChange = onRepoRootChange,
            label = { Text("Local repository root path") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = if (onBrowseRepoRoot != null) {
                {
                    IconButton(onClick = onBrowseRepoRoot) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Browse for directory",
                        )
                    }
                }
            } else null,
        )

        OutlinedTextField(
            value = wikiSubdir,
            onValueChange = onWikiSubdirChange,
            label = { Text("Wiki subdirectory (leave empty if notes are at repo root)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Button(
                onClick = onNext,
                enabled = nextEnabled,
            ) { Text("Next") }
        }
    }
}

@Composable
private fun Step3Auth(
    authType: GitAuthType,
    onAuthTypeChange: (GitAuthType) -> Unit,
    sshKeyPath: String,
    onSshKeyPathChange: (String) -> Unit,
    httpsToken: String,
    onHttpsTokenChange: (String) -> Unit,
    tokenVisible: Boolean,
    onToggleTokenVisible: () -> Unit,
    sshPassphrase: String,
    onSshPassphraseChange: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    oauthConnectedAs: String? = null,
    onStartOAuthFlow: () -> Unit = {},
    showOAuthDialog: Boolean = false,
    deviceFlowEnabled: Boolean = false,
    onBrowseSshKey: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Authentication", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth().selectable(selected = authType == GitAuthType.NONE, role = Role.RadioButton, onClick = { onAuthTypeChange(GitAuthType.NONE) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = authType == GitAuthType.NONE, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("No authentication (public repo)")
        }
        Row(
            modifier = Modifier.fillMaxWidth().selectable(selected = authType == GitAuthType.SSH_KEY, role = Role.RadioButton, onClick = { onAuthTypeChange(GitAuthType.SSH_KEY) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = authType == GitAuthType.SSH_KEY, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SSH key")
        }
        Row(
            modifier = Modifier.fillMaxWidth().selectable(selected = authType == GitAuthType.HTTPS_TOKEN, role = Role.RadioButton, onClick = { onAuthTypeChange(GitAuthType.HTTPS_TOKEN) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = authType == GitAuthType.HTTPS_TOKEN, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("HTTPS token (GitHub PAT, etc.)")
        }
        Row(
            modifier = Modifier.fillMaxWidth().selectable(selected = authType == GitAuthType.GITHUB_OAUTH, role = Role.RadioButton, onClick = { onAuthTypeChange(GitAuthType.GITHUB_OAUTH) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = authType == GitAuthType.GITHUB_OAUTH, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("GitHub (OAuth)")
        }

        if (authType == GitAuthType.SSH_KEY) {
            OutlinedTextField(
                value = sshKeyPath,
                onValueChange = onSshKeyPathChange,
                label = { Text("SSH private key path (e.g. ~/.ssh/id_ed25519)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = if (onBrowseSshKey != null) {
                    {
                        IconButton(onClick = onBrowseSshKey) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = "Browse for SSH key file",
                            )
                        }
                    }
                } else null,
            )
            Text(
                "On Android, the selected file is copied to secure app storage. The path shown is the app-private copy.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            var passphraseVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = sshPassphrase,
                onValueChange = onSshPassphraseChange,
                label = { Text("SSH key passphrase (leave empty if none)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                        Icon(
                            imageVector = if (passphraseVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passphraseVisible) "Hide passphrase" else "Show passphrase",
                        )
                    }
                },
            )
        }

        if (authType == GitAuthType.HTTPS_TOKEN) {
            OutlinedTextField(
                value = httpsToken,
                onValueChange = onHttpsTokenChange,
                label = { Text("Personal access token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onToggleTokenVisible) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (tokenVisible) "Hide token" else "Show token",
                        )
                    }
                },
            )
            Text(
                "Token is encrypted on disk using device-specific keys. For stronger protection, use SSH key auth.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (authType == GitAuthType.GITHUB_OAUTH) {
            if (oauthConnectedAs != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Connected",
                        tint = Color(0xFF047857),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "Connected as @$oauthConnectedAs",
                        color = Color(0xFF047857),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(
                    onClick = onStartOAuthFlow,
                    enabled = deviceFlowEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Re-connect") }
            } else {
                Button(
                    onClick = onStartOAuthFlow,
                    enabled = deviceFlowEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect GitHub Account") }
                if (!deviceFlowEnabled) {
                    Text(
                        "GitHub OAuth is not available on this platform.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Button(onClick = onNext) { Text("Next") }
        }
    }
}

@Composable
private fun Step4Branch(
    remoteBranch: String,
    onRemoteBranchChange: (String) -> Unit,
    pollIntervalMinutes: Int,
    onPollIntervalChange: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sync settings", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = remoteBranch,
            onValueChange = onRemoteBranchChange,
            label = { Text("Remote branch") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text("Background poll interval", style = MaterialTheme.typography.labelMedium)

        val intervals = listOf(0 to "Off", 5 to "5 minutes", 15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour")
        intervals.forEach { (minutes, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().selectable(selected = pollIntervalMinutes == minutes, role = Role.RadioButton, onClick = { onPollIntervalChange(minutes) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = pollIntervalMinutes == minutes,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(label)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Button(onClick = onNext) { Text("Next") }
        }
    }
}

@Composable
private fun Step5TestAndSave(
    testInProgress: Boolean,
    testResult: String?,
    testSuccess: Boolean,
    saving: Boolean,
    saveError: String?,
    onBack: () -> Unit,
    onTestConnection: () -> Unit,
    cloneInProgress: Boolean = false,
    cloneProgress: String = "",
    cloneError: String? = null,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Test and save", style = MaterialTheme.typography.titleMedium)
        Text("Optionally test your connection before saving.", style = MaterialTheme.typography.bodyMedium)

        OutlinedButton(
            onClick = onTestConnection,
            enabled = !testInProgress,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testInProgress) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Test connection")
        }

        if (testResult != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (testSuccess) Icons.Default.Check else Icons.Default.Error,
                    contentDescription = if (testSuccess) "Success" else "Error",
                    tint = if (testSuccess) Color(0xFF047857) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = testResult,
                    color = if (testSuccess) Color(0xFF047857) else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (cloneInProgress) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = cloneProgress.ifBlank { "Cloning repository…" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        cloneError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        saveError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onBack, enabled = !saving && !cloneInProgress) { Text("Back") }
            Button(
                onClick = onSave,
                enabled = !saving && !cloneInProgress,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Save configuration")
            }
        }
    }
}

/**
 * Populates the `PlatformSettings` keys ("githubOwner"/"githubRepo"/"githubBranch"/"githubToken")
 * that `browser/Main.kt`'s `configResolver` reads once at wasmJs startup into
 * `PlatformFileSystem`/`WasmSectionSyncService`'s companion fields — the credential source the
 * write engine (`WasmGitRepository`) actually trusts. Without this, a web user's saved
 * `HTTPS_TOKEN` PAT (persisted via [CredentialStore], a no-op on wasmJs for a different, accepted
 * reason) is never visible to `configResolver`, and every `commit`/`fetch`/`push`/`merge` fails
 * with `AuthFailed("Unable to resolve git host configuration for this graph")`.
 *
 * [cloneUrl] is parsed via [GitHostAdapter.extractOwnerRepo] to derive `owner`/`repo`; no-ops
 * (does not overwrite any existing settings with blanks) when [authType] is not
 * [GitAuthType.HTTPS_TOKEN], [cloneUrl] is blank or unparseable, or [token] is blank.
 *
 * IMPORTANT — documented UX rough edge, not a correctness bug: this only takes effect for the
 * CURRENT session after a page reload on web, because `Main.kt` reads `PlatformSettings` exactly
 * once at startup. This function intentionally does not attempt to also update
 * `PlatformFileSystem`'s companion fields live — that would require a new expect/actual hook this
 * fix doesn't need to build.
 *
 * KNOWN FOLLOW-UP (explicitly out of scope for this fix): [GitAuthType.GITHUB_OAUTH]'s equivalent
 * gap — the OAuth device-flow token is also never written to `PlatformSettings` — is not handled
 * here and needs its own follow-up.
 *
 * Safe no-op-equivalent on JVM/Android: those platforms never read these specific
 * `PlatformSettings` keys back, so writing them there is harmless, just slightly redundant.
 */
internal fun persistWebGitCredentials(
    cloneUrl: String,
    branch: String,
    authType: GitAuthType,
    token: String,
) {
    if (authType != GitAuthType.HTTPS_TOKEN) return
    if (cloneUrl.isBlank() || token.isBlank()) return
    val (owner, repo) = GitHostAdapter.extractOwnerRepo(cloneUrl) ?: return
    val settings = PlatformSettings()
    settings.putString("githubOwner", owner)
    settings.putString("githubRepo", repo)
    settings.putString("githubBranch", branch)
    settings.putString("githubToken", token)
}

private fun buildConfig(
    graphId: String,
    repoRoot: String,
    wikiSubdir: String,
    authType: GitAuthType,
    sshKeyPath: String,
    remoteBranch: String,
    pollIntervalMinutes: Int,
    httpsTokenKey: String? = null,
    sshKeyPassphraseKey: String? = null,
    oauthTokenKey: String? = null,
): GitConfig = GitConfig(
    graphId = graphId,
    repoRoot = repoRoot,
    wikiSubdir = wikiSubdir,
    authType = authType,
    sshKeyPath = sshKeyPath.takeIf { it.isNotBlank() },
    remoteBranch = remoteBranch,
    pollIntervalMinutes = pollIntervalMinutes,
    httpsTokenKey = httpsTokenKey,
    sshKeyPassphraseKey = sshKeyPassphraseKey,
    oauthTokenKey = oauthTokenKey,
)
