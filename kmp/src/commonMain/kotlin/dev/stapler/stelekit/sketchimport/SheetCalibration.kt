// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.parseLength
import kotlin.math.abs
import kotlin.math.hypot

/** Which way the drawing's own x axis runs: the elevation fixture is drawn in negative local x. */
enum class ViewDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

sealed interface CalibrationError {
    /** No label gave a known length, and a unit is never assumed. */
    data object NoKnownDimension : CalibrationError
    data object NoGeometry : CalibrationError
    data class Transform(val error: TransformError) : CalibrationError
}

/**
 * [pxPerInchTransform] is the scale the transform chain implies for the sheet's content (the
 * drawing is authored in inches); [pxPerInchLabel] is the longest horizontal drawn segment divided
 * by the length a label states. [labelAgreement] is their relative difference.
 */
data class SheetCalibration(
    val pxPerInchTransform: Double,
    val pxPerInchLabel: Double,
    val labelAgreement: Double,
    val viewDirection: ViewDirection,
)

internal val LABEL_LENGTH = Regex("""\d[\d /.'\-]*["']?""")

/** Known length from a label such as `Total Width: 136"` or `24" Usable Depth`, in inches. */
private fun knownInches(label: String): Double? {
    val afterColon = label.substringAfter(':', label)
    val token = LABEL_LENGTH.find(afterColon)?.value ?: return null
    val measured = parseLength(token).getOrNull() as? ParsedLength.Measured ?: return null
    return measured.value.num.toDouble() / measured.value.den.toDouble()
}

fun calibrateSheet(doc: SvgDoc): Either<CalibrationError, SheetCalibration> {
    val known = doc.texts.firstNotNullOfOrNull { t ->
        if (t.text.contains("Total Width") || t.text.contains("Usable Depth")) knownInches(t.text) else null
    } ?: return CalibrationError.NoKnownDimension.left()

    var found = false
    var bestWorld = 0.0
    var bestAffine = Affine.IDENTITY
    var centroidSum = 0.0
    var segmentCount = 0
    for (path in doc.paths) {
        val m = composeTransforms(path.transforms).fold({ return CalibrationError.Transform(it).left() }, { it })
        for (seg in path.segments) {
            centroidSum += (seg.x1 + seg.x2) / 2
            segmentCount++
            if (seg.y1 != seg.y2) continue
            val (wx1, wy1) = m.apply(seg.x1, seg.y1)
            val (wx2, wy2) = m.apply(seg.x2, seg.y2)
            val len = hypot(wx2 - wx1, wy2 - wy1)
            if (len > bestWorld) {
                bestWorld = len
                found = true
                bestAffine = m
            }
        }
    }
    if (!found || segmentCount == 0) return CalibrationError.NoGeometry.left()

    val transformScale = hypot(bestAffine.a, bestAffine.b)
    val labelScale = bestWorld / known
    val direction = if (centroidSum / segmentCount * bestAffine.a < 0) {
        ViewDirection.RIGHT_TO_LEFT
    } else {
        ViewDirection.LEFT_TO_RIGHT
    }
    return SheetCalibration(transformScale, labelScale, abs(transformScale - labelScale) / transformScale, direction)
        .right()
}
