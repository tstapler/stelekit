// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.export.ShareProvider

/**
 * Returns a platform-specific [ShareProvider].
 * On Android: Intent.ACTION_SEND + SAF for file saves.
 * On JVM (Desktop): AWT FileDialog for file saves; share operations are no-ops.
 * On iOS: UIActivityViewController + UIDocumentPickerViewController.
 * On WASM/JS: no-ops.
 */
@Composable
expect fun rememberShareProvider(): ShareProvider
