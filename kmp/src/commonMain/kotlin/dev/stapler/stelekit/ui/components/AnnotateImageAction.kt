// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import dev.stapler.stelekit.model.Block

/** Opens the sketch editor for an inline sketch embed, given its opaque slug; null (default) leaves embeds as plain images. */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell, next to LocalAnnotateImageAction
val LocalOpenSketchAction = staticCompositionLocalOf<((slug: String) -> Unit)?> { null }

/** Opens an inline image block in the annotation editor; null (default) hides "Annotate" from the image viewer. */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell; threading it through BlockList/BlockRenderer would touch every page screen
val LocalAnnotateImageAction = staticCompositionLocalOf<((imageRef: String, block: Block) -> Unit)?> { null }
