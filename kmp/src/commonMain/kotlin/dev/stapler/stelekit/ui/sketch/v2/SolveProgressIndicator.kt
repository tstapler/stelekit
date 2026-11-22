// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveState
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

object SolveProgressTags {
    const val INDICATOR = "sketch.solve.indicator"
    const val CANCEL = "sketch.solve.cancel"
}

/** What the solver runner puts on screen (Story 4.1.4, UX-35, UX-36), as plain data so the wording is tested without a screen. */
object SolveMessages {
    /** True only once a solve has run past `SceneLimits.slowSolveIndicatorMs`; a fast solve never flashes the indicator. */
    fun indicatorVisible(state: SolveState): Boolean = state.solving && state.slow

    /** The `CantSatisfy` toast of UX-35: no action of its own, because the ordinary Undo already restores the pose before the edit. */
    fun cantSatisfy(): SketchNotice = SketchNotice(
        key = SketchStrings.Solve.CANT_SATISFY_KEY,
        tier = BannerTier.Warning,
        text = SketchStrings.Solve.CANT_SATISFY_SUMMARY,
        shortTitle = SketchStrings.Solve.CANT_SATISFY_SHORT,
        details = SketchStrings.Solve.CANT_SATISFY_DETAILS,
    )

    /** The Cancel chip for the action chip row, which survives a collapsed status row (ux.md section 9). */
    fun cancelChip(onCancel: () -> Unit): ActionChip =
        ActionChip(SolveProgressTags.CANCEL, SketchStrings.Solve.CANCEL, SketchStrings.Solve.CANCEL_NAME, onClick = onCancel)
}

/**
 * The "Solving..." status with its Cancel control, shown near the status line while a solve is slow. Static text, never an
 * animation, so reduced motion needs no special case (UX-47). Cancel is a button at the touch floor.
 */
@Composable
fun SolveProgressIndicator(state: SolveState, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    if (!SolveMessages.indicatorVisible(state)) return
    Row(
        modifier.testTag(SolveProgressTags.INDICATOR),
        horizontalArrangement = Arrangement.spacedBy(INDICATOR_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            SketchStrings.Solve.SOLVING,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = SketchStrings.Solve.SOLVING },
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = onCancel,
            modifier = Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(SolveProgressTags.CANCEL).semantics {
                contentDescription = SketchStrings.Solve.CANCEL_NAME
            },
        ) { Text(SketchStrings.Solve.CANCEL) }
    }
}

private const val INDICATOR_GAP_DP = 8
