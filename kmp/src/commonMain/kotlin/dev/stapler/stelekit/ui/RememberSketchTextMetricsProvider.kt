// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.ui.sketch.v2.ComposeTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.v2.rememberSketchFontState

/**
 * Composition root of the sketch engine's text measurer (Task 2.1.2b): the one construction of
 * [ComposeTextMetricsProvider]. The measurer uses density 1 and font scale 1, so one sp is one scene unit and
 * measured widths do not depend on the display.
 */
@Composable
fun rememberSketchTextMetricsProvider(): TextMetricsProvider {
    val fonts = rememberSketchFontState()
    val resolver = LocalFontFamilyResolver.current
    return remember(resolver, fonts) {
        ComposeTextMetricsProvider(TextMeasurer(resolver, Density(1f, 1f), LayoutDirection.Ltr), fonts)
    }
}
