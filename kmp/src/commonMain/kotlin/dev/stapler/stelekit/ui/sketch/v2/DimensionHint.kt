// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.DimensionInput
import dev.stapler.stelekit.sketch.TypedLength
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings.Units as U
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import dev.stapler.stelekit.units.MetricLength
import dev.stapler.stelekit.units.MetricUnit

/** What the field says about the text typed so far, before anything is committed. */
data class DimensionReading(
    /** `= 3' 7 3/4" (43 3/4")`: the value in the chosen units first, then the other common spelling. */
    val text: String,
    /** The value could not be stored exactly as typed; this says what it will be rounded to. Null for an exact value. */
    val roundedNotice: String?,
    /** The typed length rounded to the nearest sixteenth, for consumers that only hold whole sixteenths. */
    val typed: TypedLength,
    /** The typed length in scene units: fractional for metric text, which is stored to 0.1 mm rather than rounded to a sixteenth. */
    val units: Double = typed.inches.sixteenths.toDouble(),
    /** The unit written on the text (`mm`, `cm`, `m`), or null; the next field of the same edit reads a plain number in it. */
    val writtenUnit: MetricUnit? = null,
)

/** Pure live interpretation of typed length text; the field shows it and the commit uses the very same parse. */
object DimensionHint {
    /** Null while the text is empty or not (yet) a length: the field then keeps its usual hint and reports the error on submit. */
    fun reading(text: String, format: LengthFormat, bare: MetricUnit? = null): DimensionReading? {
        val typed = DimensionInput.interpretUnits(text, format, bare).getOrNull() ?: return null
        val nearest = typed.nearestInches
        val roundedFrom = typed.roundedFrom
        val writtenUnit = MetricLength.explicitUnitOf(text)
        val metricTyped = typed.metricTenthsMm != null
        val inchFormat = if (MetricLength.isMetric(format)) LengthFormat.FEET_INCHES else format
        return if (metricTyped) {
            val metricFormat = if (MetricLength.isMetric(format)) format else LengthFormat.METRIC_MM
            val tenths = typed.metricTenthsMm ?: 0L
            val primary = MetricLength.format(tenths, metricFormat)
            val approx = U.approx(LengthFormatter.format(nearest, inchFormat))
            val notice = roundedFrom?.let { U.roundedMetric(it, primary) }
            DimensionReading(U.reading(primary, approx), notice, TypedLength(nearest), typed.units, writtenUnit)
        } else {
            val typedLength = TypedLength(nearest, roundedFrom)
            val primary = LengthFormatter.format(nearest, format)
            val other = otherSpelling(nearest, format).takeIf { it != primary }
            val notice = roundedFrom?.let { U.rounded(it, DimensionInput.roundedNoticeText(nearest, format)) }
            DimensionReading(U.reading(primary, other), notice, typedLength, typed.units, writtenUnit)
        }
    }

    /** The spelling shown beside the primary one: inches for a feet-and-inches value, feet and inches for the others. */
    private fun otherSpelling(inches: Inches, format: LengthFormat): String = when (format) {
        LengthFormat.FEET_INCHES -> LengthFormatter.format(inches, LengthFormat.INCHES_MIXED)
        LengthFormat.INCHES_MIXED, LengthFormat.DECIMAL_INCHES, LengthFormat.METRIC_MM, LengthFormat.METRIC_CM_M -> LengthFormatter.format(inches, LengthFormat.FEET_INCHES)
    }
}
