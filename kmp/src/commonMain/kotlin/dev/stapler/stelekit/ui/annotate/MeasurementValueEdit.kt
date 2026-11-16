// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.MeasurementValue
import dev.stapler.stelekit.model.metersToDisplayString
import dev.stapler.stelekit.units.LengthContext
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.UnitContext
import dev.stapler.stelekit.units.parseLength

/**
 * Pure logic of editing a distance value (ADR-002): parse what the user typed in the entry unit, validate it,
 * and produce the overriding [MeasurementAnnotation]. Geometry and calibration are never touched here.
 */
object MeasurementValueEdit {
    enum class Error { EMPTY, NOT_A_NUMBER, NOT_POSITIVE, IMPLAUSIBLE }

    /** Outside 0.1 mm to 100 km a typed length is a unit slip, not a measurement of a photo. */
    private const val MIN_METERS = 1e-4
    private const val MAX_METERS = 1e5
    private const val INCH_METERS = 0.0254
    private const val FOOT_METERS = 0.3048

    private val metric = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+))\s*(mm|cm|m)$""", RegexOption.IGNORE_CASE)
    private val bareNumber = Regex("""^[+-]?(?:\d+\.?\d*|\.\d+)$""")

    /** Meters for [text] typed with the chip on [entryUnit]; a unit written in the text wins over the chip. */
    fun parse(text: String, entryUnit: MeasurementUnit): Either<Error, Double> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Error.EMPTY.left()
        val meters = metricMeters(trimmed) ?: bareMeters(trimmed, entryUnit) ?: markedMeters(trimmed)
            ?: return Error.NOT_A_NUMBER.left()
        return validate(meters)
    }

    private fun validate(meters: Double): Either<Error, Double> = when {
        !meters.isFinite() -> Error.NOT_A_NUMBER.left()
        meters <= 0.0 -> Error.NOT_POSITIVE.left()
        meters < MIN_METERS || meters > MAX_METERS -> Error.IMPLAUSIBLE.left()
        else -> meters.right()
    }

    private fun metricMeters(text: String): Double? {
        val m = metric.matchEntire(text) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "mm" -> value / 1000.0
            "cm" -> value / 100.0
            else -> value
        }
    }

    private fun bareMeters(text: String, unit: MeasurementUnit): Double? {
        if (!bareNumber.matches(text)) return null
        val value = text.toDoubleOrNull() ?: return null
        return when (unit) {
            MeasurementUnit.METERS -> value
            MeasurementUnit.CENTIMETERS -> value / 100.0
            MeasurementUnit.MILLIMETERS -> value / 1000.0
            MeasurementUnit.FEET, MeasurementUnit.FEET_INCHES -> value * FOOT_METERS
            MeasurementUnit.INCHES -> value * INCH_METERS
        }
    }

    /** Feet and inches written with marks or words (`5'3"`, `10 1/2"`, `2.5 ft`), via the shared length grammar. */
    private fun markedMeters(text: String): Double? {
        val parsed = parseLength(text, LengthContext(UnitContext.UNKNOWN)).getOrNull() as? ParsedLength.Measured ?: return null
        return parsed.value.num.toDouble() / parsed.value.den.toDouble() * INCH_METERS
    }

    /** Copy of [measurement] carrying the typed [meters] as an override; the first computed value stays as the original. */
    fun apply(measurement: MeasurementAnnotation, meters: Double, displayUnit: MeasurementUnit): MeasurementAnnotation {
        val original = when (val current = measurement.value) {
            is MeasurementValue.Overridden -> current.originalMeters
            is MeasurementValue.Computed -> current.meters
            null -> meters
        }
        return measurement.withValue(MeasurementValue.Overridden(meters, original))
            .copy(valueDisplay = metersToDisplayString(meters, displayUnit))
    }
}
