// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.platform.webMediaQueryMatches
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchA11yStrings

/** The browser's `prefers-reduced-motion: reduce` (UX-47, UX-113); read at call time so a changed setting is seen. */
internal fun prefersReducedMotion(): Boolean = webMediaQueryMatches(SketchA11yStrings.REDUCED_MOTION_QUERY)

@Composable
actual fun platformReducedMotion(): Boolean = prefersReducedMotion()
