// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

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
import dev.stapler.stelekit.git.Banner

/** Banner copy for the two sync launch banners; the mismatch banner keeps its own composable. */
internal fun syncBannerText(banner: Banner, behindCount: Int): String? = when (banner) {
    Banner.FirstSyncReview -> "Review the first sync before SteleKit syncs this graph for you."
    Banner.UpdatesWaiting -> "Updates are waiting — $behindCount remote ${if (behindCount == 1) "commit" else "commits"} not merged yet."
    Banner.Mismatch -> null
}

/** "Review first sync" and "Updates are waiting" launch banner (UX-72). Dismiss only hides it. */
@Composable
fun SyncLaunchBanner(
    banner: Banner,
    behindCount: Int,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = syncBannerText(banner, behindCount) ?: return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = text },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReview) { Text("Review") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
