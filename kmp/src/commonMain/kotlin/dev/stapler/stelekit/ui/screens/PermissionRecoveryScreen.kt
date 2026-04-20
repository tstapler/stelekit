package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.components.LogDashboard

@Composable
fun PermissionRecoveryScreen(
    folderName: String?,
    onReconnectFolder: () -> Unit,
    onChooseDifferentFolder: () -> Unit,
    errorMessage: String? = null,
) {
    var showLogs by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (showLogs) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showLogs = false }) { Text("← Back") }
                    Text(
                        text = "Diagnostic Logs",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider()
                LogDashboard(modifier = Modifier.weight(1f))
            }
            return@Surface
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.FolderOff,
                contentDescription = "Folder access unavailable",
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Can't access your notes folder",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            val folderDesc = if (folderName != null) "\"$folderName\"" else "your notes folder"
            Text(
                text = "SteleKit no longer has permission to read $folderDesc. This can happen if permissions were reset or the app was reinstalled.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onReconnectFolder,
                modifier = Modifier.fillMaxWidth(0.7f)
            ) {
                Text("Reconnect Folder")
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = onChooseDifferentFolder,
                modifier = Modifier.fillMaxWidth(0.7f)
            ) {
                Text("Choose Different Folder")
            }
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = { showLogs = true }) {
                Text(
                    text = "View diagnostic logs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
