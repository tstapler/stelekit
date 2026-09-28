// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.NotificationType
import dev.stapler.stelekit.platform.opfsWriteFileBytes
import dev.stapler.stelekit.platform.uniqueOpfsFileName
import dev.stapler.stelekit.ui.NotificationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * WASM/web implementation of [MediaAttachmentService].
 *
 * Only [attachBytes] is supported today — there is no toolbar file picker or clipboard-paste
 * wiring on web yet (see project_plans/wasm-image-drop's "Explicitly out of scope").
 */
class WasmMediaAttachmentService(
    private val notificationManager: NotificationManager,
) : MediaAttachmentService {

    override suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String
    ): Either<DomainError, AttachmentResult>? = null

    override suspend fun attachBytes(
        bytes: ByteArray,
        suggestedName: String,
        graphRoot: String
    ): Either<DomainError, AttachmentResult> = withContext(PlatformDispatcher.IO) {
        try {
            val assetsDirPath = "$graphRoot/assets"
            val stem = suggestedName.substringBeforeLast('.', suggestedName)
            val ext = suggestedName.substringAfterLast('.', "")
            val uniqueName = uniqueOpfsFileName(assetsDirPath, stem, ext)
            opfsWriteFileBytes("$assetsDirPath/$uniqueName", bytes)
            AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = e.message ?: "OPFS write failed"
            notificationManager.show("Image attachment failed: $message", NotificationType.ERROR)
            DomainError.AttachmentError.CopyFailed(message).left()
        }
    }
}
