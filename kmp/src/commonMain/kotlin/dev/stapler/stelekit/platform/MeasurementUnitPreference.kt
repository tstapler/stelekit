package dev.stapler.stelekit.platform

import dev.stapler.stelekit.model.MeasurementUnit

/** String setting: name of the [MeasurementUnit] new image annotations start with. */
const val IMAGE_METER_DEFAULT_UNIT_KEY = "image_meter_default_unit"

/** The user's global default measurement unit; unset or unrecognised values fall back to feet-inches. */
fun Settings.defaultMeasurementUnit(): MeasurementUnit =
    runCatching { MeasurementUnit.valueOf(getString(IMAGE_METER_DEFAULT_UNIT_KEY, MeasurementUnit.FEET_INCHES.name)) }
        .getOrDefault(MeasurementUnit.FEET_INCHES)

fun Settings.setDefaultMeasurementUnit(unit: MeasurementUnit) = putString(IMAGE_METER_DEFAULT_UNIT_KEY, unit.name)
