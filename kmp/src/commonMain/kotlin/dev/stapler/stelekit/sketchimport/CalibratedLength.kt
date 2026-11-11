// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import dev.stapler.stelekit.units.Rational
import kotlin.math.round

/**
 * The one place a calibrated `Double` becomes an exact [Rational] length. It lives outside the
 * Double-free `units` package (plan R2-V M5): [sixteenths] is the single rounding step, at 1/16".
 */
object CalibratedLength {
    const val DENOMINATOR = 16L
    const val METERS_PER_INCH = 0.0254

    /** Nearest whole 1/16" to [inches]; the one rounding rule shared with the measurement label formatter. */
    fun sixteenths(inches: Double): Long = round(inches * DENOMINATOR).toLong()

    /** [meters] as exact inches rounded to 1/16", or null for a non-finite or out-of-range input. */
    fun metersToInches(meters: Double): Rational? {
        if (!meters.isFinite()) return null
        val sixteenths = sixteenths(meters / METERS_PER_INCH)
        if (sixteenths == Long.MIN_VALUE || sixteenths == Long.MAX_VALUE) return null
        return Rational.of(sixteenths, DENOMINATOR).getOrNull()
    }

    fun inchesToMeters(inches: Rational): Double = inches.num.toDouble() / inches.den.toDouble() * METERS_PER_INCH
}
