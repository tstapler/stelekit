// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal const val ZOOM_IN_DESCRIPTION = "Zoom in"
internal const val ZOOM_OUT_DESCRIPTION = "Zoom out"
internal const val ZOOM_FIT_DESCRIPTION = "Fit image to screen"
private const val ZOOM_FLOOR_EPSILON = 1e-3f

/** The percentage of Fit, spoken as a value: "Zoom 40 percent". */
internal fun zoomPercentDescription(scale: Float): String = "Zoom ${(scale * 100f).roundToInt()} percent"

/**
 * Zoom out, percentage, zoom in and fit for users who cannot pinch (TalkBack, desktop). [view] is read
 * here, in its own composition scope, so a pinch recomposes only these buttons. Every target is 48 dp.
 * [vertical] is the rail beside the photo (windows >= 600 dp); otherwise a wrapping row for the toolbar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnnotationZoomControls(
    view: () -> AnnotationViewTransform,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val current = view()
    val zoomIn = @Composable {
        FilledTonalIconButton(
            onClick = onZoomIn,
            enabled = current.scale < MAX_VIEW_SCALE,
            modifier = Modifier.size(48.dp).semantics { contentDescription = ZOOM_IN_DESCRIPTION },
        ) { Text("+") }
    }
    val zoomOut = @Composable {
        FilledTonalIconButton(
            onClick = onZoomOut,
            enabled = current.scale > MIN_VIEW_SCALE + ZOOM_FLOOR_EPSILON,
            modifier = Modifier.size(48.dp).semantics { contentDescription = ZOOM_OUT_DESCRIPTION },
        ) { Text("−") }
    }
    val percent = @Composable {
        Text(
            text = "${(current.scale * 100f).roundToInt()}%",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = zoomPercentDescription(current.scale) },
        )
    }
    val fit = @Composable {
        FilledTonalIconButton(
            onClick = onFit,
            enabled = !current.isFit,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = ZOOM_FIT_DESCRIPTION },
        ) { Text("Fit") }
    }
    if (vertical) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            zoomIn(); percent(); zoomOut(); fit()
        }
    } else {
        FlowRow(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { zoomOut(); percent(); zoomIn(); fit() }
    }
}
