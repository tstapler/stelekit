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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * State for the GitHub OAuth device flow dialog.
 */
sealed class OAuthDialogState {
    data object Loading : OAuthDialogState()
    data class ShowCode(
        val userCode: String,
        val verificationUri: String,
        val expiresAt: Long,
    ) : OAuthDialogState()
    data object Polling : OAuthDialogState()
    data class Success(val username: String) : OAuthDialogState()
    data class Error(val message: String) : OAuthDialogState()
}

/**
 * Dialog showing the GitHub device flow UX:
 * - Loading: requesting code from GitHub
 * - ShowCode: user code to enter at github.com/login/device with copy/open buttons
 * - Polling: waiting for GitHub to confirm authorization
 * - Success: token retrieved, shows connected username
 * - Error: shows error with retry/cancel options
 */
@Composable
fun GitHubOAuthDialog(
    state: OAuthDialogState,
    onCopyCode: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onCancel() },
        title = { Text("Connect GitHub Account") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    is OAuthDialogState.Loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("Requesting code from GitHub…")
                        }
                    }

                    is OAuthDialogState.ShowCode -> {
                        Text("Open github.com/login/device and enter this code:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = state.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        CountdownText(expiresAt = state.expiresAt)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onCopyCode(state.userCode) },
                                modifier = Modifier.weight(1f),
                            ) { Text("Copy code") }
                            Button(
                                onClick = { onOpenBrowser(state.verificationUri) },
                                modifier = Modifier.weight(1f),
                            ) { Text("Open GitHub") }
                        }
                    }

                    is OAuthDialogState.Polling -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("Waiting for GitHub authorization…")
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    is OAuthDialogState.Success -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(24.dp),
                            )
                            Text(
                                "Connected as @${state.username}",
                                color = Color(0xFF10B981),
                            )
                        }
                    }

                    is OAuthDialogState.Error -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp),
                            )
                            Text(
                                state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is OAuthDialogState.Success -> Button(onClick = onDone) { Text("Done") }
                is OAuthDialogState.Error -> Button(onClick = onRetry) { Text("Try Again") }
                else -> {}
            }
        },
        dismissButton = {
            when (state) {
                is OAuthDialogState.Success -> {}
                else -> TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CountdownText(expiresAt: Long) {
    var secondsLeft by remember { mutableLongStateOf(maxOf(0L, (expiresAt - System.currentTimeMillis()) / 1000L)) }
    LaunchedEffect(expiresAt) {
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft = maxOf(0L, (expiresAt - System.currentTimeMillis()) / 1000L)
        }
    }
    val minutes = secondsLeft / 60
    val seconds = secondsLeft % 60
    Text(
        text = "Expires in ${minutes}m ${seconds.toString().padStart(2, '0')}s",
        style = MaterialTheme.typography.labelSmall,
        color = if (secondsLeft < 60) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
