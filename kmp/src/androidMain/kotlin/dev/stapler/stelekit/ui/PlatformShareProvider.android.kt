// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.export.ShareProvider
import dev.stapler.stelekit.platform.SteleKitContext
import java.io.File

@Composable
actual fun rememberShareProvider(): ShareProvider = remember { AndroidShareProvider() }

/**
 * Android [ShareProvider] implementation.
 * - shareText / shareHtml: fire Intent.ACTION_SEND via the OS share sheet.
 * - saveToFile: write to a temp file in cacheDir and share via Intent.ACTION_SEND + FileProvider.
 *   This is simpler than SAF ACTION_CREATE_DOCUMENT which requires Activity result callbacks.
 */
class AndroidShareProvider : ShareProvider {

    override suspend fun shareText(content: String, mimeType: String) {
        val context = SteleKitContext.context
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_TEXT, content)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "Share via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    override suspend fun shareHtml(html: String, plainFallback: String) {
        val context = SteleKitContext.context
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_TEXT, plainFallback)
            putExtra(Intent.EXTRA_HTML_TEXT, html)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "Share via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    override suspend fun saveToFile(
        content: String,
        suggestedName: String,
        extension: String
    ): Either<DomainError, Boolean> {
        return try {
            val context = SteleKitContext.context
            val cacheDir = File(context.cacheDir, "share_export").also { it.mkdirs() }
            val fileName = "${suggestedName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.$extension"
            val file = File(cacheDir, fileName)
            file.writeText(content)

            val mimeType = when (extension) {
                "md" -> "text/markdown"
                "html" -> "text/html"
                "json" -> "application/json"
                else -> "text/plain"
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Save file via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true.right()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DomainError.ExportError.ShareFailed(e.message ?: "Failed to share file").left()
        }
    }
}
