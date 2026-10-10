// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

import androidx.compose.runtime.Composable

interface PlatformFileOpener {
    suspend fun openFile(absolutePath: String, mimeType: String)
}

/**
 * Returns a platform-specific [PlatformFileOpener] for opening a file in the host OS's
 * registered handler for the file's MIME type.
 * On Android: Intent.ACTION_VIEW via ContentProvider URI (SAF) or FileProvider copy-to-cache.
 * On JVM (Desktop): java.awt.Desktop.open().
 * On WASM/JS: no-op.
 */
@Composable
expect fun rememberPlatformFileOpener(): PlatformFileOpener
