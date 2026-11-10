package dev.stapler.stelekit.units

enum class LengthFormat {
    /** `5' 4 1/4"`, `6' 0"`; under one foot it is the same as [INCHES_MIXED]. */
    FEET_INCHES,

    /** `33 7/16"`, `7/16"`, `0"`. */
    INCHES_MIXED,
}

/**
 * Canonical text for a length in whole sixteenths: lowest-terms fractions, integer math only.
 * Output always re-parses to the same value with [parseLength].
 */
object LengthFormatter {
    private const val SIXTEENTHS = 16L
    private const val INCHES_PER_FOOT = 12L

    fun format(length: Inches, format: LengthFormat): String {
        val total = length.sixteenths
        // Split the signed value first, then take magnitudes: abs(Long.MIN_VALUE) never happens.
        val wholeInches = magnitude(total / SIXTEENTHS)
        val sixteenths = magnitude(total % SIXTEENTHS)
        val sign = if (total < 0) "-" else ""
        val body = when (format) {
            LengthFormat.INCHES_MIXED -> inchesText(wholeInches, sixteenths)
            LengthFormat.FEET_INCHES -> {
                val feet = wholeInches / INCHES_PER_FOOT
                val inches = inchesText(wholeInches % INCHES_PER_FOOT, sixteenths)
                if (feet == 0L) inches else "$feet' $inches"
            }
        }
        return sign + body
    }

    private fun magnitude(x: Long): Long = if (x < 0) -x else x

    private fun inchesText(whole: Long, sixteenths: Long): String {
        if (sixteenths == 0L) return "$whole\""
        val g = CheckedMath.gcd(sixteenths, SIXTEENTHS)
        val fraction = "${sixteenths / g}/${SIXTEENTHS / g}"
        return if (whole == 0L) "$fraction\"" else "$whole $fraction\""
    }
}
