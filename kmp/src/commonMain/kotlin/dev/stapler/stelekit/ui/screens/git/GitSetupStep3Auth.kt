// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitCredentialConnection
import dev.stapler.stelekit.ui.components.MutedText

@Composable
internal fun Step3Auth(
    authType: GitAuthType,
    onAuthTypeChange: (GitAuthType) -> Unit,
    sshKeyPath: String,
    onSshKeyPathChange: (String) -> Unit,
    httpsToken: String,
    onHttpsTokenChange: (String) -> Unit,
    // ponytail: tokenVisible is a pure password-visibility UI toggle, independent of every other
    // branch in this function — not the "secretly doing two things" anti-pattern Fowler targets.
    tokenVisible: Boolean,
    onToggleTokenVisible: () -> Unit,
    sshPassphrase: String,
    onSshPassphraseChange: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    httpsConnections: List<GitCredentialConnection> = emptyList(),
    selectedHttpsConnectionId: String? = null,
    onSelectHttpsConnection: (GitCredentialConnection?) -> Unit = {},
    oauthConnections: List<GitCredentialConnection> = emptyList(),
    selectedOauthConnectionId: String? = null,
    onSelectOauthConnection: (GitCredentialConnection?) -> Unit = {},
    oauthConnectedAs: String? = null,
    onStartOAuthFlow: () -> Unit = {},
    showOAuthDialog: Boolean = false,
    // ponytail: deviceFlowEnabled is a platform-capability flag (is OAuth available at all?), not a
    // mode selector — the OAuth section renders identically either way, just disables one button.
    deviceFlowEnabled: Boolean = false,
    onBrowseSshKey: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Authentication", style = MaterialTheme.typography.titleMedium)

        AuthTypeRadioRow(authType, GitAuthType.NONE, "No authentication (public repo)", onAuthTypeChange)
        AuthTypeRadioRow(authType, GitAuthType.SSH_KEY, "SSH key", onAuthTypeChange)
        AuthTypeRadioRow(authType, GitAuthType.HTTPS_TOKEN, "HTTPS token (GitHub PAT, etc.)", onAuthTypeChange)
        AuthTypeRadioRow(authType, GitAuthType.GITHUB_OAUTH, "GitHub (OAuth)", onAuthTypeChange)

        if (authType == GitAuthType.SSH_KEY) {
            SshKeySection(
                sshKeyPath = sshKeyPath,
                onSshKeyPathChange = onSshKeyPathChange,
                onBrowseSshKey = onBrowseSshKey,
                sshPassphrase = sshPassphrase,
                onSshPassphraseChange = onSshPassphraseChange,
            )
        }

        if (authType == GitAuthType.HTTPS_TOKEN) {
            HttpsTokenSection(
                httpsToken = httpsToken,
                onHttpsTokenChange = onHttpsTokenChange,
                tokenVisible = tokenVisible,
                onToggleTokenVisible = onToggleTokenVisible,
                httpsConnections = httpsConnections,
                selectedHttpsConnectionId = selectedHttpsConnectionId,
                onSelectHttpsConnection = onSelectHttpsConnection,
            )
        }

        if (authType == GitAuthType.GITHUB_OAUTH) {
            OAuthSection(
                oauthConnections = oauthConnections,
                selectedOauthConnectionId = selectedOauthConnectionId,
                onSelectOauthConnection = onSelectOauthConnection,
                oauthConnectedAs = oauthConnectedAs,
                onStartOAuthFlow = onStartOAuthFlow,
                deviceFlowEnabled = deviceFlowEnabled,
            )
        }

        GitSetupNavRow(onBack = onBack, onNext = onNext)
    }
}

