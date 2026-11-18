// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod

@Composable
fun CalibrationConfidenceBadge(
    calibration: Calibration?,
    modifier: Modifier = Modifier,
    /** Optional trailing action text on the same line (for example the object-height entry); no extra height. */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    data class BadgeConfig(
        val icon: ImageVector,
        val label: String,
        val containerColor: Color,
        val accessibleDescription: String,
    )

    val config = when (calibration?.method) {
        CalibrationMethod.BLE_LASER -> BadgeConfig(
            icon = Icons.Default.CheckCircle,
            label = "Scale set",
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            accessibleDescription = "Calibration status: scale set with high confidence",
        )
        CalibrationMethod.MANUAL_REFERENCE -> BadgeConfig(
            icon = Icons.Default.CheckCircle,
            label = "Scale set",
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            accessibleDescription = "Calibration status: scale set with high confidence",
        )
        CalibrationMethod.ARCORE_DEPTH -> BadgeConfig(
            icon = Icons.Default.Warning,
            label = "Scale estimated",
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            accessibleDescription = "Calibration status: scale estimated, measurements may have some variance",
        )
        CalibrationMethod.LIDAR_DEPTH -> BadgeConfig(
            icon = Icons.Default.CheckCircle,
            label = "Scale set",
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            accessibleDescription = "Calibration status: scale set with high confidence",
        )
        CalibrationMethod.EXIF_FOCAL -> BadgeConfig(
            icon = Icons.Default.Info,
            label = "Scale uncertain",
            containerColor = MaterialTheme.colorScheme.errorContainer,
            accessibleDescription = "Calibration status: scale uncertain, measurements may be significantly inaccurate",
        )
        CalibrationMethod.MONOCULAR_ML -> BadgeConfig(
            icon = Icons.Default.Warning,
            label = "Scale uncertain",
            containerColor = MaterialTheme.colorScheme.errorContainer,
            accessibleDescription = "Calibration status: scale uncertain, measurements may be significantly inaccurate",
        )
        CalibrationMethod.NONE, null -> BadgeConfig(
            icon = Icons.Default.HorizontalRule,
            label = "Not calibrated",
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            accessibleDescription = "Calibration status: not calibrated, measurements will not be accurate",
        )
    }

    Surface(
        modifier = modifier.semantics { contentDescription = config.accessibleDescription },
        shape = MaterialTheme.shapes.small,
        color = config.containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = config.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = config.label,
                style = MaterialTheme.typography.labelSmall,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onAction),
                )
            }
        }
    }
}

/**
 * The visible entry to the Known distances sheet (UX-31): one tap target at least 48 dp tall showing the state-ladder
 * text with an icon, so state is never carried by colour alone. [onAction] defaults to [onClick] ("Add another").
 */
@Composable
fun CalibrationPill(
    state: PillState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    calibration: Calibration? = null,
    onAction: () -> Unit = onClick,
) {
    Surface(
        modifier = modifier
            .testTag("calibration_pill")
            .heightIn(min = PILL_MIN_HEIGHT)
            .clickable(role = Role.Button, onClickLabel = "Open known distances", onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = pillIcon(state.icon),
                contentDescription = null,
                modifier = Modifier.size(16.dp).testTag("pill_icon_${state.icon}"),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(state.text, style = MaterialTheme.typography.labelMedium)
                state.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            state.actionLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp).clickable(onClick = onAction),
                )
            }
            if (state.showConfidenceBadge && calibration != null) {
                Spacer(modifier = Modifier.width(8.dp))
                CalibrationConfidenceBadge(calibration)
            }
            if (state.actionLabel == null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private val PILL_MIN_HEIGHT = 48.dp

private fun pillIcon(icon: PillIcon): ImageVector = when (icon) {
    PillIcon.NONE -> Icons.Default.HorizontalRule
    PillIcon.CHECK -> Icons.Default.CheckCircle
    PillIcon.INFO -> Icons.Default.Info
    PillIcon.WARNING -> Icons.Default.Warning
}
