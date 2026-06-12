// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.toSyncErrorMessage
import dev.stapler.stelekit.git.model.SyncState
import kotlinx.coroutines.delay

/**
 * A compact badge + icon button combo displayed in the sidebar header area.
 *
 * - [SyncState.Idle]: greyed-out sync icon, no badge text
 * - [SyncState.Fetching / Merging / Pushing / Committing]: animated spinning sync icon
 * - [SyncState.MergeAvailable(n)]: blue cloud-download icon with "↓ n" label
 * - [SyncState.ConflictPending]: amber warning icon with "Conflict" label
 * - [SyncState.Error]: red error icon with "Error" label (routes to onAuthError if AuthFailed)
 * - [SyncState.Success]: brief green checkmark, fades after 3 seconds
 *
 * @param syncState Current sync state from [GitSyncService].
 * @param onSyncClick Called when the manual sync icon button is tapped.
 * @param isGitConfigured Whether git sync has been configured for the current graph.
 * @param onAuthError Called when the sync error is an authentication failure.
 */
@Composable
fun SyncStatusBadge(
    syncState: SyncState,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier,
    isGitConfigured: Boolean = true,
    onAuthError: (() -> Unit)? = null,
) {
    if (!isGitConfigured) {
        Box(modifier = modifier.padding(horizontal = 4.dp)) {
            Text(
                "Set up sync",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .clickable { onSyncClick() }
                    .padding(4.dp),
            )
        }
        return
    }

    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Badge area — state-specific icon and label
        SyncStateBadge(syncState = syncState, onSyncClick = onSyncClick, onAuthError = onAuthError)

        Spacer(modifier = Modifier.width(4.dp))

        // Manual sync button — always visible
        IconButton(
            onClick = onSyncClick,
            enabled = isGitConfigured,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = if (isGitConfigured) "Sync now" else "Git sync not configured",
                tint = if (isGitConfigured) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SyncStateBadge(
    syncState: SyncState,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAuthError: (() -> Unit)? = null,
) {
    when (syncState) {
        is SyncState.Idle -> {
            // No visible badge when idle
        }

        is SyncState.Fetching,
        is SyncState.Merging,
        is SyncState.Pushing,
        is SyncState.Committing -> {
            CircularProgressIndicator(
                modifier = modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        is SyncState.MergeAvailable -> {
            Box(modifier = modifier.clickable { onSyncClick() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Updates available",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    if (syncState.commitCount > 0) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "↓ ${syncState.commitCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        is SyncState.ConflictPending -> {
            Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Merge conflict",
                    tint = Color(0xFFF59E0B), // amber-400
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "Conflict",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF59E0B),
                )
            }
        }

        is SyncState.CredentialVaultLocked -> {
            Row(
                modifier = modifier.clickable { onSyncClick() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Vault locked — tap to unlock",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "Vault locked",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        is SyncState.Error -> {
            val isAuthError = syncState.error is DomainError.GitError.AuthFailed
            Row(
                modifier = modifier.clickable {
                    if (isAuthError && onAuthError != null) onAuthError() else onSyncClick()
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = if (isAuthError) "Authentication failed — tap to update credentials" else "Sync error — tap to retry",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = syncState.error.toSyncErrorMessage(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
        }

        is SyncState.CredentialExpired -> {
            Row(
                modifier = modifier.clickable { onAuthError?.invoke() ?: onSyncClick() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "GitHub authentication expired — tap to re-connect",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "Re-connect GitHub",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is SyncState.Success -> {
            // Brief green checkmark that fades after 3 seconds
            var visible by remember(syncState) { mutableStateOf(true) }
            LaunchedEffect(syncState) {
                delay(3_000)
                visible = false
            }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Sync complete",
                    tint = Color(0xFF10B981), // emerald-500
                    modifier = modifier.size(16.dp),
                )
            }
        }
    }
}
