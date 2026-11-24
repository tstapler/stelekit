// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.units.LengthFormat

object LengthUnitTags {
    const val INLINE_CHIP = "sketch.units.chip"
    const val FIELD_CHIP = "sketch.units.field-chip"
    const val CHIP_ROW = "sketch.chip.units"
    const val MENU_ROW = "sketch.units.menu"
}

private const val CHIP_RADIUS_DP = 16
private const val CHIP_PAD_DP = 10

/** A touch-sized chip showing the current length display ("ft-in", "in", "in.dec"); one tap switches to the next one. */
@Composable
fun LengthUnitChip(format: LengthFormat, onCycle: () -> Unit, tag: String, modifier: Modifier = Modifier) {
    Box(
        modifier.testTag(tag)
            .defaultMinSize(minWidth = ChromeBudget.TOOL_TARGET_DP.dp, minHeight = ChromeBudget.TOOL_TARGET_DP.dp)
            .clip(RoundedCornerShape(CHIP_RADIUS_DP.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onCycle)
            .semantics { contentDescription = format.chipDescription; role = Role.Button }
            .padding(horizontal = CHIP_PAD_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(format.chipLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}
