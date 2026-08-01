package dev.stapler.stelekit.platform.sensor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream

/**
 * Corrects EXIF orientation tags in JPEG files captured on Android.
 *
 * Samsung and many other Android OEMs write JPEG data in sensor-native orientation
 * and set the EXIF `Orientation` tag to describe the rotation needed for correct display.
 * Many downstream consumers (including SteleKit's annotation canvas) do not honour EXIF
 * tags — this fixer bakes the rotation into the pixel data and resets the tag to NORMAL.
 *
 * Also extracts calibration-relevant EXIF fields ([focalLengthMm], [focalLength35mmEq],
 * [cameraMake], [cameraModel]) for use in [dev.stapler.stelekit.model.ImageSensorData].
 *
 * Requires `androidx.exifinterface:exifinterface` on the classpath (already present via
 * the `androidx.appcompat:appcompat` transitive dependency on API 21+ targets, but
 * explicitly declared for clarity).
 */
object ExifOrientationFixer {

    /**
     * Result of [fixOrientation].
     *
     * [outputPath] is the file path of the corrected JPEG (may equal [inputPath] when
     * overwriting in-place). [sensorData] contains EXIF-extracted camera metadata.
     */
    data class FixResult(
        val outputPath: String,
        val focalLengthMm: Double?,
        val focalLength35mmEq: Double?,
        val cameraMake: String?,
        val cameraModel: String?,
    )

    /**
     * Read the EXIF orientation from [inputPath], rotate the decoded [Bitmap] if needed,
     * and write the corrected JPEG to [outputPath].
     *
     * If [outputPath] is null, the corrected image is written to [inputPath] in-place.
     *
     * Returns [Either.Right] with [FixResult] on success.
     * Returns [Either.Left] with a [DomainError.SensorError.CaptureFailed] on I/O or
     * decoding failure.
     */
    fun fixOrientation(
        inputPath: String,
        outputPath: String? = null,
        jpegQuality: Int = 95,
    ): Either<DomainError.SensorError, FixResult> {
        return try {
            val exif = ExifInterface(inputPath)

            // Extract calibration-relevant EXIF fields
            val focalLengthMm = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                ?.let { parseRational(it) }
            val focalLength35mmEq = exif.getAttributeInt(
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0
            ).takeIf { it > 0 }?.toDouble()
            val cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE)?.takeIf { it.isNotBlank() }
            val cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)?.takeIf { it.isNotBlank() }

            val orientationValue = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = buildRotationMatrix(orientationValue)
            val destination = outputPath ?: inputPath

            if (matrix == null) {
                // No rotation needed — just copy if paths differ, then return result.
                if (outputPath != null && outputPath != inputPath) {
                    File(inputPath).copyTo(File(outputPath), overwrite = true)
                }
            } else {
                // Decode, rotate, re-encode
                val original = BitmapFactory.decodeFile(inputPath)
                    ?: return DomainError.SensorError.CaptureFailed(
                        "BitmapFactory.decodeFile returned null for $inputPath"
                    ).left()
                val rotated = Bitmap.createBitmap(
                    original, 0, 0, original.width, original.height, matrix, true
                )
                original.recycle()

                FileOutputStream(destination).use { out ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                }
                rotated.recycle()

                // Reset orientation tag to NORMAL in the output file
                val outExif = ExifInterface(destination)
                outExif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString()
                )
                outExif.saveAttributes()
            }

            FixResult(
                outputPath = destination,
                focalLengthMm = focalLengthMm,
                focalLength35mmEq = focalLength35mmEq,
                cameraMake = cameraMake,
                cameraModel = cameraModel,
            ).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception — an OutOfMemoryError decoding a large bitmap must
            // surface as a capture failure, not silently kill the process (CLAUDE.md:
            // uncaught Throwables in a coroutine kill the Android process).
            DomainError.SensorError.CaptureFailed(
                "ExifOrientationFixer failed for $inputPath: ${e.message ?: "unknown"}"
            ).left()
        }
    }

    /**
     * Build a [Matrix] that applies the rotation/flip described by [orientationValue].
     *
     * Returns `null` when no transformation is needed (ORIENTATION_NORMAL or unknown).
     */
    private fun buildRotationMatrix(orientationValue: Int): Matrix? {
        val matrix = Matrix()
        var needsTransform = true
        when (orientationValue) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> needsTransform = false
        }
        return if (needsTransform) matrix else null
    }

    /**
     * Parse a rational EXIF string like "3670/1000" or "3.67" to a [Double].
     * Returns null on failure.
     */
    private fun parseRational(value: String): Double? {
        return try {
            if (value.contains('/')) {
                val parts = value.split('/')
                if (parts.size == 2) {
                    val num = parts[0].trim().toDoubleOrNull() ?: return null
                    val den = parts[1].trim().toDoubleOrNull() ?: return null
                    if (den == 0.0) null else num / den
                } else null
            } else {
                value.toDoubleOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }
}
