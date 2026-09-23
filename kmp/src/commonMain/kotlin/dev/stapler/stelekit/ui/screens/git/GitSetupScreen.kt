// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import arrow.core.Either
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.GitCredentialConnectionStore
import dev.stapler.stelekit.git.GitHubDeviceFlowClient
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    initialCloneMode: CloneMode = CloneMode.UseExistingClone,
    graphPath: String = "",
    // Auto-detected by GraphManager.detectGitRoot() and surfaced via GitDetectionBanner — prefills
    // Step2RepoPath so opening this wizard from that banner doesn't discard what the app already
    // figured out (previously always defaulted repoRoot to graphPath / wikiSubdir to blank, even
    // when detection had already found the real repo root above a nested wiki folder).
    detectedRepoRoot: String? = null,
    detectedWikiSubdir: String? = null,
    onSave: () -> Unit = {},
    onCloneAndAdd: (suspend (url: String, localPath: String, auth: GitAuth, location: StorageLocation?, displayName: String?, description: String, onProgress: (String) -> Unit) -> Either<DomainError.GitError, String>)? = null,
    onCloneComplete: ((String) -> Unit)? = null,
    deviceFlowClient: GitHubDeviceFlowClient? = null,
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var step by remember { mutableIntStateOf(initialStep) }

    // Form state
    var cloneMode by remember { mutableStateOf(initialCloneMode) }
    var cloneUrl by remember { mutableStateOf("") }
    var repoRoot by remember {
        mutableStateOf(
            existingConfig?.repoRoot
                ?: detectedRepoRoot
                ?: if (initialCloneMode == CloneMode.UseExistingClone) graphPath else ""
        )
    }
    var sshPassphrase by remember { mutableStateOf("") }
    var wikiSubdir by remember {
        mutableStateOf(existingConfig?.wikiSubdir ?: detectedWikiSubdir ?: "")
    }
    var wikiSubdirBrowserOpen by remember { mutableStateOf(false) }
    var graphName by remember { mutableStateOf("") }
    var graphDescription by remember { mutableStateOf("") }

    // Live .git check at repoRoot, replacing the old saf://-string-prefix heuristic (which never
    // covered wasm's OPFS-mirrored picker paths, only Android's). Null while unchecked/blank —
    // callers below only branch on it once it's a real true/false.
    var hasGitAtRepoRoot by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(repoRoot) {
        if (repoRoot.isBlank()) {
            hasGitAtRepoRoot = null
            return@LaunchedEffect
        }
        hasGitAtRepoRoot = withContext(PlatformDispatcher.IO) {
            val gitPath = "$repoRoot/.git"
            fileSystem.fileExists(gitPath) || fileSystem.directoryExists(gitPath)
        }
    }
    // "Use existing clone" against a SAF-only folder (no MANAGE_EXTERNAL_STORAGE) is structurally
    // unsupported — see AndroidGitRepository.resolveForJGit's doc comment: JGit only understands
    // java.io.File, and the shadow-worktree mirror deliberately never copies .git itself
    // (FileSystem.listFilesRecursiveWithModTimes skips it), so there's no path to a working
    // git repo either way. Surfaced explicitly rather than left to fail at "Test connection" with
    // a cryptic "repository not found: /data/data/.../gitshadow" error.
    val existingRepoNeedsAllFilesAccess = cloneMode == CloneMode.UseExistingClone &&
        repoRoot.startsWith("saf://") &&
        !fileSystem.hasAllFilesAccess()

    var authType by remember { mutableStateOf(existingConfig?.authType ?: GitAuthType.NONE) }
    var sshKeyPath by remember { mutableStateOf(existingConfig?.sshKeyPath ?: "") }
    val credentialStore = remember { CredentialStore() }
    // App-wide (not per-graph) — lets HTTPS_TOKEN/GITHUB_OAUTH credentials be reused across graphs
    // instead of re-pasting a PAT or redoing the OAuth device flow every time. PlatformSettings()
    // is instantiated ad hoc here rather than threaded through as a parameter, matching the
    // existing precedent in persistWebGitCredentials below (each instance shares the same
    // underlying platform store — SharedPreferences/NSUserDefaults/localStorage — so this is safe).
    val connectionStore = remember(credentialStore) { GitCredentialConnectionStore(PlatformSettings(), credentialStore) }
    var httpsConnections by remember {
        mutableStateOf(connectionStore.listConnections(GitAuthType.HTTPS_TOKEN))
    }
    var oauthConnections by remember {
        mutableStateOf(connectionStore.listConnections(GitAuthType.GITHUB_OAUTH))
    }
    // Null selection means "enter/connect a new credential" — the manual entry UI stays visible.
    // Pre-selects the saved connection an existing config's token happens to match, so re-opening
    // the wizard on an already-configured graph doesn't show "enter a new token" over a value that
    // actually came from a saved connection.
    var selectedHttpsConnectionId by remember {
        mutableStateOf(
            existingConfig?.httpsTokenKey?.let { key -> credentialStore.retrieve(key) }?.let { token ->
                httpsConnections.firstOrNull { connectionStore.getSecret(it) == token }?.id
            }
        )
    }
    var selectedOauthConnectionId by remember { mutableStateOf<String?>(null) }
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

    // Shared by both startOAuthFlow call sites (initial connect + dialog retry): a freshly
    // completed device flow is saved as a new reusable connection (GitHub OAuth is the only auth
    // type this device flow ever produces, so host is always "github.com"), so the next graph
    // configured against the same account can pick it from the list instead of redoing the flow.
    val onOAuthConnected: (username: String, token: String) -> Unit = { username, token ->
        oauthConnectedAs = username
        val connection = connectionStore.saveConnection(
            host = "github.com",
            accountLabel = username,
            authType = GitAuthType.GITHUB_OAUTH,
            secret = token,
            createdAt = Clock.System.now().toEpochMilliseconds(),
        )
        selectedOauthConnectionId = connection.id
        oauthConnections = connectionStore.listConnections(GitAuthType.GITHUB_OAUTH)
    }

    // Step 5: connection test state
    var testState by remember { mutableStateOf<GitConnectionTestState>(GitConnectionTestState.Idle) }

    // Save state
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Clone state
    var cloneInProgress by remember { mutableStateOf(false) }
    var cloneProgress by remember { mutableStateOf("") }
    var cloneError by remember { mutableStateOf<String?>(null) }

    // Story 2.2.2: destination StorageLocation for a "clone a remote repository" flow, resolved by
    // UnifiedLocationPicker. Null for "use existing clone" (no picker shown there — see
    // Step2RepoPath's onShowLocationPicker gate) and for the pre-feature "Browse…"-only path this
    // repo replaces. showCloneLocationPicker/pendingAppOwnedGraphPath are the picker dialog's own
    // open-state and its pre-generated "App storage" candidate path (needed up front because
    // UnifiedLocationPicker takes graphId as a constructor param, before the user has chosen).
    var cloneStorageLocation by remember { mutableStateOf<StorageLocation?>(null) }
    var showCloneLocationPicker by remember { mutableStateOf(false) }
    var pendingAppOwnedGraphPath by remember { mutableStateOf("") }
    // Set alongside the StorageLocation.SafFolder returned from the picker's onBrowseRequest —
    // SafFolder.treeUri only carries the tree-root segment (see the comment at its construction
    // below), so the full saf://<tree>/<subpath> repoRoot this screen/JGit needs is stashed here
    // rather than reconstructed from that shorter field.
    var pendingSafRepoRoot by remember { mutableStateOf("") }

    // Clone mode defaults to app-owned storage (no folder grant needed) where the platform has it;
    // the user only sees a folder picker if they explicitly choose "Change".
    fun selectAppStorage() {
        val path = fileSystem.newAppOwnedGraphPath()
        pendingAppOwnedGraphPath = path
        repoRoot = path
        cloneStorageLocation = StorageLocation.AppOwned(graphIdFromPath(fileSystem.expandTilde(path)))
        wikiSubdir = ""
    }
    LaunchedEffect(Unit) {
        if (cloneMode == CloneMode.CloneNewRepository && fileSystem.supportsAppOwnedStorage && repoRoot.isBlank()) selectAppStorage()
    }

    // Snapshot of Step 2–4's fields read at the moment "Test connection"/"Save" is clicked — see
    // GitSetupFormSnapshot's KDoc for why a snapshot is equivalent to re-reading each field live.
    fun currentFormSnapshot() = GitSetupFormSnapshot(
        graphId = graphId,
        cloneMode = cloneMode,
        cloneUrl = cloneUrl,
        repoRoot = repoRoot,
        wikiSubdir = wikiSubdir,
        graphName = graphName,
        graphDescription = graphDescription,
        cloneStorageLocation = cloneStorageLocation,
        authType = authType,
        sshKeyPath = sshKeyPath,
        sshPassphrase = sshPassphrase,
        httpsToken = httpsToken,
        oauthConnectedAs = oauthConnectedAs,
        selectedHttpsConnectionId = selectedHttpsConnectionId,
        selectedOauthConnectionId = selectedOauthConnectionId,
        remoteBranch = remoteBranch,
        pollIntervalMinutes = pollIntervalMinutes,
    )

    fun selectCloneMode(newMode: CloneMode) {
        cloneMode = newMode
        if (newMode == CloneMode.CloneNewRepository && fileSystem.supportsAppOwnedStorage && repoRoot.isBlank()) {
            selectAppStorage()
        } else if (newMode == CloneMode.UseExistingClone && cloneStorageLocation is StorageLocation.AppOwned) {
            // app-storage path is meaningless for "use existing clone"
            cloneStorageLocation = null
            repoRoot = detectedRepoRoot ?: graphPath
            wikiSubdir = detectedWikiSubdir ?: ""
        }
    }

    fun browseRepoRootViaSystemPicker() {
        scope.launch {
            val path = fileSystem.pickDirectoryAsync()
            if (path != null) {
                repoRoot = path
                wikiSubdir = "" // stale relative to the old root — start over, not silently wrong
                cloneStorageLocation = null
            }
        }
    }

    fun browseSshKey() {
        scope.launch {
            val path = fileSystem.pickFileAsync()
            if (path != null) sshKeyPath = path
        }
    }

    fun browseRepoRoot() {
        if (cloneMode == CloneMode.CloneNewRepository && fileSystem.supportsAppOwnedStorage) {
            // Story 2.2.2: "clone a remote repository" destination — show UnifiedLocationPicker
            // instead of jumping straight to the SAF folder picker, so "App storage" is choosable
            // with zero SAF grant. "Use existing clone" (the other branch) is untouched: browsing
            // there always means "find my existing local repo," where AppOwned has no meaning.
            pendingAppOwnedGraphPath = fileSystem.newAppOwnedGraphPath()
            showCloneLocationPicker = true
        } else {
            browseRepoRootViaSystemPicker()
        }
    }

    // The initial-connect entry point (Step3Auth's "Connect GitHub Account" button) shows loading
    // feedback immediately; the dialog's own "Try Again" resets to a clean slate first — see each
    // function's own comment. Both then launch the same startOAuthFlow.
    fun startGithubOAuthFlowFresh() {
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
                onConnected = onOAuthConnected,
            )
        }
    }

    fun retryGithubOAuthFlow() {
        showOAuthDialog = false
        oauthDialogState = null
        oauthJob?.cancel()
        oauthJob = scope.launch {
            startOAuthFlow(
                deviceFlowClient = deviceFlowClient,
                graphId = graphId,
                credentialStore = credentialStore,
                onDialogStateChange = { oauthDialogState = it },
                onShowDialog = { showOAuthDialog = true },
                onConnected = onOAuthConnected,
            )
        }
    }

    fun selectAuthType(newType: GitAuthType) {
        if (authType == GitAuthType.GITHUB_OAUTH && newType != GitAuthType.GITHUB_OAUTH) {
            // Delete stored OAuth token when switching away
            credentialStore.delete("git_github_oauth_$graphId")
            oauthConnectedAs = null
            selectedOauthConnectionId = null
        }
        authType = newType
    }

    fun performTestConnection() {
        scope.launch {
            testState = GitConnectionTestState.InProgress
            val result = testGitConnection(currentFormSnapshot(), gitRepository, credentialStore)
            testState = if (result.isRight()) {
                GitConnectionTestState.Success("Connection successful.")
            } else {
                val errMsg = (result as? Either.Left)?.value?.message ?: "Unknown error"
                GitConnectionTestState.Failure("Connection failed: $errMsg")
            }
        }
    }

    fun performSave() {
        scope.launch {
            saving = true
            saveError = null
            cloneError = null
            val formSnapshot = currentFormSnapshot()

            // If cloning a new repo, clone first
            val cloneAndAdd = onCloneAndAdd
            if (cloneMode == CloneMode.CloneNewRepository && cloneAndAdd != null) {
                val outcome = performCloneAndSave(
                    form = formSnapshot,
                    credentialStore = credentialStore,
                    connectionStore = connectionStore,
                    gitConfigRepository = gitConfigRepository,
                    onCloneAndAdd = cloneAndAdd,
                    onCloneProgress = { cloneProgress = it },
                    onCloneInProgressChange = { cloneInProgress = it },
                )
                saving = false
                when (outcome) {
                    is CloneAndSaveOutcome.CloneFailed -> cloneError = outcome.message
                    is CloneAndSaveOutcome.Saved -> {
                        onCloneComplete?.invoke(outcome.newGraphId)
                        onSave()
                    }
                    is CloneAndSaveOutcome.SaveFailed -> saveError = "Failed to save configuration."
                }
                return@launch
            }

            val outcome = performSaveExistingConfig(
                form = formSnapshot,
                existingConfig = existingConfig,
                credentialStore = credentialStore,
                connectionStore = connectionStore,
                gitConfigRepository = gitConfigRepository,
                gitSyncService = gitSyncService,
            )
            saving = false
            when (outcome) {
                SaveConfigOutcome.Saved -> onSave()
                SaveConfigOutcome.Failed -> saveError = "Failed to save configuration."
            }
        }
    }

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
        GitSetupOAuthDialogHost(
            state = oauthDialogState!!,
            clipboardManager = clipboardManager,
            onCancel = {
                oauthJob?.cancel()
                showOAuthDialog = false
                oauthDialogState = null
            },
            onRetry = ::retryGithubOAuthFlow,
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
                    cloneMode = cloneMode,
                    onCloneModeChange = ::selectCloneMode,
                    onNext = { step = 2 },
                )

                2 -> Step2RepoPath(
                    cloneMode = cloneMode,
                    repoRoot = repoRoot,
                    onRepoRootChange = { repoRoot = it },
                    cloneUrl = cloneUrl,
                    onCloneUrlChange = { cloneUrl = it },
                    graphName = graphName,
                    onGraphNameChange = { graphName = it },
                    graphDescription = graphDescription,
                    onGraphDescriptionChange = { graphDescription = it },
                    wikiSubdir = wikiSubdir,
                    onWikiSubdirChange = { newValue ->
                        // Reject rather than silently store a picked content:// URI here — a
                        // relative subdirectory never contains a URI scheme.
                        if (!looksLikeUri(newValue)) wikiSubdir = newValue
                    },
                    onBack = { step = 1 },
                    onNext = { step = 3 },
                    nextEnabled = repoRoot.isNotBlank() && (cloneMode == CloneMode.UseExistingClone || cloneUrl.isNotBlank()) &&
                        wikiSubdirError(wikiSubdir) == null,
                    saveToAppStorage = cloneMode == CloneMode.CloneNewRepository && cloneStorageLocation is StorageLocation.AppOwned,
                    onBrowseRepoRoot = ::browseRepoRoot,
                    onBrowseWikiSubdir = { wikiSubdirBrowserOpen = true },
                    // Android SAF/wasm OPFS picker grants are scoped to exactly the folder the
                    // user picked — a .git above it is structurally invisible, so
                    // GraphManager.detectGitRoot()'s upward walk never runs for these (see its own
                    // doc comment). Driven by the live check above rather than a saf://-prefix
                    // guess, so it also covers wasm's differently-schemed picker path.
                    detectionUnavailable = hasGitAtRepoRoot == false && detectedRepoRoot.isNullOrEmpty(),
                    existingRepoNeedsAllFilesAccess = existingRepoNeedsAllFilesAccess,
                )

                3 -> {
                    var tokenVisible by remember { mutableStateOf(false) }
                    Step3Auth(
                        authType = authType,
                        onAuthTypeChange = ::selectAuthType,
                        sshKeyPath = sshKeyPath,
                        onSshKeyPathChange = { sshKeyPath = it },
                        httpsToken = httpsToken,
                        onHttpsTokenChange = {
                            httpsToken = it
                            selectedHttpsConnectionId = null
                        },
                        tokenVisible = tokenVisible,
                        onToggleTokenVisible = { tokenVisible = !tokenVisible },
                        sshPassphrase = sshPassphrase,
                        onSshPassphraseChange = { sshPassphrase = it },
                        onBack = { step = 2 },
                        onNext = { step = 4 },
                        httpsConnections = httpsConnections,
                        selectedHttpsConnectionId = selectedHttpsConnectionId,
                        onSelectHttpsConnection = { connection ->
                            selectedHttpsConnectionId = connection?.id
                            httpsToken = connection?.let { connectionStore.getSecret(it) } ?: ""
                        },
                        oauthConnections = oauthConnections,
                        selectedOauthConnectionId = selectedOauthConnectionId,
                        onSelectOauthConnection = { connection ->
                            selectedOauthConnectionId = connection?.id
                            oauthConnectedAs = connection?.accountLabel
                        },
                        oauthConnectedAs = oauthConnectedAs,
                        onStartOAuthFlow = ::startGithubOAuthFlowFresh,
                        showOAuthDialog = showOAuthDialog,
                        deviceFlowEnabled = deviceFlowClient != null,
                        onBrowseSshKey = ::browseSshKey,
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
                    testState = testState,
                    saving = saving,
                    saveError = saveError,
                    existingRepoNeedsAllFilesAccess = existingRepoNeedsAllFilesAccess,
                    onBack = { step = 4 },
                    onTestConnection = ::performTestConnection,
                    cloneInProgress = cloneInProgress,
                    cloneProgress = cloneProgress,
                    cloneError = cloneError,
                    onSave = ::performSave,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (wikiSubdirBrowserOpen) {
        WikiSubdirBrowserDialog(
            fileSystem = fileSystem,
            repoRoot = repoRoot,
            initialSubdir = wikiSubdir,
            onDismiss = { wikiSubdirBrowserOpen = false },
            onSelect = { selected -> wikiSubdir = selected },
        )
    }

    // Story 2.2.2: clone-destination picker for "clone a remote repository" — see the
    // onBrowseRepoRoot branch above that opens this. Only ever shown when
    // fileSystem.supportsAppOwnedStorage (Android today), so this dialog never appears on
    // Desktop/iOS, matching those platforms' pre-feature "Browse…"-only behavior.
    if (showCloneLocationPicker) {
        GitSetupCloneLocationPickerHost(
            fileSystem = fileSystem,
            pendingAppOwnedGraphPath = pendingAppOwnedGraphPath,
            onPendingSafRepoRootChange = { pendingSafRepoRoot = it },
            onConfirm = { location ->
                showCloneLocationPicker = false
                cloneStorageLocation = location
                repoRoot = when (location) {
                    is StorageLocation.AppOwned -> pendingAppOwnedGraphPath
                    is StorageLocation.SafFolder -> pendingSafRepoRoot
                    else -> repoRoot
                }
                wikiSubdir = "" // stale relative to the old root — start over, not silently wrong
            },
            onDismiss = { showCloneLocationPicker = false },
        )
    }
}
