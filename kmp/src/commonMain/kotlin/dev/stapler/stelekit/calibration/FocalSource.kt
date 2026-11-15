// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import kotlinx.serialization.Serializable

/** Where a photo's focal length came from, ranked by how far it can be trusted (ADR-003 decision 5). */
@Serializable
enum class FocalSource {
    /** A 35 mm-equivalent value the user typed in the height-offset focal field. */
    USER_CONFIRMED,

    /** A matched camera profile; it compares EXIF with EXIF, so it adds no independent evidence. */
    PROFILE,

    /** The sidecar lens record at zoom ratio 1. */
    SIDECAR_LENS_RECORD,

    /** Only the EXIF value, or none. */
    EXIF_ONLY,
    ;

    /** Only independent evidence may enable the exact `1/cos(tau)` refinement; EXIF-derived sources never do. */
    val isTrusted: Boolean get() = this == USER_CONFIRMED || this == SIDECAR_LENS_RECORD
}

/** The part of a sidecar lens record the resolver needs; E4's capture record maps onto it. */
data class LensRecordFocal(val focal35mmEq: Double?, val zoomRatio: Double)

/** The winning source and the 35 mm-equivalent focal length it supplies (null for [FocalSource.EXIF_ONLY] without EXIF). */
data class FocalResolution(val source: FocalSource, val focal35mmEq: Double?)

object FocalSourceResolver {
    private const val ZOOM_TOLERANCE = 1e-6

    private fun Double?.valid(): Double? = this?.takeIf { it.isFinite() && it > 0.0 }

    /**
     * Ranks the evidence: a user-confirmed value, then a lens record at zoom 1, then a matched profile, then EXIF alone.
     * A lens record at zoom != 1 describes a cropped frame, so it contributes nothing.
     */
    fun resolve(
        userConfirmed35mmEq: Double?,
        lensRecord: LensRecordFocal?,
        profileMatched: Boolean,
        exifFocal35mmEq: Double?,
    ): FocalResolution {
        userConfirmed35mmEq.valid()?.let { return FocalResolution(FocalSource.USER_CONFIRMED, it) }
        val record = lensRecord?.takeIf { kotlin.math.abs(it.zoomRatio - 1.0) <= ZOOM_TOLERANCE }
        record?.focal35mmEq.valid()?.let { return FocalResolution(FocalSource.SIDECAR_LENS_RECORD, it) }
        if (profileMatched) return FocalResolution(FocalSource.PROFILE, exifFocal35mmEq.valid())
        return FocalResolution(FocalSource.EXIF_ONLY, exifFocal35mmEq.valid())
    }

    /**
     * Focal length in pixels for the optional refinement, or null (no refinement) unless [refinementEnabled] and the
     * source is trusted. Delegates to [HeightOffset.focalPixels], which assumes an uncropped frame.
     */
    fun focalPixelsOrNull(resolution: FocalResolution, refinementEnabled: Boolean, widthPx: Double, heightPx: Double): Double? {
        if (!refinementEnabled || !resolution.source.isTrusted) return null
        return HeightOffset.focalPixels(resolution.focal35mmEq, widthPx, heightPx)
    }
}
