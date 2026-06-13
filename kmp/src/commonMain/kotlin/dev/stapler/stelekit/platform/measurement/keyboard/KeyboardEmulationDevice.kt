package dev.stapler.stelekit.platform.measurement.keyboard

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.measurement.DeviceConnectionState
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ExternalMeasurementDevice] that accepts typed measurement strings instead of
 * receiving data over BLE or USB.
 *
 * Use case: the user types a measurement into a capture [TextField] (or uses a BT HID
 * keyboard-mode device that types the value as text). This class parses the typed
 * string and emits a [MeasurementReading] so the calibration/annotation injection
 * flow works identically regardless of the input transport.
 *
 * Supported formats (via [KeyboardMeasurementParser]):
 *   - `3.45 m`  → 3.45 m
 *   - `3.45`    → 3.45 m (unit-less, treated as meters)
 *   - `34.5 cm` → 0.345 m
 *   - `345 mm`  → 0.345 m
 *   - `11.35 ft`→ ~3.46 m
 *   - `135 in`  → ~3.43 m
 *   - `3,45 m`  → 3.45 m (comma decimal separator)
 */
class KeyboardEmulationDevice : ExternalMeasurementDevice {

    override val deviceName: String = "Keyboard"
    override val deviceId: String = DEVICE_ID

    private val _connectionState = MutableStateFlow(DeviceConnectionState.CONNECTED)
    override val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

    // Shared flow with no replay — each reading is consumed once by the active collector.
    private val _readings = MutableSharedFlow<MeasurementReading>(extraBufferCapacity = 8)

    // Internal scope owned by the device (never accept an external scope).
    @Suppress("unused")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun connect(): Either<DomainError, Unit> {
        _connectionState.value = DeviceConnectionState.CONNECTED
        return Unit.right()
    }

    override suspend fun disconnect() {
        _connectionState.value = DeviceConnectionState.DISCONNECTED
    }

    override fun measurementFlow(): Flow<MeasurementReading> = _readings

    /**
     * Parse [input] and emit a [MeasurementReading] if it matches a supported format.
     *
     * Returns true if a reading was emitted, false if parsing failed or the value
     * was non-positive.
     */
    fun submitText(input: String): Boolean {
        val reading = KeyboardMeasurementParser.parse(input, deviceId = DEVICE_ID)
            ?: return false
        return _readings.tryEmit(reading)
    }

    companion object {
        const val DEVICE_ID: String = "keyboard"
    }
}
