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
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.writeToURL
import platform.Foundation.NSTemporaryDirectory
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

@Composable
actual fun rememberShareProvider(): ShareProvider = remember { IosShareProvider() }

/**
 * iOS [ShareProvider] implementation.
 * - shareText: presents [UIActivityViewController] from the root view controller.
 * - shareHtml: presents [UIActivityViewController] with the plain fallback text.
 * - saveToFile: writes to a temp file and presents [UIActivityViewController] to share.
 *
 * All UIKit calls MUST run on Main: UIKit requires main thread.
 */
class IosShareProvider : ShareProvider {

    override suspend fun shareText(content: String, mimeType: String) {
        // MUST run on Main: UIKit requires main thread
        withContext(Dispatchers.Main) {
            val items = listOf(content as NSString)
            val activityVC = UIActivityViewController(
                activityItems = items,
                applicationActivities = null
            )
            val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
            rootVC?.presentViewController(activityVC, animated = true, completion = null)
        }
    }

    override suspend fun shareHtml(html: String, plainFallback: String) {
        // Share plain fallback — full HTML NSAttributedString is complex; use plainFallback for now
        shareText(plainFallback, "text/plain")
    }

    override suspend fun saveToFile(
        content: String,
        suggestedName: String,
        extension: String
    ): Either<DomainError, Boolean> {
        return try {
            val fileName = "${suggestedName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.$extension"
            val tempDir = NSTemporaryDirectory()
            val fileUrl = NSURL.fileURLWithPath("$tempDir$fileName")

            // Write content to temp file
            val nsString = content as NSString
            nsString.writeToURL(
                url = fileUrl,
                atomically = true,
                encoding = 4u, // NSUTF8StringEncoding
                error = null
            )

            // MUST run on Main: UIKit requires main thread
            withContext(Dispatchers.Main) {
                val items = listOf(fileUrl)
                val activityVC = UIActivityViewController(
                    activityItems = items,
                    applicationActivities = null
                )
                val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
                rootVC?.presentViewController(activityVC, animated = true, completion = null)
            }
            true.right()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DomainError.ExportError.ShareFailed(e.message ?: "Failed to save file on iOS").left()
        }
    }
}
