// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Story 7.2: "Connect Google Account" settings section.
 *
 * Shown in the Settings dialog under the GOOGLE_ACCOUNT category.
 * Displays the connected email when authenticated, or a connect button when not.
 *
 * @param isAuthenticated Whether a Google account is currently connected.
 * @param connectedEmail The email of the connected account, or null if not authenticated.
 * @param isConnecting Whether an authentication flow is in progress.
 * @param errorMessage Optional error message from a failed connect attempt.
 * @param onConnect Invoked when the user taps "Connect Google Account".
 * @param onDisconnect Invoked when the user taps "Disconnect".
 */
@Composable
fun GoogleAccountSettings(
    isAuthenticated: Boolean,
    connectedEmail: String?,
    isConnecting: Boolean,
    errorMessage: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSection(title = "Google Account") {
            if (isAuthenticated && connectedEmail != null) {
                ConnectedAccountCard(
                    email = connectedEmail,
                    onDisconnect = onDisconnect,
                )
            } else {
                DisconnectedAccountCard(
                    isConnecting = isConnecting,
                    errorMessage = errorMessage,
                    onConnect = onConnect,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Google Drive integration lets you export annotated images and " +
                    "import photos from Google Photos using the secure system picker. " +
                    "Only files created by SteleKit are accessible.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectedAccountCard(
    email: String,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFFE8F5E9),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    text = "Connected as $email",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1B5E20),
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Disconnect Google Account")
            }
        }
    }
}

@Composable
private fun DisconnectedAccountCard(
    isConnecting: Boolean,
    errorMessage: String?,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    text = "No Google account connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConnect,
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1565C0),
                ),
            ) {
                if (isConnecting) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("Connecting…")
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudQueue,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("Connect Google Account")
                }
            }
        }
    }
}
