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

/** What the field says about the text typed so far, before anything is committed. */
data class DimensionReading(
    /** `= 3' 7 3/4" (43 3/4")`: the value in the chosen units first, then the other common spelling. */
    val text: String,
    /** The sheet could not hold the typed value exactly; this says what it will be rounded to. Null for an exact value. */
    val roundedNotice: String?,
    val typed: TypedLength,
)

/** Pure live interpretation of typed length text; the field shows it and the commit uses the very same parse. */
object DimensionHint {
    /** Null while the text is empty or not (yet) a length: the field then keeps its usual hint and reports the error on submit. */
    fun reading(text: String, format: LengthFormat): DimensionReading? {
        val typed = DimensionInput.interpret(text).getOrNull() ?: return null
        val primary = LengthFormatter.format(typed.inches, format)
        val other = otherSpelling(typed.inches, format).takeIf { it != primary }
        val notice = typed.roundedFrom?.let { U.rounded(it, DimensionInput.roundedNoticeText(typed.inches)) }
        return DimensionReading(U.reading(primary, other), notice, typed)
    }

    /** The spelling shown beside the primary one: inches for a feet-and-inches value, feet and inches for the others. */
    private fun otherSpelling(inches: Inches, format: LengthFormat): String = when (format) {
        LengthFormat.FEET_INCHES -> LengthFormatter.format(inches, LengthFormat.INCHES_MIXED)
        LengthFormat.INCHES_MIXED, LengthFormat.DECIMAL_INCHES -> LengthFormatter.format(inches, LengthFormat.FEET_INCHES)
    }
}
