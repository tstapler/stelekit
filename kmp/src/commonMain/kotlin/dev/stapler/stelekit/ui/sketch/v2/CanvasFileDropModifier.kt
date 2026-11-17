// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.Modifier
import dev.stapler.stelekit.platform.DroppedCanvasFile

/**
 * Makes the canvas a drop target for one file (Story 5.1.5b). Desktop accepts an OS file drop through Compose's drag and
 * drop; the other targets return the receiver unchanged: Android and iOS have no file drag, and the web uses the canvas
 * element's own `CanvasFileDrop` handler (a `CanvasFileDropHost`), because the page-level drop target is a no-op there (BUG-004).
 */
expect fun Modifier.canvasFileDrop(onFile: (DroppedCanvasFile) -> Unit): Modifier
