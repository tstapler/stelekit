package dev.stapler.stelekit.platform.measurement.keyboard

import dev.stapler.stelekit.platform.measurement.MeasurementReading

/**
 * Parses typed measurement strings into [MeasurementReading] values.
 *
 * Supported unit suffixes (case-insensitive):
 *   `mm`, `cm`, `m`, `ft`, `in`
 *
 * Decimal separators: both `.` and `,` are accepted.
 * Trailing whitespace and leading whitespace are ignored.
 *
 * Unit-less values are treated as meters.
 */
object KeyboardMeasurementParser {

    /**
     * Regex captures:
     *   Group 1: numeric value (digits, optional decimal separator, more digits)
     *   Group 2: optional unit suffix
     *
     * Matches formats like: `3.45`, `3.45m`, `3,45 m`, `345mm`, `11.35 ft`, `5in`
     */
    private val MEASUREMENT_REGEX = Regex(
        """^\s*(\d+[.,]?\d*)\s*(mm|cm|m|ft|in)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parse [text] into a [MeasurementReading], converting to meters.
     *
     * Returns null when:
     * - [text] does not match the supported pattern
     * - The numeric value is non-positive
     *
     * @param text     user-typed or BT-HID-typed measurement string
     * @param deviceId device identifier to embed in the returned reading
     */
    fun parse(text: String, deviceId: String = "keyboard"): MeasurementReading? {
        val match = MEASUREMENT_REGEX.matchEntire(text.trim()) ?: return null

        val rawNumber = match.groupValues[1].replace(',', '.')
        val value = rawNumber.toDoubleOrNull() ?: return null
        if (value <= 0.0) return null

        val unit = match.groupValues[2].lowercase().ifBlank { "m" }

        val meters = when (unit) {
            "mm" -> value / 1_000.0
            "cm" -> value / 100.0
            "m" -> value
            "ft" -> value * 0.3048
            "in" -> value * 0.0254
            else -> value // unreachable given the regex
        }

        return MeasurementReading(
            valueMeters = meters,
            deviceId = deviceId,
        )
    }
}
