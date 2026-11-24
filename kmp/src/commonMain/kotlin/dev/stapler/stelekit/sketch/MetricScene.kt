package dev.stapler.stelekit.sketch

import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import dev.stapler.stelekit.units.MetricLength
import kotlin.math.floor
import kotlin.math.round

/**
 * The `Double` side of metric lengths. The exact tenths-of-a-millimetre math lives in [MetricLength] (a floating-point-free
 * domain package, ADR-002); scene units are `Double`s (1 unit = 1/16 inch), so the conversion to and from them is here.
 */
object MetricScene {
    private const val TENTHS_PER_INCH = 254L
    private const val UNITS_PER_INCH = 16L
    private const val HALF = 0.5

    /** Scene units (sixteenths of an inch, fractional) for [tenthsMm]; one correctly rounded division. */
    fun toUnits(tenthsMm: Long): Double = tenthsMm.toDouble() * UNITS_PER_INCH / TENTHS_PER_INCH

    /** The nearest tenth of a millimetre to [units] scene units. */
    fun tenthsOf(units: Double): Long = round(units * TENTHS_PER_INCH / UNITS_PER_INCH).toLong()

    /**
     * [units] scene units as text: metric formats show the value to 0.1 mm without first rounding to a sixteenth; the inch formats
     * round to the nearest sixteenth (halves up) as the sheet's grid does.
     */
    fun formatUnits(units: Double, format: LengthFormat): String =
        if (MetricLength.isMetric(format)) MetricLength.format(tenthsOf(units), format)
        else LengthFormatter.format(Inches(floor(units + HALF).toLong()), format)
}
