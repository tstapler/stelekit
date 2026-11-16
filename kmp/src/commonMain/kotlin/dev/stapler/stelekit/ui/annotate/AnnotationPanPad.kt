// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal const val PAN_AT_EDGE_DESCRIPTION = "At image edge"
internal const val PAN_NOTHING_TO_PAN_DESCRIPTION = "Nothing to pan"

/** Fraction of the viewport one pan-arrow press moves the view by. */
internal const val PAN_STEP_FRACTION = 0.25f

/** The four non-drag pan moves (WCAG 2.5.7). Directions are where the view window goes, so the image moves the other way. */
internal enum class PanDirection(val label: String, val arrow: String) {
    LEFT("Pan left", "←"),
    UP("Pan up", "↑"),
    DOWN("Pan down", "↓"),
    RIGHT("Pan right", "→"),
}

/** Screen-pixel image movement for one press of [direction]: 25 percent of the viewport on the pressed axis. */
internal fun panDelta(direction: PanDirection, viewportWidth: Float, viewportHeight: Float): Offset = when (direction) {
    PanDirection.LEFT -> Offset(PAN_STEP_FRACTION * viewportWidth, 0f)
    PanDirection.RIGHT -> Offset(-PAN_STEP_FRACTION * viewportWidth, 0f)
    PanDirection.UP -> Offset(0f, PAN_STEP_FRACTION * viewportHeight)
    PanDirection.DOWN -> Offset(0f, -PAN_STEP_FRACTION * viewportHeight)
}

/** ADR-006 decision 2: the Pan toggle and pad exist exactly when the view is at or above Fit. */
internal val AnnotationViewTransform.canPan: Boolean get() = isFitOrGreater(scale)

/**
 * Four 48 dp arrows for users who cannot drag. [atEdge] is read inside each button so a pan recomposes
 * only the pad. The pad is physically oriented (left is left) in RTL too, matching the unmirrored photo.
 */
@Composable
internal fun AnnotationPanPad(
    atEdge: (PanDirection) -> Boolean,
    onPan: (PanDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PanDirection.entries.forEach { direction -> PanArrow(direction, atEdge, onPan) }
        }
    }
}

@Composable
private fun PanArrow(direction: PanDirection, atEdge: (PanDirection) -> Boolean, onPan: (PanDirection) -> Unit) {
    val blocked = atEdge(direction)
    FilledTonalIconButton(
        onClick = { onPan(direction) },
        enabled = !blocked,
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = direction.label
            if (blocked) stateDescription = PAN_AT_EDGE_DESCRIPTION
        },
    ) { Text(direction.arrow) }
}
