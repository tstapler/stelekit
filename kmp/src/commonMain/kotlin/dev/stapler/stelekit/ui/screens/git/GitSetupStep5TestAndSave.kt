// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun Step5TestAndSave(
    testState: GitConnectionTestState,
    // ponytail: saving/cloneInProgress/existingRepoNeedsAllFilesAccess each independently gate a
    // single, self-contained UI element (a spinner, a progress row, a warning banner) — not one
    // function secretly doing two unrelated things. Left as plain booleans.
    saving: Boolean,
    saveError: String?,
    onBack: () -> Unit,
    onTestConnection: () -> Unit,
    onCancelTestConnection: () -> Unit,
    cloneInProgress: Boolean = false,
    cloneProgress: String = "",
    cloneError: String? = null,
    existingRepoNeedsAllFilesAccess: Boolean = false,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Test and save", style = MaterialTheme.typography.titleMedium)
        Text("Optionally test your connection before saving.", style = MaterialTheme.typography.bodyMedium)

        // Repeats Step2RepoPath's warning here since this is where the underlying problem
        // actually surfaces to the user — a cryptic "repository not found: .../gitshadow"
        // from Test connection — not just at the earlier repo-path step.
        if (existingRepoNeedsAllFilesAccess) {
            AllFilesAccessWarning()
        }

        TestConnectionButton(
            testState = testState,
            onTestConnection = onTestConnection,
            onCancelTestConnection = onCancelTestConnection,
        )

        if (cloneInProgress) {
            CloneProgressRow(cloneProgress)
        }

        cloneError?.let { error -> ErrorText(error) }
        saveError?.let { error -> ErrorText(error) }

        BackAndSaveRow(saving = saving, cloneInProgress = cloneInProgress, onBack = onBack, onSave = onSave)
    }
}

@Composable
private fun BackAndSaveRow(
    saving: Boolean,
    cloneInProgress: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val busy = saving || cloneInProgress
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(onClick = onBack, enabled = !busy) { Text("Back") }
        Button(
            onClick = onSave,
            enabled = !busy,
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

@Composable
private fun AllFilesAccessWarning() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "This repo was picked via the system document picker, so \"Test " +
                "connection\" will fail here unless \"All files access\" is granted.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { dev.stapler.stelekit.platform.openAllFilesAccessSettings() }) {
            Text("Open \"All files access\" settings")
        }
    }
}

@Composable
private fun TestConnectionButton(
    testState: GitConnectionTestState,
    onTestConnection: () -> Unit,
    onCancelTestConnection: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onTestConnection,
                enabled = testState !is GitConnectionTestState.InProgress,
                modifier = Modifier.weight(1f),
            ) {
                if (testState is GitConnectionTestState.InProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Test connection")
            }
            // Finding #2: testRemote() is bounded by a 15s timeout, but a disabled button with no
            // way out until then is still worth an explicit escape hatch.
            if (testState is GitConnectionTestState.InProgress) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onCancelTestConnection) { Text("Cancel") }
            }
        }

        TestResultRow(testState)
    }
}

@Composable
private fun TestResultRow(testState: GitConnectionTestState) {
    val resultMessage = when (testState) {
        is GitConnectionTestState.Success -> testState.message
        is GitConnectionTestState.Failure -> testState.message
        else -> null
    } ?: return
    val success = testState is GitConnectionTestState.Success
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (success) Icons.Default.Check else Icons.Default.Error,
            contentDescription = if (success) "Success" else "Error",
            tint = if (success) Color(0xFF047857) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = resultMessage,
            color = if (success) Color(0xFF047857) else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CloneProgressRow(cloneProgress: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = cloneProgress.ifBlank { "Cloning repository…" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
