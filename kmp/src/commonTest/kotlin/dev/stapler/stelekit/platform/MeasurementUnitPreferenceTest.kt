package dev.stapler.stelekit.platform

import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class MeasurementUnitPreferenceTest {
    private class MapSettings(val map: MutableMap<String, String> = mutableMapOf()) : Settings {
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) {}
        override fun getString(key: String, defaultValue: String) = map[key] ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    @Test
    fun imageAnnotationDefaultsToFeetInches() {
        val a = ImageAnnotation(uuid = "a1", blockUuid = "b1", pageUuid = "p1", graphPath = "/g", filePath = "f.jpg")
        assertEquals(MeasurementUnit.FEET_INCHES, a.unit)
    }

    @Test
    fun keyNameIsStable() {
        assertEquals("image_meter_default_unit", IMAGE_METER_DEFAULT_UNIT_KEY)
    }

    @Test
    fun unsetSettingFallsBackToFeetInches() {
        assertEquals(MeasurementUnit.FEET_INCHES, MapSettings().defaultMeasurementUnit())
    }

    @Test
    fun storedUnitRoundTrips() {
        val s = MapSettings()
        s.setDefaultMeasurementUnit(MeasurementUnit.METERS)
        assertEquals("METERS", s.map[IMAGE_METER_DEFAULT_UNIT_KEY])
        assertEquals(MeasurementUnit.METERS, s.defaultMeasurementUnit())
    }

    @Test
    fun corruptStoredValueFallsBackToFeetInches() {
        val s = MapSettings(mutableMapOf<String, String>(IMAGE_METER_DEFAULT_UNIT_KEY to "FURLONGS"))
        assertEquals(MeasurementUnit.FEET_INCHES, s.defaultMeasurementUnit())
    }
}
