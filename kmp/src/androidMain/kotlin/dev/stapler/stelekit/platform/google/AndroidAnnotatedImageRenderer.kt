// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.ui.annotate.AnnotationExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Decodes the annotation's source image, bakes the measurements in via [AnnotationExporter] and
 * encodes the JPEG for Drive export. The source is down-sampled so its longest side stays within
 * [MAX_EDGE_PX]: measurements are stored in normalized coordinates, so the baked image still
 * matches, and a full-resolution camera frame would risk an OutOfMemoryError on the heap.
 */
class AndroidAnnotatedImageRenderer(private val fileSystem: FileSystem) : AnnotatedImageRenderer {

    companion object {
        const val MAX_EDGE_PX = 4096
    }

    override suspend fun renderJpeg(
        imageAnnotation: ImageAnnotation,
        measurements: List<MeasurementAnnotation>,
    ): Either<DomainError, ByteArray> = withContext(PlatformDispatcher.IO) {
        try {
            val source = fileSystem.readFileBytes(imageAnnotation.filePath)
                ?: return@withContext failure("source image is unreadable")
            val bitmap = decodeBounded(source) ?: return@withContext failure("source image could not be decoded")
            AnnotationExporter.bakeAndEncode(bitmap.asImageBitmap(), measurements)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable: an OutOfMemoryError here must surface as a failed export, not kill the process.
            failure(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun failure(reason: String): Either<DomainError, ByteArray> =
        DomainError.ExportError.EncodingFailed("$ZERO_BYTE_IMAGE_MESSAGE ($reason)").left()
}
