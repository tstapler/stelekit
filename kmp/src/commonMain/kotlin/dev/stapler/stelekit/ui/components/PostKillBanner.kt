// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.diagnostics.PostKillBannerController

/** Collects [controller] and shows [PostKillBanner] while it holds a notice. */
@Composable
fun PostKillBannerHost(
    controller: PostKillBannerController,
    onViewLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notice by controller.notice.collectAsState()
    if (notice != null) {
        PostKillBanner(
            onViewLog = {
                controller.dismiss()
                onViewLog()
            },
            onDismiss = controller::dismiss,
            modifier = modifier,
        )
    }
}

/** UX-22: shown once on the launch after the process died while the annotation editor was open. */
@Composable
fun PostKillBanner(
    onViewLog: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = PostKillBannerController.MESSAGE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onViewLog) { Text("View log") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
