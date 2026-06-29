// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.opfsWriteFileBytes
import kotlinx.coroutines.await

class WasmMediaAttachmentService(private val fileSystem: FileSystem) : MediaAttachmentService {

    override suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String,
    ): Either<DomainError, AttachmentResult>? {
        val fileObj = try {
            pickImageFileJs().await()
        } catch (e: Throwable) {
            return null  // user cancelled or browser denied
        }
        val name = jsFileName(fileObj)
        val arrayBuffer = try {
            fileArrayBuffer(fileObj).await()
        } catch (e: Throwable) {
            return DomainError.AttachmentError.CopyFailed(e.message ?: "read failed").left()
        }

        val assetsPath = "$graphRoot/assets"
        fileSystem.createDirectory(assetsPath)

        val stem = if ('.' in name) name.substringBeforeLast('.') else name
        val ext = if ('.' in name) name.substringAfterLast('.') else ""
        val uniqueName = uniqueFileName(assetsPath, stem, ext, fileSystem)

        val destPath = "$assetsPath/$uniqueName"
        return try {
            opfsWriteFileBytes(destPath, arrayBuffer)
            val blobUrl = createObjectUrlFromBuffer(arrayBuffer, mimeTypeForExt(ext))
            fileSystem.registerBlobUrl(destPath, blobUrl)
            AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
        } catch (e: Throwable) {
            DomainError.AttachmentError.CopyFailed(e.message ?: "OPFS write failed").left()
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

private fun jsFileName(file: JsAny): String = js("file.name")
private fun fileArrayBuffer(file: JsAny): kotlin.js.Promise<JsAny> = js("file.arrayBuffer()")
