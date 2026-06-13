package dev.stapler.stelekit.platform.measurement

import dev.stapler.stelekit.platform.measurement.ble.LeicaDistoProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [LeicaDistoProtocol] — Story 5.3.
 *
 * Byte sequences derived from the seichter/d2relay reference implementation.
 */
class LeicaDistoProtocolTest {

    @Test
    fun `parseNotification returns correct meters for known 3-45m sequence`() {
        // 3.45 m as little-endian IEEE 754 float: CD CC 5C 40
        val bytes = byteArrayOf(0xCD.toByte(), 0xCC.toByte(), 0x5C.toByte(), 0x40.toByte())
        val reading = LeicaDistoProtocol.parseNotification(bytes, "test-device")
        assertNotNull(reading)
        assertEquals("test-device", reading.deviceId)
        // Float precision: allow small epsilon
        val delta = 0.001
        assertEquals(3.45, reading.valueMeters, delta)
    }

    @Test
    fun `parseNotification returns correct meters for 1-0m sequence`() {
        // 1.0 m as little-endian IEEE 754 float: 00 00 80 3F
        val bytes = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x80.toByte(), 0x3F.toByte())
        val reading = LeicaDistoProtocol.parseNotification(bytes, "leica-test")
        assertNotNull(reading)
        assertEquals(1.0, reading.valueMeters, 0.0001)
    }

    @Test
    fun `parseNotification returns correct meters for 10-0m sequence`() {
        // 10.0 m as little-endian IEEE 754 float: 00 00 20 41
        val bytes = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x20.toByte(), 0x41.toByte())
        val reading = LeicaDistoProtocol.parseNotification(bytes, "leica-test")
        assertNotNull(reading)
        assertEquals(10.0, reading.valueMeters, 0.001)
    }

    @Test
    fun `parseNotification returns null for payload shorter than 4 bytes`() {
        val reading = LeicaDistoProtocol.parseNotification(byteArrayOf(0x01, 0x02), "test")
        assertNull(reading, "Should return null for < 4 bytes")
    }

    @Test
    fun `parseNotification returns null for empty payload`() {
        val reading = LeicaDistoProtocol.parseNotification(byteArrayOf(), "test")
        assertNull(reading)
    }

    @Test
    fun `parseNotification returns null for zero value`() {
        // 0.0f as little-endian: 00 00 00 00
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val reading = LeicaDistoProtocol.parseNotification(bytes, "test")
        assertNull(reading, "Zero reading should be rejected (error state from DISTO)")
    }

    @Test
    fun `parseNotification returns null for negative value`() {
        // -1.0f as little-endian: 00 00 80 BF
        val bytes = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x80.toByte(), 0xBF.toByte())
        val reading = LeicaDistoProtocol.parseNotification(bytes, "test")
        assertNull(reading, "Negative reading should be rejected")
    }

    @Test
    fun `parseNotification stores copy of raw bytes`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F.toByte()) // 1.0m
        val reading = LeicaDistoProtocol.parseNotification(bytes, "test")
        assertNotNull(reading)
        assertNotNull(reading.rawBytes)
        assertEquals(4, reading.rawBytes!!.size)
    }

    @Test
    fun `parseNotification uses default deviceId when not provided`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F.toByte()) // 1.0m
        val reading = LeicaDistoProtocol.parseNotification(bytes)
        assertNotNull(reading)
        assertEquals("leica-disto", reading.deviceId)
    }

    @Test
    fun `SERVICE_UUID has correct value`() {
        assertEquals("3ab10100-f831-4395-b29d-570977d5bf94", LeicaDistoProtocol.SERVICE_UUID)
    }

    @Test
    fun `ACK_BYTES has correct length and values`() {
        assertEquals(2, LeicaDistoProtocol.ACK_BYTES.size)
        assertEquals(0x04.toByte(), LeicaDistoProtocol.ACK_BYTES[0])
        assertEquals(0x00.toByte(), LeicaDistoProtocol.ACK_BYTES[1])
    }
}
