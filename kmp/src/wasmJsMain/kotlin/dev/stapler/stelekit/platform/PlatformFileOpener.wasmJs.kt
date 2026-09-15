// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPlatformFileOpener(): PlatformFileOpener = remember { WasmJsPlatformFileOpener() }

private class WasmJsPlatformFileOpener : PlatformFileOpener {
    override suspend fun openFile(absolutePath: String, mimeType: String) {
        // No-op: WASM/JS target has no file:// access; required for compilation
    }
}
