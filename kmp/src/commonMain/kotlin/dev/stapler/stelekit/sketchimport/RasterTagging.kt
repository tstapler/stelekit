// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.units.parseLength
import dev.stapler.stelekit.units.roundToInches
import kotlin.math.hypot

sealed interface RasterInputError {
    /** The typed text is empty, not a single length, or not positive. */
    data object NotALength : RasterInputError

    /** The two points are closer than [RasterTagging.MIN_SEPARATION_PX] native pixels. */
    data object PointsTooClose : RasterInputError

    data object NotCalibrated : RasterInputError

    /** The key is not a registered length dimension. */
    data class NotALengthKey(val key: String) : RasterInputError
}

/**
 * A dimension the user tagged on a raster sheet: [length] is exact inches. [measured] is false for the
 * calibration reference itself (the value the user typed) and true for a length read off the image (rounded to 1/16").
 */
data class RasterTag(val key: String, val start: NormalizedPoint, val end: NormalizedPoint, val length: Rational, val measured: Boolean)

/** Two points the user placed on the image. */
data class RasterLine(val start: NormalizedPoint, val end: NormalizedPoint)

/** Pure rules of the PNG calibration step (Story 4.5b): no UI, no I/O. The only Double-to-Rational step is [CalibratedLength]. */
object RasterTagging {
    /** Two points nearer than this give a meaningless scale, so the step refuses them. */
    const val MIN_SEPARATION_PX = 4.0

    /** Half of the 1/16" a measured length is rounded to. */
    val MEASURED_PRECISION: Rational = Rational(1, 2 * CalibratedLength.DENOMINATOR)

    /** A single positive length from user text such as `5 3/8"` or `12'-6"`. */
    fun parseTypedLength(text: String): Either<RasterInputError, Rational> {
        val measured = parseLength(text).getOrNull() as? ParsedLength.Measured ?: return RasterInputError.NotALength.left()
        return if (measured.value.signum > 0) measured.value.right() else RasterInputError.NotALength.left()
    }

    /** Pixel distance between two normalized points on an image of [size]. */
    fun pixelDistance(size: PngSize, start: NormalizedPoint, end: NormalizedPoint): Double =
        hypot((end.x - start.x) * size.widthPx, (end.y - start.y) * size.heightPx)

    /** The native-pixel calibration from two points and the length typed for them. */
    fun calibrate(size: PngSize, start: NormalizedPoint, end: NormalizedPoint, typed: String): Either<RasterInputError, Calibration> {
        val length = parseTypedLength(typed).fold({ return it.left() }, { it })
        if (pixelDistance(size, start, end) < MIN_SEPARATION_PX) return RasterInputError.PointsTooClose.left()
        return PngSheetAdapter.calibrate(size, RasterReference("reference", start, end, length))?.right()
            ?: RasterInputError.PointsTooClose.left()
    }

    /** Real length between two points, rounded to 1/16"; null when the points are too close or the scale is unusable. */
    fun measure(calibration: Calibration, size: PngSize, start: NormalizedPoint, end: NormalizedPoint): Rational? {
        val distance = pixelDistance(size, start, end)
        if (distance < MIN_SEPARATION_PX) return null
        return PngSheetAdapter.measuredInches(calibration, size, size.widthPx.toDouble(), distance)
    }

    /** Canonical text of [length] in feet and inches; a length that is not whole sixteenths is shown rounded to the nearest. */
    fun format(length: Rational): String {
        val inches = length.roundToInches().getOrNull()
        return inches?.let { LengthFormatter.format(it, LengthFormat.FEET_INCHES) } ?: length.toString()
    }

    /** The measured tag for [key] between two points; the key must be a registered length. */
    fun tag(
        registry: DimensionRegistry,
        calibration: Calibration?,
        size: PngSize,
        key: String,
        line: RasterLine,
    ): Either<RasterInputError, RasterTag> {
        if (registry.entry(key)?.kind != DimensionKind.LENGTH) return RasterInputError.NotALengthKey(key).left()
        val scale = calibration ?: return RasterInputError.NotCalibrated.left()
        val length = measure(scale, size, line.start, line.end) ?: return RasterInputError.PointsTooClose.left()
        return RasterTag(key, line.start, line.end, length, measured = true).right()
    }

    /** [tags] with [tag] added; an earlier tag for the same key is replaced, so each key has one reading per sheet. */
    fun upsert(tags: List<RasterTag>, tag: RasterTag): List<RasterTag> = tags.filterNot { it.key == tag.key } + tag
}

/** What the user made on a PNG sheet: its native-pixel [calibration] and the [tags] (the typed reference, when bound, and measured lengths). */
data class RasterSession(val calibration: Calibration, val tags: List<RasterTag>) {
    /** The tags whose key is a registered length. */
    fun tagsIn(registry: DimensionRegistry): List<RasterTag> = tags.filter { registry.entry(it.key)?.kind == DimensionKind.LENGTH }

    /** The synced record for the sheet with [contentHash]; the scale is kept as the decimal text of the calibrated Double. */
    fun record(contentHash: String, kept: List<RasterTag> = tags): PngCalibrationRecord = PngCalibrationRecord(
        contentHash = contentHash,
        basisVersion = calibration.basisVersion,
        pixelsPerMeter = calibration.pixelsPerMeter.toString(),
        tags = kept.map { PngTagValue(it.key, it.length, it.measured) },
    )
}
