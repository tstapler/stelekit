// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/**
 * Result of a successful file attachment operation.
 *
 * @param relativePath Logseq-compatible relative path to insert into block markdown,
 *   always of the form `../assets/<filename>`.
 * @param displayName Human-readable filename for use as the image alt text.
 */
data class AttachmentResult(
    val relativePath: String,
    val displayName: String
)

/**
 * Platform-agnostic service for picking a media file and copying it into the graph's
 * `assets/` directory.
 *
 * All implementations must:
 * - Run the file copy on [dev.stapler.stelekit.coroutines.PlatformDispatcher.IO].
 * - Create `<graphRoot>/assets/` if it does not exist.
 * - Handle duplicate filenames via suffix counter (photo.jpg → photo-1.jpg → photo-2.jpg).
 * - Write to a temp file first, then atomically rename, to prevent Coil reading partial writes.
 * - Return [arrow.core.Either.Left] with [DomainError] on any failure; never throw.
 */
interface MediaAttachmentService {
    /**
     * Opens the platform file picker (gallery on mobile, file chooser on desktop),
     * copies the selected file to `<graphRoot>/assets/<uniqueName>`, and returns
     * the relative markdown path.
     *
     * Returns [Either.Right] with [AttachmentResult] on success.
     * Returns [Either.Left] with [DomainError.AttachmentError] on failure.
     * Returns `null` if the user cancels without selecting a file (no error, no result).
     */
    suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String
    ): Either<DomainError, AttachmentResult>?

    /**
     * Copies an already-selected file (identified by its absolute path string) into the
     * graph's `assets/` directory without showing a file picker.
     *
     * Used for drag-and-drop scenarios where the file is already known.
     * The [filePath] must be an absolute path readable by the platform.
     *
     * Returns [Either.Right] with [AttachmentResult] on success.
     * Returns [Either.Left] with [DomainError.AttachmentError] on failure.
     * Returns `null` if the platform does not support this operation.
     *
     * Default implementation returns `null` (no-op) so existing implementations need not
     * override unless they support drag-and-drop.
     */
    suspend fun attachFilePath(
        filePath: String,
        graphRoot: String
    ): Either<DomainError, AttachmentResult>? = null
}
