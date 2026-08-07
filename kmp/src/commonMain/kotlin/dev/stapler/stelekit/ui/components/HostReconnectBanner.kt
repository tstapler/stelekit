package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.HostAccessState

/**
 * Top-of-app banner shown when the browser has silently lost (or never re-confirmed) permission to
 * the connected host folder — [HostAccessState.PromptNeeded] or [HostAccessState.Denied]. Mirrors
 * [BrowserOnlySyncBanner]'s structure, but for a different underlying condition: here the graph
 * *was* connected to live folder sync, but no file is being read from or written to disk until the
 * user re-grants permission, because a browser can silently drop a `FileSystemDirectoryHandle`
 * permission grant across restarts and only a real user gesture can re-request it (see
 * [dev.stapler.stelekit.platform.HostDirectorySync.requestHostDirectoryAccess]).
 *
 * Until now this state was only surfaced via [FolderSyncStatusBadge], a small sidebar badge — easy
 * to miss, which let host-folder edits go silently unsynced for an entire session. This banner is
 * additive, not a replacement: the sidebar badge still renders the same state.
 *
 * Callers gate visibility on [state] being [HostAccessState.PromptNeeded] or
 * [HostAccessState.Denied]; [onReconnect] must invoke
 * `PlatformFileSystem.hostDirectorySync.requestHostDirectoryAccess` from this button's click
 * handler so the browser sees the required transient user activation.
 */
@Composable
fun HostReconnectBanner(
    state: HostAccessState,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when (state) {
        is HostAccessState.Denied ->
            "Folder access was declined. Changes made outside SteleKit will not appear here " +
                "until you grant access again."
        else ->
            "This browser needs permission to reconnect to your synced folder. Changes made " +
                "outside SteleKit will not appear here until you reconnect."
    }
    val buttonLabel = if (state is HostAccessState.Denied) "Grant access" else "Reconnect folder"

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = message },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReconnect) { Text(buttonLabel) }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
