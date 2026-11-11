// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.image.inSampleSizeFor
import dev.stapler.stelekit.platform.sensor.retryOnOutOfMemory
import dev.stapler.stelekit.ui.annotate.AnnotationExporter
import dev.stapler.stelekit.ui.annotate.ExifOrientation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** Turns encoded image bytes into an upright, pixel-bounded bitmap, or null when they can't be decoded. */
fun interface SourceBitmapDecoder {
    fun decode(bytes: ByteArray): Bitmap?
}

/**
 * Decodes the annotation's source image, bakes the measurements in via [AnnotationExporter] and
 * encodes the JPEG for Drive export. The source is sub-sampled to the shared decode pixel budget and
 * rotated upright per its EXIF tag: measurements are stored in normalized coordinates of the
 * upright image, so the baked image still matches, and a full-resolution camera frame would risk an
 * OutOfMemoryError on the heap.
 */
class AndroidAnnotatedImageRenderer(
    private val fileSystem: FileSystem,
    private val decoder: SourceBitmapDecoder = BoundedUprightDecoder,
) : AnnotatedImageRenderer {

    override suspend fun renderJpeg(
        imageAnnotation: ImageAnnotation,
        measurements: List<MeasurementAnnotation>,
    ): Either<DomainError, ByteArray> = withContext(PlatformDispatcher.IO) {
        try {
            val source = fileSystem.readFileBytes(imageAnnotation.filePath)
                ?: return@withContext failure("source image is unreadable")
            val bitmap = decoder.decode(source) ?: return@withContext failure("source image could not be decoded")
            AnnotationExporter.bakeAndEncode(bitmap.asImageBitmap(), measurements)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable: an OutOfMemoryError here must surface as a failed export, not kill the process.
            failure(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun failure(reason: String): Either<DomainError, ByteArray> =
        DomainError.ExportError.EncodingFailed("$ZERO_BYTE_IMAGE_MESSAGE ($reason)").left()
}

/** Default decoder: pixel-budgeted [inSampleSizeFor] decode (halving on OOM), then EXIF rotation. */
internal object BoundedUprightDecoder : SourceBitmapDecoder {
    override fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val orientation = readOrientation(bytes)
        return retryOnOutOfMemory(inSampleSizeFor(bounds.outWidth, bounds.outHeight)) { sample ->
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (decoded == null || orientation == ExifOrientation.UPRIGHT) decoded else rotate(decoded, orientation)
        }
    }

    private fun readOrientation(bytes: ByteArray): ExifOrientation = try {
        val tag = ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ExifOrientation.fromTag(tag)
    } catch (e: java.io.IOException) {
        ExifOrientation.UPRIGHT
    }

    private fun rotate(source: Bitmap, orientation: ExifOrientation): Bitmap {
        val matrix = Matrix().apply {
            if (orientation.flipped) postScale(-1f, 1f)
            postRotate(orientation.rotationDegrees.toFloat())
        }
        val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (upright !== source) source.recycle()
        return upright
    }
}
