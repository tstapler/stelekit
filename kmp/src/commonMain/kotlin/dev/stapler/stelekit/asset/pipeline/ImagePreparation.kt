package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.image.ImageDownscalerPort

/** Longest edge handed to the label model; it resamples to 512 px itself. */
const val LABELING_MAX_EDGE_PX: Int = 1024

/** Longest edge handed to OCR; small print needs more pixels than labeling does. */
const val OCR_MAX_EDGE_PX: Int = 1600

/** Largest undecoded image a plugin may pass to a model when no downscaled copy exists. */
const val MAX_UNSCALED_IMAGE_BYTES: Int = 4 * 1024 * 1024

/**
 * The bytes a model may see: a downscaled copy (the downscaler decodes within its pixel budget),
 * or the original only when it is small. A 40 MP photo reaches the model as a few hundred KB, and
 * a large original whose downscale failed is refused rather than decoded at full size.
 */
internal fun prepareForModel(
    original: ByteArray,
    maxEdgePx: Int,
    downscaler: ImageDownscalerPort,
): Either<DomainError, ByteArray> {
    // The port promises not to throw, but a platform decoder that does must not fail the asset.
    val scaled = try {
        downscaler.downscale(original, listOf(maxEdgePx))?.firstOrNull()
    } catch (_: Exception) {
        null
    }
    return when {
        scaled != null -> scaled.right()
        original.size <= MAX_UNSCALED_IMAGE_BYTES -> original.right()
        else -> DomainError.DatabaseError.ReadFailed("Image too large to process without a downscaled copy").left()
    }
}
