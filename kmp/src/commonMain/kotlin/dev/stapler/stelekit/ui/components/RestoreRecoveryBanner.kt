// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.db.RestoreNotice
import dev.stapler.stelekit.db.RestoreRecovery

/**
 * Shown once, on the first open after a restore was interrupted and recovered at launch (plan AC 3.4-G). It does not
 * depend on developer mode or the console, so an owner who never opens the console still learns their data changed.
 */
@Composable
fun RestoreRecoveryBannerHost(modifier: Modifier = Modifier) {
    val notice by RestoreRecovery.notice.collectAsState()
    notice?.let { RestoreRecoveryBanner(it, onDismiss = { RestoreRecovery.dismiss() }, modifier = modifier) }
}

@Composable
internal fun RestoreRecoveryBanner(notice: RestoreNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag("restore-recovery-banner")
            .semantics {
                contentDescription = notice.headline
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = notice.headline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("restore-recovery-ok")) { Text("OK") }
        }
    }
}
