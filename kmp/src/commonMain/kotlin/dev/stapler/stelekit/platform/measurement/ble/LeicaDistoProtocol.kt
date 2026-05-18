package dev.stapler.stelekit.platform.measurement.ble

import dev.stapler.stelekit.platform.measurement.MeasurementReading

/**
 * Pure-Kotlin parser for the Leica DISTO BLE GATT measurement protocol.
 *
 * No BLE library dependency — this is entirely data parsing logic that can be
 * tested in commonTest without any hardware or BLE runtime.
 *
 * ## Protocol summary (from seichter/d2relay reference implementation)
 *
 * Service UUID : [SERVICE_UUID]
 * Notify characteristic : [MEASUREMENT_CHARACTERISTIC_UUID]
 *   Payload: 4-byte little-endian IEEE 754 float, value in meters.
 *   Example: bytes [CD CC 5C 40] → 3.45 m
 *
 * Write characteristic : [ACK_CHARACTERISTIC_UUID]
 *   After each notification the client must write [ACK_BYTES] within 2 seconds or
 *   the DISTO stops sending further measurements.
 *
 * Device filter: advertised service UUID starts with [SERVICE_UUID_PREFIX].
 */
object LeicaDistoProtocol {

    /**
     * Primary GATT service UUID for Leica DISTO measurement service.
     */
    const val SERVICE_UUID: String = "3ab10100-f831-4395-b29d-570977d5bf94"

    /**
     * UUID prefix used to filter BLE scan advertisements.
     * Matches all Leica DISTO generations that share this service range.
     */
    const val SERVICE_UUID_PREFIX: String = "3ab10100"

    /**
     * Notification characteristic that emits distance readings.
     * UUID: 3ab10101-f831-4395-b29d-570977d5bf94
     */
    const val MEASUREMENT_CHARACTERISTIC_UUID: String = "3ab10101-f831-4395-b29d-570977d5bf94"

    /**
     * Write characteristic used to acknowledge each measurement.
     * UUID: 3ab10109-f831-4395-b29d-570977d5bf94
     *
     * The ACK must be written within 2 seconds of receiving a measurement notification.
     */
    const val ACK_CHARACTERISTIC_UUID: String = "3ab10109-f831-4395-b29d-570977d5bf94"

    /**
     * Byte sequence to write to [ACK_CHARACTERISTIC_UUID] to acknowledge a measurement.
     */
    val ACK_BYTES: ByteArray = byteArrayOf(0x04, 0x00)

    /**
     * Parse a 4-byte little-endian IEEE 754 float notification from the DISTO measurement
     * characteristic into a [MeasurementReading].
     *
     * Returns null if:
     * - [bytes] has fewer than 4 bytes
     * - The decoded float is NaN, infinite, negative, or zero (DISTO emits 0.0 on error states)
     *
     * @param bytes  raw notification payload from [MEASUREMENT_CHARACTERISTIC_UUID]
     * @param deviceId  stable device identifier (BLE MAC address or test ID)
     */
    fun parseNotification(bytes: ByteArray, deviceId: String = "leica-disto"): MeasurementReading? {
        if (bytes.size < 4) return null

        // Reassemble 4 bytes as a little-endian 32-bit integer then reinterpret as IEEE 754 float.
        val bits = ((bytes[3].toInt() and 0xFF) shl 24) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            (bytes[0].toInt() and 0xFF)

        val value = Float.fromBits(bits).toDouble()

        if (value.isNaN() || value.isInfinite() || value <= 0.0) return null

        return MeasurementReading(
            valueMeters = value,
            deviceId = deviceId,
            rawBytes = bytes.copyOf(),
        )
    }
}
