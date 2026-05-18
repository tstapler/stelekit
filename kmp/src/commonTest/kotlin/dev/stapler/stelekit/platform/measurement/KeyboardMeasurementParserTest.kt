package dev.stapler.stelekit.platform.measurement

import dev.stapler.stelekit.platform.measurement.keyboard.KeyboardMeasurementParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [KeyboardMeasurementParser] — Story 5.5.
 */
class KeyboardMeasurementParserTest {

    // ── Meters ────────────────────────────────────────────────────────────────

    @Test
    fun `parse meters with explicit unit`() {
        val reading = KeyboardMeasurementParser.parse("3.45 m")
        assertNotNull(reading)
        assertEquals(3.45, reading.valueMeters, 0.0001)
    }

    @Test
    fun `parse meters without unit defaults to meters`() {
        val reading = KeyboardMeasurementParser.parse("3.45")
        assertNotNull(reading)
        assertEquals(3.45, reading.valueMeters, 0.0001)
    }

    @Test
    fun `parse meters uppercase unit`() {
        val reading = KeyboardMeasurementParser.parse("2.5 M")
        assertNotNull(reading)
        assertEquals(2.5, reading.valueMeters, 0.0001)
    }

    // ── Centimeters ───────────────────────────────────────────────────────────

    @Test
    fun `parse centimeters`() {
        val reading = KeyboardMeasurementParser.parse("345 cm")
        assertNotNull(reading)
        assertEquals(3.45, reading.valueMeters, 0.0001)
    }

    @Test
    fun `parse centimeters decimal`() {
        val reading = KeyboardMeasurementParser.parse("34.5 cm")
        assertNotNull(reading)
        assertEquals(0.345, reading.valueMeters, 0.0001)
    }

    // ── Millimeters ───────────────────────────────────────────────────────────

    @Test
    fun `parse millimeters`() {
        val reading = KeyboardMeasurementParser.parse("3450 mm")
        assertNotNull(reading)
        assertEquals(3.45, reading.valueMeters, 0.0001)
    }

    @Test
    fun `parse millimeters decimal`() {
        val reading = KeyboardMeasurementParser.parse("345.5 mm")
        assertNotNull(reading)
        assertEquals(0.3455, reading.valueMeters, 0.00001)
    }

    // ── Feet and inches ───────────────────────────────────────────────────────

    @Test
    fun `parse feet converts to meters`() {
        val reading = KeyboardMeasurementParser.parse("10 ft")
        assertNotNull(reading)
        assertEquals(3.048, reading.valueMeters, 0.001)
    }

    @Test
    fun `parse inches converts to meters`() {
        val reading = KeyboardMeasurementParser.parse("120 in")
        assertNotNull(reading)
        assertEquals(3.048, reading.valueMeters, 0.001)
    }

    // ── Comma decimal separator ───────────────────────────────────────────────

    @Test
    fun `parse comma decimal separator`() {
        val reading = KeyboardMeasurementParser.parse("3,45 m")
        assertNotNull(reading)
        assertEquals(3.45, reading.valueMeters, 0.0001)
    }

    @Test
    fun `parse comma decimal without unit`() {
        val reading = KeyboardMeasurementParser.parse("3,45")
        assertNotNull(reading)
        assertEquals(3.45, reading.valueMeters, 0.0001)
    }

    // ── Whitespace tolerance ──────────────────────────────────────────────────

    @Test
    fun `parse leading and trailing whitespace`() {
        val reading = KeyboardMeasurementParser.parse("  3.45 m  ")
        assertNotNull(reading)
        assertEquals(3.45, reading.valueMeters, 0.0001)
    }

    @Test
    fun `parse value with no space between number and unit`() {
        val reading = KeyboardMeasurementParser.parse("3.45m")
        assertNotNull(reading)
        assertEquals(3.45, reading.valueMeters, 0.0001)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `parse returns null for empty string`() {
        val reading = KeyboardMeasurementParser.parse("")
        assertNull(reading)
    }

    @Test
    fun `parse returns null for text only`() {
        val reading = KeyboardMeasurementParser.parse("hello")
        assertNull(reading)
    }

    @Test
    fun `parse returns null for zero value`() {
        val reading = KeyboardMeasurementParser.parse("0 m")
        assertNull(reading)
    }

    @Test
    fun `parse returns null for negative value`() {
        val reading = KeyboardMeasurementParser.parse("-3.45 m")
        assertNull(reading)
    }

    @Test
    fun `parse uses provided deviceId`() {
        val reading = KeyboardMeasurementParser.parse("1.0 m", deviceId = "test-device")
        assertNotNull(reading)
        assertEquals("test-device", reading.deviceId)
    }

    @Test
    fun `parse uses default keyboard deviceId`() {
        val reading = KeyboardMeasurementParser.parse("1.0 m")
        assertNotNull(reading)
        assertEquals("keyboard", reading.deviceId)
    }

    @Test
    fun `parse handles unit without decimal`() {
        val reading = KeyboardMeasurementParser.parse("5 m")
        assertNotNull(reading)
        assertEquals(5.0, reading.valueMeters, 0.0001)
    }
}
