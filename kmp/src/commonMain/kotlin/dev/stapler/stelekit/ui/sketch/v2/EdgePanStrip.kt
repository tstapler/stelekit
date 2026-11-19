// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.EdgeGestureExclusion

object EdgePanTags {
    const val LAYER = "sketch.edgepan"
    fun band(edge: StripEdge) = "sketch.edgepan.band.${edge.name.lowercase()}"
}

/** The band's colour: the scheme's `outline`, which Material keeps at 3:1 against the surface; the test checks light, dark and high contrast. */
object EdgePanBand {
    fun color(scheme: ColorScheme): Color = scheme.outline
}

/**
 * The edge-pan strips (Task 3.1.2g, UX-121): the visible band, the strip's only drawn part, and the system gesture exclusion
 * for the protected span. With no strip nothing is composed, so an Off strip is absent from the semantics tree and from hit
 * testing. The band uses the theme's `outline`, which Material keeps at 3:1 against the surface in light and dark; a
 * high-contrast scheme supplies its own `outline`. The pointer adapter does the hit routing (`SketchPointerAdapter.edgeStrips`).
 */
@Composable
fun EdgePanStrip(strips: List<EdgeStrip>, viewWidthDp: Double, exclusion: EdgeGestureExclusion?, modifier: Modifier = Modifier) {
    if (strips.isEmpty()) return
    val pxPerDp = LocalDensity.current.density.toDouble()
    DisposableEffect(strips, viewWidthDp, pxPerDp, exclusion) {
        exclusion?.request(EdgePanGeometry.exclusionRects(strips, viewWidthDp, pxPerDp))
        onDispose { exclusion?.release() }
    }
    Box(modifier.fillMaxSize().testTag(EdgePanTags.LAYER)) {
        for (strip in strips) {
            Box(
                Modifier.offset(strip.band.left.dp, strip.band.top.dp)
                    .size(strip.band.width.dp, strip.band.height.dp)
                    .background(EdgePanBand.color(MaterialTheme.colorScheme))
                    .testTag(EdgePanTags.band(strip.edge)),
            )
        }
    }
}
