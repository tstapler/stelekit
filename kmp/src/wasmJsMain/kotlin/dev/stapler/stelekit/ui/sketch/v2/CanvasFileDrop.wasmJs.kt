// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.Modifier
import dev.stapler.stelekit.platform.DroppedCanvasFile

/** The web drop is the canvas element's own [dev.stapler.stelekit.platform.CanvasFileDrop], not a Compose modifier. */
actual fun Modifier.canvasFileDrop(onFile: (DroppedCanvasFile) -> Unit): Modifier = this
