// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.NotificationType
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.opfsWriteFileBytes
import dev.stapler.stelekit.platform.toJsUint8Array
import dev.stapler.stelekit.ui.NotificationManager
import dev.stapler.stelekit.ui.components.fileArrayBufferPromise
import dev.stapler.stelekit.ui.components.jsFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.withContext

/**
 * WASM/web implementation of [MediaAttachmentService]: [pickAndAttach] backs the toolbar
 * file-picker button, [attachBytes] backs drag-and-drop — both go through [persistAttachment].
 */
class WasmMediaAttachmentService(private val fileSystem: FileSystem) : MediaAttachmentService {

    private var notificationManager: NotificationManager? = null

    // Attached post-construction since StelekitApp creates its NotificationManager after
    // deps.platformIntegrations is built — mirrors CaptureController.attachNotificationManager
    // on Desktop.
    fun attachNotificationManager(nm: NotificationManager) {
        notificationManager = nm
    }

    override suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String,
    ): Either<DomainError, AttachmentResult>? {
        val fileObj = try {
            pickImageFileJs().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return null  // user cancelled or browser denied
        }
        val name = jsFileName(fileObj)
        val arrayBuffer = try {
            fileArrayBufferPromise(fileObj).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return DomainError.AttachmentError.CopyFailed(e.message ?: "read failed").left()
        }
        return persistAttachment(name, arrayBuffer, graphRoot)
    }

    override suspend fun attachBytes(
        bytes: ByteArray,
        suggestedName: String,
        graphRoot: String
    ): Either<DomainError, AttachmentResult> =
        persistAttachment(suggestedName, bytes.toJsUint8Array(), graphRoot)

    /**
     * Shared write-then-register-blob-URL sequence for both [pickAndAttach] and [attachBytes] —
     * a single source of truth so the two entry points can't drift on dedup, dispatcher, or
     * failure-toast behavior the way they did before this was extracted (a file-picker OPFS
     * failure went silent while an identical drag-drop failure toasted).
     */
    private suspend fun persistAttachment(
        suggestedName: String,
        arrayBuffer: JsAny,
        graphRoot: String,
    ): Either<DomainError, AttachmentResult> = withContext(PlatformDispatcher.IO) {
        try {
            val assetsPath = "$graphRoot/assets"
            fileSystem.createDirectory(assetsPath)

            val stem = if ('.' in suggestedName) suggestedName.substringBeforeLast('.') else suggestedName
            val ext = if ('.' in suggestedName) suggestedName.substringAfterLast('.') else ""
            val uniqueName = uniqueFileName(assetsPath, stem, ext, fileSystem)

            val destPath = "$assetsPath/$uniqueName"
            opfsWriteFileBytes(destPath, arrayBuffer)
            val blobUrl = createObjectUrlFromBuffer(arrayBuffer, mimeTypeForExt(ext))
            fileSystem.registerBlobUrl(destPath, blobUrl)
            AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = e.message ?: "OPFS write failed"
            notificationManager?.show("Image attachment failed: $message", NotificationType.ERROR)
            DomainError.AttachmentError.CopyFailed(message).left()
        }
    }
}

private fun createObjectUrlFromBuffer(buffer: JsAny, mimeType: String): String =
    js("URL.createObjectURL(new Blob([buffer], { type: mimeType }))")

private fun mimeTypeForExt(ext: String): String = when (ext.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "bmp" -> "image/bmp"
    "avif" -> "image/avif"
    else -> "application/octet-stream"
}

private fun pickImageFileJs(): kotlin.js.Promise<JsAny> = js("""(function() {
    return new Promise(function(resolve, reject) {
        var input = document.createElement('input');
        input.type = 'file'; input.accept = 'image/*';
        input.addEventListener('change', function() {
            if (input.files && input.files[0]) resolve(input.files[0]);
            else reject(new Error('no-file'));
        });
        input.addEventListener('cancel', function() { reject(new Error('cancelled')); });
        input.click();
    });
})()""")
