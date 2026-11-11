// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import dev.stapler.stelekit.calibration.CalibrationService
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.units.Rational

data class PngSize(val widthPx: Int, val heightPx: Int)

/**
 * A named known dimension the user marked on a raster sheet: a reference line between two
 * normalized points whose real length is [length] (inches). A raster has no labels, so this is
 * the only source of dimensions.
 */
data class RasterReference(val key: String, val start: NormalizedPoint, val end: NormalizedPoint, val length: Rational)

/** Raster (PNG) sheets through the existing `Calibration` path (Story 4.5); native-pixel basis only. */
object PngSheetAdapter {
    const val SOURCE_KIND = "png"
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val IHDR_END = 24
    private const val WIDTH_AT = 16
    private const val HEIGHT_AT = 20

    /** Native size from the IHDR chunk, or null when [bytes] is not a PNG with positive dimensions. */
    fun readSize(bytes: ByteArray): PngSize? {
        if (bytes.size < IHDR_END) return null
        if (!SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }) return null
        if (bytes.decodeToString(12, 16) != "IHDR") return null
        val width = int32(bytes, WIDTH_AT)
        val height = int32(bytes, HEIGHT_AT)
        return if (width > 0 && height > 0) PngSize(width, height) else null
    }

    /** `computeFromReference` at [CalibrationBasis.NATIVE_PIXELS]; null when the line has no length. */
    fun calibrate(size: PngSize, reference: RasterReference): Calibration? {
        if (reference.length.signum <= 0) return null
        return CalibrationService.computeFromReference(
            pixelStart = reference.start,
            pixelEnd = reference.end,
            imageWidthPx = size.widthPx.toDouble(),
            imageHeightPx = size.heightPx.toDouble(),
            knownLengthMeters = CalibratedLength.inchesToMeters(reference.length),
            basisVersion = CalibrationBasis.NATIVE_PIXELS,
        )
    }

    /** Real length of [pixelDistance] drawn on a view [renderedWidthPx] wide, via `pixelsPerMeterAt`. */
    fun measuredInches(calibration: Calibration, size: PngSize, renderedWidthPx: Double, pixelDistance: Double): Rational? {
        val perMeter = CalibrationService.pixelsPerMeterAt(calibration, size.widthPx.toDouble(), renderedWidthPx)
        if (perMeter <= 0.0 || !perMeter.isFinite()) return null
        return CalibratedLength.metersToInches(pixelDistance / perMeter)
    }

    /** The reference as an observation; its value is what the user stated, not a re-derived number. */
    fun referenceDimension(projectUuid: ProjectUuid, uuid: String, reference: RasterReference): SketchDimension =
        SketchDimension(
            uuid = uuid,
            projectUuid = projectUuid,
            key = reference.key,
            kind = "length",
            value = reference.length,
            precision = null,
            sourceKind = SOURCE_KIND,
            sourceRef = "png:reference",
        )

    private fun int32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
