// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlinx.coroutines.launch

/** One chip of the action chip row: a touch or pen user's control for the active tool or operation (UX-126). */
data class ActionChip(val tag: String, val label: String, val accessibleName: String = label, val onClick: () -> Unit)

object ActionChipTags {
    const val CHEVRON = "sketch.chips.chevron"
}

/**
 * The action chip row (Story 3.1.4, UX-126): the modifier toggles, Line's [Done], the picker's [Cancel], the smart-dimension
 * chip and the "Tool lock" chip live here, a canvas overlay that no row collapse removes. Each chip is at the touch floor; the
 * row scrolls with a visible chevron while more chips exist beyond the edge. Empty when nothing is active.
 */
@Composable
fun ActionChipRow(chips: List<ActionChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Row(modifier.testTag(ShellTags.CHIP_ROW), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(CHIP_GAP_DP.dp)) {
            for (chip in chips) {
                Box(
                    Modifier.testTag(chip.tag)
                        .defaultMinSize(minWidth = CHIP_MIN_WIDTH_DP.dp, minHeight = ChromeBudget.TOOL_TARGET_DP.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = chip.onClick)
                        .semantics { contentDescription = chip.accessibleName; role = Role.Button }
                        .padding(horizontal = CHIP_PAD_DP.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(chip.label, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
        }
        if (scroll.canScrollForward) {
            Box(
                Modifier.size(ChromeBudget.TOOL_TARGET_DP.dp).testTag(ActionChipTags.CHEVRON)
                    .clickable { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }
                    .semantics { contentDescription = SketchStrings.Chrome.SCROLL_CHIPS; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { Text(CHEVRON_GLYPH) }
        }
    }
}

private const val CHIP_GAP_DP = 8
private const val CHIP_PAD_DP = 12
private const val CHIP_MIN_WIDTH_DP = 88
private const val CHEVRON_GLYPH = "›"
