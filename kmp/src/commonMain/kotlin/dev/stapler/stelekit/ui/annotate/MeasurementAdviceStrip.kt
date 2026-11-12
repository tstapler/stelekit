// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.calibration.MeasurementReliability

private val WARNING_FILL = Color(0xFFFFF3E0)
private val WARNING_TEXT = Color(0xFF8A4B00)

/**
 * Editor status area for measurement confidence: warnings about the selected (else latest) measurement
 * or the calibration, neutral notes (applied lens correction, unknown tilt) and, behind "Tips", how to
 * shoot for better accuracy. Renders nothing when there is nothing to say.
 */
@Composable
fun MeasurementAdviceStrip(reliability: MeasurementReliability?, modifier: Modifier = Modifier) {
    if (reliability == null || (!reliability.hasWarning && reliability.notes.isEmpty())) return
    var showTips by remember { mutableStateOf(false) }
    Surface(
        color = if (reliability.hasWarning) WARNING_FILL else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            reliability.warnings.forEach { warning ->
                Text("⚠ $warning", style = MaterialTheme.typography.bodySmall, color = WARNING_TEXT)
            }
            reliability.notes.forEach { note ->
                Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val guidance = reliability.guidance
            if (guidance != null) {
                TextButton(onClick = { showTips = !showTips }) { Text(if (showTips) "Hide tips" else "Tips for accuracy") }
                if (showTips) Text(guidance, style = MaterialTheme.typography.bodySmall, color = WARNING_TEXT)
            }
        }
    }
}
