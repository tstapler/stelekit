// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationSheet(
    onDismiss: () -> Unit,
    onDrawReference: () -> Unit,
    onUseBle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Calibrate Scale",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose a reference object in the photo with a known size.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDrawReference,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                    )
                    Text(
                        text = "Draw on photo",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                FilledTonalButton(
                    onClick = onUseBle,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                    )
                    Text(
                        text = "Use BLE laser",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "If you have a Bluetooth laser meter, connect it for automatic calibration.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
