package dev.stapler.stelekit.platform

import dev.stapler.stelekit.model.DEFAULT_MEASUREMENT_UNIT
import dev.stapler.stelekit.model.MeasurementUnit

/** String setting: name of the [MeasurementUnit] new image annotations start with. */
const val IMAGE_METER_DEFAULT_UNIT_KEY = "image_meter_default_unit"

/** The user's global default measurement unit; no settings, or an unset/unrecognised value, falls back to [DEFAULT_MEASUREMENT_UNIT]. */
fun Settings?.defaultMeasurementUnit(): MeasurementUnit =
    this?.let { runCatching { MeasurementUnit.valueOf(it.getString(IMAGE_METER_DEFAULT_UNIT_KEY, DEFAULT_MEASUREMENT_UNIT.name)) }.getOrNull() }
        ?: DEFAULT_MEASUREMENT_UNIT

fun Settings.setDefaultMeasurementUnit(unit: MeasurementUnit) = putString(IMAGE_METER_DEFAULT_UNIT_KEY, unit.name)