@Composable
private fun AuthTypeRadioRow(
    authType: GitAuthType,
    value: GitAuthType,
    label: String,
    onAuthTypeChange: (GitAuthType) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = authType == value,
            role = Role.RadioButton,
            onClick = { onAuthTypeChange(value) },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = authType == value, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SshKeySection(
    sshKeyPath: String,
    onSshKeyPathChange: (String) -> Unit,
    onBrowseSshKey: (() -> Unit)?,
    sshPassphrase: String,
    onSshPassphraseChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        MutedText(
            "On Android, the selected file is copied to secure app storage. The path shown is the app-private copy.",
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
}

@Composable
private fun HttpsTokenSection(
    httpsToken: String,
    onHttpsTokenChange: (String) -> Unit,
    tokenVisible: Boolean,
    onToggleTokenVisible: () -> Unit,
    httpsConnections: List<GitCredentialConnection>,
    selectedHttpsConnectionId: String?,
    onSelectHttpsConnection: (GitCredentialConnection?) -> Unit,
) {
    if (httpsConnections.isNotEmpty()) {
        SavedHttpsTokensList(httpsConnections, selectedHttpsConnectionId, onSelectHttpsConnection)
    }
    if (selectedHttpsConnectionId == null) {
        NewHttpsTokenField(httpsToken, onHttpsTokenChange, tokenVisible, onToggleTokenVisible)
    }
}

@Composable
private fun SavedHttpsTokensList(
    httpsConnections: List<GitCredentialConnection>,
    selectedHttpsConnectionId: String?,
    onSelectHttpsConnection: (GitCredentialConnection?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Saved tokens", style = MaterialTheme.typography.labelMedium)
        httpsConnections.forEach { connection ->
            Row(
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = selectedHttpsConnectionId == connection.id,
                    role = Role.RadioButton,
                    onClick = { onSelectHttpsConnection(connection) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedHttpsConnectionId == connection.id, onClick = null)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(connection.accountLabel, style = MaterialTheme.typography.bodyMedium)
                    MutedText(connection.host)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().selectable(
                selected = selectedHttpsConnectionId == null,
                role = Role.RadioButton,
                onClick = { onSelectHttpsConnection(null) },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selectedHttpsConnectionId == null, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Use a new token")
        }
    }
}

@Composable
private fun NewHttpsTokenField(
    httpsToken: String,
    onHttpsTokenChange: (String) -> Unit,
    tokenVisible: Boolean,
    onToggleTokenVisible: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        MutedText("Token is encrypted at rest. For stronger protection, use SSH key auth.")
    }
}

@Composable
private fun OAuthSection(
    oauthConnections: List<GitCredentialConnection>,
    selectedOauthConnectionId: String?,
    onSelectOauthConnection: (GitCredentialConnection?) -> Unit,
    oauthConnectedAs: String?,
    onStartOAuthFlow: () -> Unit,
    deviceFlowEnabled: Boolean,
) {
    // A saved connection is selected: show it as "connected" (matching the fresh-device-flow
    // banner below) plus the picker for switching to a different saved account or a new one.
    // Otherwise: the device-flow entry point, with the picker (if any saved accounts exist)
    // offered above it as a faster alternative to redoing the flow.
    if (selectedOauthConnectionId != null && oauthConnectedAs != null) {
        ConnectedOAuthAccount(
            oauthConnections = oauthConnections,
            selectedOauthConnectionId = selectedOauthConnectionId,
            onSelectOauthConnection = onSelectOauthConnection,
            oauthConnectedAs = oauthConnectedAs,
            onStartOAuthFlow = onStartOAuthFlow,
            deviceFlowEnabled = deviceFlowEnabled,
        )
    } else {
        DisconnectedOAuthPrompt(
            oauthConnections = oauthConnections,
            onSelectOauthConnection = onSelectOauthConnection,
            onStartOAuthFlow = onStartOAuthFlow,
            deviceFlowEnabled = deviceFlowEnabled,
        )
    }
}

@Composable
private fun ConnectedOAuthAccount(
    oauthConnections: List<GitCredentialConnection>,
    selectedOauthConnectionId: String,
    onSelectOauthConnection: (GitCredentialConnection?) -> Unit,
    oauthConnectedAs: String,
    onStartOAuthFlow: () -> Unit,
    deviceFlowEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        if (oauthConnections.size > 1) {
            Text("Switch account", style = MaterialTheme.typography.labelMedium)
            oauthConnections.filter { it.id != selectedOauthConnectionId }.forEach { connection ->
                OAuthAccountRow(connection, onSelectOauthConnection)
            }
        }
        OutlinedButton(
            onClick = onStartOAuthFlow,
            enabled = deviceFlowEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect a different account") }
    }
}

@Composable
private fun DisconnectedOAuthPrompt(
    oauthConnections: List<GitCredentialConnection>,
    onSelectOauthConnection: (GitCredentialConnection?) -> Unit,
    onStartOAuthFlow: () -> Unit,
    deviceFlowEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (oauthConnections.isNotEmpty()) {
            Text("Saved accounts", style = MaterialTheme.typography.labelMedium)
            oauthConnections.forEach { connection ->
                OAuthAccountRow(connection, onSelectOauthConnection)
            }
            MutedText("Or connect a new account below.")
        }
        Button(
            onClick = onStartOAuthFlow,
            enabled = deviceFlowEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect GitHub Account") }
        if (!deviceFlowEnabled) {
            MutedText("GitHub OAuth is not available on this platform.")
        }
    }
}

@Composable
private fun OAuthAccountRow(
    connection: GitCredentialConnection,
    onSelectOauthConnection: (GitCredentialConnection?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = false,
            role = Role.RadioButton,
            onClick = { onSelectOauthConnection(connection) },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = false, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("@${connection.accountLabel}", style = MaterialTheme.typography.bodyMedium)
    }
}
