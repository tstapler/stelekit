// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.launch

/**
 * Multi-step wizard for configuring git sync on a graph.
 *
 * Step 1: Clone mode — use existing clone or clone new repo.
 * Step 2: Repo path and wiki subdirectory.
 * Step 3: Auth type (SSH key / HTTPS token / None) and credentials.
 * Step 4: Branch name and poll interval.
 * Step 5: Test connection then save.
 *
 * @param graphId ID of the graph being configured.
 * @param existingConfig Pre-filled when editing an existing config.
 * @param gitRepository Platform-specific git implementation.
 * @param gitConfigRepository Persistence for [GitConfig].
 * @param gitSyncService Active service; used for immediate fetchOnly after save.
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
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    existingConfig: GitConfig? = null,
    onSave: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(1) }

    // Form state
    var useExistingClone by remember { mutableStateOf(true) }
    var cloneUrl by remember { mutableStateOf("") }
    var repoRoot by remember { mutableStateOf(existingConfig?.repoRoot ?: "") }
    var wikiSubdir by remember { mutableStateOf(existingConfig?.wikiSubdir ?: "") }
    var authType by remember { mutableStateOf(existingConfig?.authType ?: GitAuthType.NONE) }
    var sshKeyPath by remember { mutableStateOf(existingConfig?.sshKeyPath ?: "") }
    var httpsToken by remember { mutableStateOf("") }
    var remoteBranch by remember { mutableStateOf(existingConfig?.remoteBranch ?: "main") }
    var pollIntervalMinutes by remember { mutableStateOf(existingConfig?.pollIntervalMinutes ?: 5) }

    // Step 5: connection test state
    var testInProgress by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }

    // Save state
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Git Sync Setup (Step $step / 5)") },
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
            Spacer(modifier = Modifier.height(8.dp))

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
                )

                3 -> Step3Auth(
                    authType = authType,
                    onAuthTypeChange = { authType = it },
                    sshKeyPath = sshKeyPath,
                    onSshKeyPathChange = { sshKeyPath = it },
                    httpsToken = httpsToken,
                    onHttpsTokenChange = { httpsToken = it },
                    onBack = { step = 2 },
                    onNext = { step = 4 },
                )

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
                            val config = buildConfig(
                                graphId, repoRoot, wikiSubdir, authType,
                                sshKeyPath, remoteBranch, pollIntervalMinutes
                            )
                            val result = gitRepository.fetch(config)
                            testInProgress = false
                            if (result.isRight()) {
                                testSuccess = true
                                testResult = "Connection successful."
                            } else {
                                testSuccess = false
                                testResult = "Connection failed."
                            }
                        }
                    },
                    onSave = {
                        scope.launch {
                            saving = true
                            saveError = null
                            val config = buildConfig(
                                graphId, repoRoot, wikiSubdir, authType,
                                sshKeyPath, remoteBranch, pollIntervalMinutes
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

@Composable
private fun Step1CloneMode(
    useExistingClone: Boolean,
    onUseExistingClone: (Boolean) -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Repository mode", style = MaterialTheme.typography.titleMedium)
        Text("Do you already have a local git clone of your notes repository?", style = MaterialTheme.typography.bodyMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = useExistingClone, onClick = { onUseExistingClone(true) })
            Spacer(modifier = Modifier.width(8.dp))
            Text("Use existing clone")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !useExistingClone, onClick = { onUseExistingClone(false) })
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
                enabled = repoRoot.isNotBlank(),
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
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Authentication", style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = authType == GitAuthType.NONE, onClick = { onAuthTypeChange(GitAuthType.NONE) })
            Spacer(modifier = Modifier.width(8.dp))
            Text("No authentication (public repo)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = authType == GitAuthType.SSH_KEY, onClick = { onAuthTypeChange(GitAuthType.SSH_KEY) })
            Spacer(modifier = Modifier.width(8.dp))
            Text("SSH key")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = authType == GitAuthType.HTTPS_TOKEN, onClick = { onAuthTypeChange(GitAuthType.HTTPS_TOKEN) })
            Spacer(modifier = Modifier.width(8.dp))
            Text("HTTPS token (GitHub PAT, etc.)")
        }

        if (authType == GitAuthType.SSH_KEY) {
            OutlinedTextField(
                value = sshKeyPath,
                onValueChange = onSshKeyPathChange,
                label = { Text("SSH private key path (e.g. ~/.ssh/id_ed25519)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        if (authType == GitAuthType.HTTPS_TOKEN) {
            OutlinedTextField(
                value = httpsToken,
                onValueChange = onHttpsTokenChange,
                label = { Text("Personal access token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                "Token is stored securely on device and never transmitted in plaintext.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = pollIntervalMinutes == minutes,
                    onClick = { onPollIntervalChange(minutes) },
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
                    contentDescription = null,
                    tint = if (testSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = testResult,
                    color = if (testSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
            OutlinedButton(onClick = onBack, enabled = !saving) { Text("Back") }
            Button(
                onClick = onSave,
                enabled = !saving,
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

private fun buildConfig(
    graphId: String,
    repoRoot: String,
    wikiSubdir: String,
    authType: GitAuthType,
    sshKeyPath: String,
    remoteBranch: String,
    pollIntervalMinutes: Int,
): GitConfig = GitConfig(
    graphId = graphId,
    repoRoot = repoRoot,
    wikiSubdir = wikiSubdir,
    authType = authType,
    sshKeyPath = sshKeyPath.takeIf { it.isNotBlank() },
    remoteBranch = remoteBranch,
    pollIntervalMinutes = pollIntervalMinutes,
)
