// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CalibrationNudgeBanner(
    isFirstUse: Boolean,
    onCalibrateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        if (isFirstUse) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Straighten,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Set a scale to measure distances",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Tap a known length on the photo to calibrate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(modifier = Modifier.padding(top = 12.dp))
                FilledTonalButton(
                    onClick = onCalibrateClick,
                ) {
                    Text("Calibrate Now")
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Scale not set — ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                TextButton(
                    onClick = onCalibrateClick,
                ) {
                    Text(
                        text = "tap to calibrate",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
