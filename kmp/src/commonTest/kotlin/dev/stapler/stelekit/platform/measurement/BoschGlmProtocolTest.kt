package dev.stapler.stelekit.platform.measurement

import dev.stapler.stelekit.platform.measurement.ble.BoschGlmProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [BoschGlmProtocol] — Story 5.4.
 *
 * Format strings derived from philipptrenz/BOSCH-GLM-rangefinder reference implementation.
 */
class BoschGlmProtocolTest {

    @Test
    fun `parseResponse returns correct value for standard format`() {
        val reading = BoschGlmProtocol.parseResponse("MM:D3.450\r\n", "bosch-test")
        assertNotNull(reading)
        assertEquals(3.450, reading.valueMeters, 0.0001)
        assertEquals("bosch-test", reading.deviceId)
    }

    @Test
    fun `parseResponse returns correct value for 0-123m`() {
        val reading = BoschGlmProtocol.parseResponse("MM:D0.123\r\n")
        assertNotNull(reading)
        assertEquals(0.123, reading.valueMeters, 0.0001)
    }

    @Test
    fun `parseResponse returns correct value for 10-0m`() {
        val reading = BoschGlmProtocol.parseResponse("MM:D10.00\r\n")
        assertNotNull(reading)
        assertEquals(10.0, reading.valueMeters, 0.001)
    }

    @Test
    fun `parseResponse handles whole number without decimal`() {
        val reading = BoschGlmProtocol.parseResponse("MM:D5\r\n")
        assertNotNull(reading)
        assertEquals(5.0, reading.valueMeters, 0.0001)
    }

    @Test
    fun `parseResponse accepts comma as decimal separator`() {
        val reading = BoschGlmProtocol.parseResponse("MM:D3,45\r\n")
        assertNotNull(reading)
        assertEquals(3.45, reading.valueMeters, 0.001)
    }

    @Test
    fun `parseResponse returns null for unrecognized format`() {
        val reading = BoschGlmProtocol.parseResponse("HELLO WORLD")
        assertNull(reading, "Unrecognized format should return null")
    }

    @Test
    fun `parseResponse returns null for empty string`() {
        val reading = BoschGlmProtocol.parseResponse("")
        assertNull(reading)
    }

    @Test
    fun `parseResponse stores raw bytes`() {
        val text = "MM:D1.234\r\n"
        val reading = BoschGlmProtocol.parseResponse(text)
        assertNotNull(reading)
        assertNotNull(reading.rawBytes)
        val decoded = reading.rawBytes!!.decodeToString()
        assertEquals(text, decoded)
    }

    @Test
    fun `parseResponse uses default deviceId when not provided`() {
        val reading = BoschGlmProtocol.parseResponse("MM:D2.000\r\n")
        assertNotNull(reading)
        assertEquals("bosch-glm", reading.deviceId)
    }

    @Test
    fun `parseResponse works without CRLF terminator`() {
        val reading = BoschGlmProtocol.parseResponse("MM:D4.567")
        assertNotNull(reading)
        assertEquals(4.567, reading.valueMeters, 0.0001)
    }

    @Test
    fun `DEVICE_NAME_PREFIXES contains Bosch and GLM`() {
        assertEquals(true, "Bosch" in BoschGlmProtocol.DEVICE_NAME_PREFIXES)
        assertEquals(true, "GLM" in BoschGlmProtocol.DEVICE_NAME_PREFIXES)
    }
}
