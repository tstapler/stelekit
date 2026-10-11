// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.Modifier

/**
 * Applies a drag-and-drop drop target to the composable.
 *
 * On JVM/Desktop, this accepts file-list drops and filters to image extensions,
 * then invokes [onFilesDropped] with the dropped [java.io.File] instances (typed as [Any]
 * to keep the common API platform-agnostic).
 *
 * On WASM, this captures OS file drops via a `document.body`-level JS listener (bubbling covers
 * the Compose canvas), filters to the same image extensions as JVM, and invokes [onFilesDropped]
 * with [dev.stapler.stelekit.service.DroppedFileBytes] instances (typed as [Any]).
 *
 * On Android and iOS, this is a no-op and returns the receiver unchanged.
 */
expect fun Modifier.pageDropTarget(onFilesDropped: (List<Any>) -> Unit): Modifier
