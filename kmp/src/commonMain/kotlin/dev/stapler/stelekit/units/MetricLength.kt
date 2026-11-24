package dev.stapler.stelekit.units

import kotlin.math.round

/** A metric unit a length can be written in. [tenthsMmPerUnit] is how many tenths of a millimetre one of it is. */
enum class MetricUnit(val suffix: String, val tenthsMmPerUnit: Long) {
    MM("mm", 1L * MetricLength.TENTHS_PER_MM),
    CM("cm", 10L * MetricLength.TENTHS_PER_MM),
    M("m", 1000L * MetricLength.TENTHS_PER_MM),
}

/** The metric reading of typed text: [tenthsMm] is the length rounded to 0.1 mm, [rounded] says the text had to be rounded to get there. */
data class MetricReading(val tenthsMm: Long, val rounded: Boolean)

/** How [MetricLength.parse] saw a piece of text. */
sealed interface MetricParse {
    /** Not a plain metric number; the caller tries the feet-and-inches grammar instead. */
    data object NotMetric : MetricParse

    data object TooLarge : MetricParse

    data class Ok(val reading: MetricReading) : MetricParse
}

/**
 * Metric lengths against the sheet's scene unit (1 unit = 1/16 inch = 1.5875 mm). Typed metric values keep a resolution of
 * 0.1 mm (a tenth of a millimetre is 0.063 units) and are stored as a `Double` number of units, so they are NOT rounded to the
 * sixteenth. Everything here is integer math on tenths of a millimetre; the one float step is [toUnits], a single division.
 */
object MetricLength {
    const val TENTHS_PER_MM = 10L
    private const val TENTHS_PER_INCH = 254L
    private const val UNITS_PER_INCH = 16L
    private const val MAX_DIGITS = 12
    private const val DECIMAL_BASE = 10L
    private const val TENTHS_PER_CM = 100L
    private const val TENTHS_PER_M = 10_000L
    private const val CM_DECIMALS = 2
    private const val M_DECIMALS = 4
    private const val MM_DECIMALS = 1
    private const val MAX_TENTHS = (1L shl 28) * TENTHS_PER_INCH / UNITS_PER_INCH

    /** The metric unit a bare number means in [format], or null for the inch formats. */
    fun bareUnitOf(format: LengthFormat): MetricUnit? = when (format) {
        LengthFormat.METRIC_MM -> MetricUnit.MM
        LengthFormat.METRIC_CM_M -> MetricUnit.CM
        else -> null
    }

    fun isMetric(format: LengthFormat): Boolean = bareUnitOf(format) != null

    /** The unit suffix [text] ends with (`mm`, `cm` or `m`, any case, after a digit or a space), or null. */
    fun explicitUnitOf(text: String): MetricUnit? {
        val t = text.trim().lowercase()
        val body = listOf(MetricUnit.MM, MetricUnit.CM, MetricUnit.M).firstOrNull { t.endsWith(it.suffix) } ?: return null
        val rest = t.removeSuffix(body.suffix).trimEnd()
        return body.takeIf { rest.isNotEmpty() && (rest.last().isDigit() || rest.last() == '.' || rest.last() == ',') }
    }

    /**
     * Reads `12.5 mm`, `3,5 cm`, `1.2m` or, when [bare] is given, a plain number in that unit. Both `.` and `,` are a decimal
     * mark; a number with more than one mark, a sign or any other text is [MetricParse.NotMetric]. Never throws.
     */
    fun parse(text: String, bare: MetricUnit?): MetricParse {
        val t = text.trim().lowercase()
        val explicit = explicitUnitOf(t)
        val unit = explicit ?: bare ?: return MetricParse.NotMetric
        val number = (if (explicit != null) t.removeSuffix(explicit.suffix) else t).trim()
        val marks = number.count { it == '.' || it == ',' }
        val digitsOnly = number.filter { it != '.' && it != ',' }
        val wellFormed = marks <= 1 && digitsOnly.isNotEmpty() && digitsOnly.all { it in '0'..'9' }
        if (!wellFormed) return MetricParse.NotMetric
        if (digitsOnly.length > MAX_DIGITS) return MetricParse.TooLarge
        val markAt = number.indexOfFirst { it == '.' || it == ',' }
        val decimals = if (markAt < 0) 0 else number.length - markAt - 1
        var scale = 1L
        repeat(decimals) { scale *= DECIMAL_BASE }
        val scaled = digitsOnly.toLong() * unit.tenthsMmPerUnit
        val tenths = scaled / scale
        val remainder = scaled % scale
        val roundedUp = if (remainder * 2 >= scale) tenths + 1 else tenths
        return if (roundedUp > MAX_TENTHS) MetricParse.TooLarge else MetricParse.Ok(MetricReading(roundedUp, remainder != 0L))
    }

    /** Scene units (sixteenths of an inch, fractional) for [tenthsMm]; one correctly rounded division. */
    fun toUnits(tenthsMm: Long): Double = tenthsMm.toDouble() * UNITS_PER_INCH / TENTHS_PER_INCH

    /** The nearest tenth of a millimetre to [units] scene units. */
    fun tenthsOf(units: Double): Long = round(units * TENTHS_PER_INCH / UNITS_PER_INCH).toLong()

    /** The nearest tenth of a millimetre to [sixteenths] of an inch (exact halves away from zero). */
    fun tenthsOfSixteenths(sixteenths: Long): Long {
        val whole = sixteenths / UNITS_PER_INCH
        val rest = sixteenths % UNITS_PER_INCH
        val restTenths = (kotlin.math.abs(rest) * TENTHS_PER_INCH + UNITS_PER_INCH / 2) / UNITS_PER_INCH
        return whole * TENTHS_PER_INCH + if (rest < 0) -restTenths else restTenths
    }

    /** `12.3 mm`, `25 mm`: millimetres with at most one decimal. */
    fun formatMm(tenthsMm: Long): String = withUnit(tenthsMm, TENTHS_PER_MM, MM_DECIMALS, MetricUnit.MM)

    /** Centimetres below one metre (`12.34 cm`), metres from there on (`1.2345 m`); trailing zeros trimmed, so the text re-parses to the same tenths. */
    fun formatCmM(tenthsMm: Long): String =
        if (kotlin.math.abs(tenthsMm) < TENTHS_PER_M) withUnit(tenthsMm, TENTHS_PER_CM, CM_DECIMALS, MetricUnit.CM)
        else withUnit(tenthsMm, TENTHS_PER_M, M_DECIMALS, MetricUnit.M)

    fun format(tenthsMm: Long, format: LengthFormat): String =
        if (format == LengthFormat.METRIC_CM_M) formatCmM(tenthsMm) else formatMm(tenthsMm)

    private fun withUnit(tenthsMm: Long, perUnit: Long, decimals: Int, unit: MetricUnit): String {
        val sign = if (tenthsMm < 0) "-" else ""
        val magnitude = if (tenthsMm < 0) -tenthsMm else tenthsMm
        val whole = magnitude / perUnit
        val fraction = (magnitude % perUnit).toString().padStart(decimals, '0').trimEnd('0')
        val number = if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
        return "$sign$number ${unit.suffix}"
    }
}
