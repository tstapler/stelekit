// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/** Where the action chip row goes: beside the view cluster when its whole natural width fits there, else on its own row above. */
enum class ChipRowPlacement { Beside, Raised }

object BottomOverlayPolicy {
    /** [Beside] only when every chip fits between the start inset and the end stack, so the two never share pixels. */
    fun placement(chipsNaturalPx: Int, besideRoomPx: Int): ChipRowPlacement =
        if (chipsNaturalPx <= besideRoomPx) ChipRowPlacement.Beside else ChipRowPlacement.Raised
}

/**
 * The bottom of the canvas: the action chip row at the start and the end stack (pan pad over the view cluster) at the end.
 * Both are measured, so the chip row sits beside the cluster only when all of it fits there and otherwise moves onto the
 * row above the whole end stack; a fixed canvas-width threshold let the chips run under the translucent cluster on a phone
 * a little wider than the threshold or at a large font. [onClearance] reports how far up from the canvas bottom the chip row
 * and the end stack reach, in px, so the toast lane and the help popup sit above both.
 */
@Composable
internal fun BottomOverlayLayout(
    chips: @Composable () -> Unit,
    endStack: @Composable () -> Unit,
    startPad: Dp,
    endPad: Dp,
    chipsEndPad: Dp,
    bottomPad: Dp,
    gap: Dp,
    onClearance: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(chips, endStack), modifier = modifier) { (chipMeasurables, endMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val start = startPad.roundToPx()
        val bottom = bottomPad.roundToPx()
        val space = gap.roundToPx()
        val ends = endMeasurables.map { it.measure(loose.copy(maxWidth = (width - endPad.roundToPx()).coerceAtLeast(0))) }
        val endWidth = ends.maxOfOrNull { it.width } ?: 0
        val endHeight = ends.sumOf { it.height }
        val besideRoom = (width - start - endPad.roundToPx() - endWidth - space).coerceAtLeast(0)
        val natural = chipMeasurables.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val placement = BottomOverlayPolicy.placement(natural, besideRoom)
        val chipMax = if (placement == ChipRowPlacement.Beside) besideRoom else (width - start - chipsEndPad.roundToPx()).coerceAtLeast(0)
        val chipPlaceables = chipMeasurables.map { it.measure(loose.copy(maxWidth = chipMax)) }
        val chipHeight = chipPlaceables.maxOfOrNull { it.height } ?: 0
        val chipsBottom = if (placement == ChipRowPlacement.Beside || endHeight == 0) bottom else bottom + endHeight + space
        layout(width, height) {
            var y = height - bottom
            for (p in ends.asReversed()) {
                y -= p.height
                p.placeRelative(width - endPad.roundToPx() - p.width, y)
            }
            for (p in chipPlaceables) p.placeRelative(start, height - chipsBottom - chipHeight)
            onClearance(maxOf(chipsBottom + chipHeight, bottom + endHeight))
        }
    }
}
