// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.export.ShareProvider
import kotlinx.coroutines.await

@Composable
actual fun rememberShareProvider(): ShareProvider = remember { WasmJsShareProvider() }

class WasmJsShareProvider : ShareProvider {
    override suspend fun shareText(content: String, mimeType: String) {
        if (hasNavigatorShare()) {
            try { navigatorShareText(content).await() } catch (_: Throwable) { }
        }
    }

    override suspend fun shareHtml(html: String, plainFallback: String) {
        shareText(plainFallback, "text/plain")
    }

    override suspend fun saveToFile(
        content: String,
        suggestedName: String,
        extension: String,
    ): Either<DomainError, Boolean> {
        return try {
            triggerBlobDownload(content, "$suggestedName.$extension")
            true.right()
        } catch (e: Throwable) {
            DomainError.ExportError.ShareFailed(e.message ?: "download failed").left()
        }
    }
}

private fun hasNavigatorShare(): Boolean = js("typeof navigator.share === 'function'")
private fun navigatorShareText(text: String): kotlin.js.Promise<JsAny> = js("navigator.share({ text: text })")
private fun triggerBlobDownload(content: String, filename: String): Unit =
    js("""(function() {
        var blob = new Blob([content], { type: 'text/plain' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url; a.download = filename;
        document.body.appendChild(a); a.click(); document.body.removeChild(a);
        setTimeout(function() { URL.revokeObjectURL(url); }, 1000);
    })()""")
