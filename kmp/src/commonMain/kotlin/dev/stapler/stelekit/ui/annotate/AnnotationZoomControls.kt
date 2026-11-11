// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal const val ZOOM_IN_DESCRIPTION = "Zoom in"
internal const val ZOOM_OUT_DESCRIPTION = "Zoom out"
internal const val ZOOM_FIT_DESCRIPTION = "Fit image to screen"

/**
 * Zoom in, zoom out and fit buttons for users who cannot pinch (TalkBack, desktop). [view] is read
 * here, in its own composition scope, so a pinch recomposes only these three buttons.
 */
@Composable
internal fun AnnotationZoomControls(
    view: () -> AnnotationViewTransform,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = view()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(
            onClick = onZoomIn,
            enabled = current.scale < MAX_VIEW_SCALE,
            modifier = Modifier.semantics { contentDescription = ZOOM_IN_DESCRIPTION },
        ) { Text("+") }
        FilledTonalIconButton(
            onClick = onZoomOut,
            enabled = !current.isFit,
            modifier = Modifier.semantics { contentDescription = ZOOM_OUT_DESCRIPTION },
        ) { Text("−") }
        FilledTonalIconButton(
            onClick = onFit,
            enabled = !current.isFit,
            modifier = Modifier.semantics { contentDescription = ZOOM_FIT_DESCRIPTION },
        ) { Text("Fit") }
    }
}
