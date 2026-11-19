// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.Modifier
import dev.stapler.stelekit.platform.DroppedCanvasFile

actual fun Modifier.canvasFileDrop(onFile: (DroppedCanvasFile) -> Unit): Modifier = this
