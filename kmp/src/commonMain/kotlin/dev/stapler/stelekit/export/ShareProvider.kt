// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.export

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/**
 * Platform-agnostic interface for sharing content via native OS mechanisms.
 *
 * Platform implementations:
 * - androidMain: [AndroidShareProvider] — Intent.ACTION_SEND + SAF for file saves
 * - jvmMain: [JvmShareProvider] — AWT FileDialog for file saves; shareText/shareHtml are no-ops
 * - iosMain: [IosShareProvider] — UIActivityViewController + UIDocumentPickerViewController
 * - wasmJsMain: no-op stub
 */
interface ShareProvider {
    /**
     * Share plain text content via the OS share sheet.
     * No-op on Desktop (no share sheet).
     */
    suspend fun shareText(content: String, mimeType: String = "text/plain")

    /**
     * Share HTML content with a plain-text fallback via the OS share sheet.
     * No-op on Desktop (no share sheet).
     */
    suspend fun shareHtml(html: String, plainFallback: String)

    /**
     * Save content to a file, showing a native file-save dialog.
     *
     * @param content The text content to write.
     * @param suggestedName The suggested filename (without extension).
     * @param extension The file extension (e.g. "md", "html", "json").
     * @return Right(true) if saved successfully, Right(false) if user cancelled,
     *         Left(DomainError.ExportError.ShareFailed) on write error.
     */
    suspend fun saveToFile(
        content: String,
        suggestedName: String,
        extension: String
    ): Either<DomainError, Boolean>
}
