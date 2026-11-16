// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun CalibrationNudgeBanner(
    isFirstUse: Boolean,
    onCalibrateClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** When set, the whole banner is one 48 dp target that opens the Known distances sheet, with the same text as the pill. */
    onOpenKnownDistances: (() -> Unit)? = null,
) {
    if (onOpenKnownDistances != null) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .testTag("calibration_banner")
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = "Open known distances", onClick = onOpenKnownDistances),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Straighten, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(CalibrationStatePresenter.NOT_CALIBRATED, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
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
