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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberShareProvider(): ShareProvider = remember { JvmShareProvider() }

/**
 * JVM (Desktop) [ShareProvider] implementation.
 * - shareText / shareHtml: no-ops (Desktop has no OS share sheet; the "Share via app" tile
 *   is hidden in the UI on desktop).
 * - saveToFile: shows a native AWT FileDialog on the EDT.
 */
class JvmShareProvider : ShareProvider {

    companion object {
        /** Matches any character that is not safe for use in a filename. */
        private val UNSAFE_FILENAME_CHARS = Regex("[^a-zA-Z0-9._-]")
    }

    override suspend fun shareText(content: String, mimeType: String) {
        // No-op: Desktop has no share sheet. The "Share via app" tile is hidden on desktop.
    }

    override suspend fun shareHtml(html: String, plainFallback: String) {
        // No-op: Desktop has no share sheet. The "Share via app" tile is hidden on desktop.
    }

    override suspend fun saveToFile(
        content: String,
        suggestedName: String,
        extension: String
    ): Either<DomainError, Boolean> {
        return try {
            val selectedFile: File? = withContext(Dispatchers.Main) {
                val dialog = FileDialog(null as Frame?, "Save file", FileDialog.SAVE).apply {
                    file = "${suggestedName.replace(UNSAFE_FILENAME_CHARS, "_")}.$extension"
                    isVisible = true
                }
                val dir = dialog.directory
                val filename = dialog.file
                if (dir != null && filename != null) File(dir, filename) else null
            }

            if (selectedFile == null) {
                // User cancelled
                return false.right()
            }

            withContext(Dispatchers.IO) {
                selectedFile.writeText(content)
            }
            true.right()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DomainError.ExportError.ShareFailed(e.message ?: "Failed to save file").left()
        }
    }
}
