// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.annotation

import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.MetricLength
import kotlin.math.round

/**
 * The text of a room's floor area: square feet for the inch formats, square metres for the metric ones, one decimal.
 * A scene unit is a sixteenth of an inch, so a square foot is 192 x 192 square units and a square metre is
 * (1000 / 1.5875)^2 square units (1 unit = 1.5875 mm exactly).
 */
object RoomArea {
    private const val UNITS_PER_FOOT = 192.0
    private const val MM_PER_UNIT = 1.5875
    private const val MM_PER_METRE = 1000.0
    private const val TENTHS = 10L

    const val SQUARE_FEET_SUFFIX = "sq ft"
    const val SQUARE_METRES_SUFFIX = "m²"

    fun squareFeet(areaUnits: Double): Double = areaUnits / (UNITS_PER_FOOT * UNITS_PER_FOOT)

    fun squareMetres(areaUnits: Double): Double = areaUnits * (MM_PER_UNIT / MM_PER_METRE) * (MM_PER_UNIT / MM_PER_METRE)

    /** [areaUnits] square scene units as "123.4 sq ft" or "11.5 m²" per [format]. */
    fun text(areaUnits: Double, format: LengthFormat): String =
        if (MetricLength.isMetric(format)) "${oneDecimal(squareMetres(areaUnits))} $SQUARE_METRES_SUFFIX" else "${oneDecimal(squareFeet(areaUnits))} $SQUARE_FEET_SUFFIX"

    /** Integer arithmetic only: `Double.toString` is not stable across the targets. */
    private fun oneDecimal(v: Double): String {
        val tenths = round(v * TENTHS).toLong()
        return "${tenths / TENTHS}.${tenths % TENTHS}"
    }
}
