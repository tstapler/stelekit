package dev.stapler.stelekit.platform.measurement.ble

import dev.stapler.stelekit.platform.measurement.MeasurementReading

/**
 * Pure-Kotlin parser for the Bosch GLM BLE measurement protocol.
 *
 * No BLE library dependency — entirely data parsing logic testable in commonTest.
 *
 * ## Protocol summary (from philipptrenz/BOSCH-GLM-rangefinder reference implementation)
 *
 * Transport: SPP-over-BLE (Serial Port Profile emulation over BLE).
 * Classic BT UUID: 0x1101 (SPP service)
 *
 * Message format: ASCII text terminated by CRLF.
 *   `MM:D<float_value>\r\n`
 *
 * Examples:
 *   `MM:D3.450\r\n`     → 3.450 m
 *   `MM:D0.123\r\n`     → 0.123 m
 *   `MM:D10.00\r\n`     → 10.00 m
 *
 * Device scan filter: device name starts with "Bosch" or "GLM".
 */
object BoschGlmProtocol {

    /**
     * SPP-over-BLE service UUID used by Bosch GLM devices.
     */
    const val SPP_SERVICE_UUID: String = "00001101-0000-1000-8000-00805f9b34fb"

    /**
     * Scan-filter name prefixes for Bosch GLM BLE advertisement names.
     */
    val DEVICE_NAME_PREFIXES: List<String> = listOf("Bosch", "GLM")

    /**
     * Regex that matches the GLM ASCII measurement format `MM:D<value>` optionally
     * followed by CRLF or LF. The float value is captured in group 1.
     *
     * Accepts both `.` and `,` as decimal separators (locale-tolerance).
     */
    private val MEASUREMENT_REGEX = Regex("""MM:D([0-9]+[.,][0-9]+|[0-9]+)""")

    /**
     * Parse an ASCII response string from the Bosch GLM SPP characteristic.
     *
     * Returns a [MeasurementReading] if the string matches the `MM:D<value>` format,
     * or null if the format is unrecognized, the value is non-positive, or the value
     * is NaN/infinite.
     *
     * @param text  raw ASCII string received from the device (may include CRLF)
     * @param deviceId  stable device identifier (BLE MAC address or test ID)
     */
    fun parseResponse(text: String, deviceId: String = "bosch-glm"): MeasurementReading? {
        val match = MEASUREMENT_REGEX.find(text) ?: return null
        val rawValue = match.groupValues[1].replace(',', '.')
        val value = rawValue.toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite() || value <= 0.0) return null

        return MeasurementReading(
            valueMeters = value,
            deviceId = deviceId,
            rawBytes = text.encodeToByteArray(),
        )
    }
}
