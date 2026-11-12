// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import dev.stapler.stelekit.model.Block

/** Opens an inline image block in the annotation editor; null (default) hides "Annotate" from the image viewer. */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell; threading it through BlockList/BlockRenderer would touch every page screen
val LocalAnnotateImageAction = staticCompositionLocalOf<((imageRef: String, block: Block) -> Unit)?> { null }
