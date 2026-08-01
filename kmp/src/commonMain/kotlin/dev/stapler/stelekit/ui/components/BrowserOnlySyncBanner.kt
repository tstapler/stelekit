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

/**
 * Top-of-app banner shown when a graph is stored browser-only (never connected to live folder
 * sync) — surfaces the fact that edits are not being written to disk. Mirrors
 * [dev.stapler.stelekit.ui.components.git.GitDetectionBanner]'s structure. Callers gate visibility
 * on [dev.stapler.stelekit.platform.HostAccessState.NotApplicable] plus
 * `supportsNativeDirectoryPicker` plus the per-graph dismissed flag
 * ([dev.stapler.stelekit.model.GraphInfo.browserOnlySyncBannerDismissed]).
 */
@Composable
fun BrowserOnlySyncBanner(
    onEnableSync: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "This graph is stored in your browser only — changes are not being synced to disk"
            },
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
                text = "This graph is stored in your browser only. Your changes are not being " +
                    "synced to disk — enable live folder sync to keep them safe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEnableSync) { Text("Enable sync") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
