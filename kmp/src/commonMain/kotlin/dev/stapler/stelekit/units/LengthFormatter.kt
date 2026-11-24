package dev.stapler.stelekit.units

enum class LengthFormat {
    /** `5' 4 1/4"`, `6' 0"`; under one foot it is the same as [INCHES_MIXED]. */
    FEET_INCHES,

    /** `33 7/16"`, `7/16"`, `0"`. */
    INCHES_MIXED,

    /** `43.75"`, `0.0625"`, `12"`: decimal inches, trailing zeros trimmed (every sixteenth is at most four decimal places). */
    DECIMAL_INCHES,

    /** `12.3 mm`, `25.4 mm`: millimetres to 0.1 mm. Appended after the inch formats so a stored choice keeps its meaning. */
    METRIC_MM,

    /** `12.34 cm` below a metre, `1.2345 m` from there on. */
    METRIC_CM_M,
}

/**
 * Canonical text for a length in whole sixteenths: lowest-terms fractions, integer math only.
 * Output always re-parses to the same value with [parseLength].
 */
object LengthFormatter {
    private const val SIXTEENTHS = 16L
    private const val INCHES_PER_FOOT = 12L
    private const val DECIMAL_PLACES = 4
    private const val DECIMAL_SCALE = 10_000L

    fun format(length: Inches, format: LengthFormat): String {
        if (MetricLength.isMetric(format)) return MetricLength.format(MetricLength.tenthsOfSixteenths(length.sixteenths), format)
        val total = length.sixteenths
        // Split the signed value first, then take magnitudes: abs(Long.MIN_VALUE) never happens.
        val wholeInches = magnitude(total / SIXTEENTHS)
        val sixteenths = magnitude(total % SIXTEENTHS)
        val sign = if (total < 0) "-" else ""
        val body = when (format) {
            LengthFormat.METRIC_MM, LengthFormat.METRIC_CM_M -> error("metric formats return above")
            LengthFormat.INCHES_MIXED -> inchesText(wholeInches, sixteenths)
            LengthFormat.DECIMAL_INCHES -> decimalText(wholeInches, sixteenths)
            LengthFormat.FEET_INCHES -> {
                val feet = wholeInches / INCHES_PER_FOOT
                val inches = inchesText(wholeInches % INCHES_PER_FOOT, sixteenths)
                if (feet == 0L) inches else "$feet' $inches"
            }
        }
        return sign + body
    }

    private fun decimalText(whole: Long, sixteenths: Long): String {
        if (sixteenths == 0L) return "$whole\""
        // sixteenths/16 = (sixteenths * 625) / 10000, so four digits always carry it exactly.
        val digits = (sixteenths * DECIMAL_SCALE / SIXTEENTHS).toString().padStart(DECIMAL_PLACES, '0').trimEnd('0')
        return "$whole.$digits\""
    }

    private fun magnitude(x: Long): Long = if (x < 0) -x else x

    private fun inchesText(whole: Long, sixteenths: Long): String {
        if (sixteenths == 0L) return "$whole\""
        val g = CheckedMath.gcd(sixteenths, SIXTEENTHS)
        val fraction = "${sixteenths / g}/${SIXTEENTHS / g}"
        return if (whole == 0L) "$fraction\"" else "$whole $fraction\""
    }
}
