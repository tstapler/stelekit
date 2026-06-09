// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.export.ShareProvider

@Composable
actual fun rememberShareProvider(): ShareProvider = remember { WasmJsShareProvider() }

/** No-op [ShareProvider] for WASM/JS target. */
class WasmJsShareProvider : ShareProvider {
    override suspend fun shareText(content: String, mimeType: String) {
        // No-op: WASM/JS target
    }

    override suspend fun shareHtml(html: String, plainFallback: String) {
        // No-op: WASM/JS target
    }

    override suspend fun saveToFile(
        content: String,
        suggestedName: String,
        extension: String
    ): Either<DomainError, Boolean> = false.right()
}
