package dev.stapler.stelekit.platform.measurement

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Connection lifecycle state for an [ExternalMeasurementDevice].
 */
enum class DeviceConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/**
 * A single distance reading emitted by an [ExternalMeasurementDevice].
 *
 * [valueMeters] is the calibrated distance in SI meters.
 * [deviceId] identifies the source device (BLE address, "keyboard", etc.).
 * [rawBytes] is the raw protocol payload, preserved for diagnostics — null for
 * devices that emit parsed values directly (e.g. keyboard emulation).
 */
data class MeasurementReading(
    val valueMeters: Double,
    val deviceId: String,
    val rawBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeasurementReading) return false
        return valueMeters == other.valueMeters &&
            deviceId == other.deviceId &&
            (rawBytes === other.rawBytes || rawBytes != null && other.rawBytes != null && rawBytes.contentEquals(other.rawBytes))
    }

    override fun hashCode(): Int {
        var result = valueMeters.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + (rawBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Abstraction over any external distance-measurement device.
 *
 * Implementations include BLE laser rangefinders (Leica DISTO, Bosch GLM),
 * BT HID keyboard-emulation mode, and USB serial (OTG).
 *
 * All connection operations return [Either] so callers can handle errors
 * without exceptions propagating across coroutine boundaries.
 */
interface ExternalMeasurementDevice {
    /**
     * Human-readable device name (e.g. "Leica DISTO X3", "Keyboard", "Bosch GLM 50 C").
     */
    val deviceName: String

    /**
     * Stable identifier — BLE MAC address for BLE devices, "keyboard" for HID mode,
     * USB device path for serial devices.
     */
    val deviceId: String

    /**
     * Live connection state. Callers should collect this to update UI.
     */
    val connectionState: StateFlow<DeviceConnectionState>

    /**
     * Connect to (or activate) the device.
     *
     * Returns [Either.Right] on success. BLE devices perform GATT connection here;
     * keyboard and USB implementations may be no-ops that immediately succeed.
     */
    suspend fun connect(): Either<DomainError, Unit>

    /**
     * Disconnect from the device and release all resources.
     *
     * Must be called on every exit path — including error paths — to avoid GATT
     * object leaks on Android (30-object BLE stack limit).
     */
    suspend fun disconnect()

    /**
     * Hot flow of distance readings emitted by the device.
     *
     * The flow is active as long as the device is [DeviceConnectionState.CONNECTED].
     * Collect from a coroutine with an appropriate lifecycle scope.
     */
    fun measurementFlow(): Flow<MeasurementReading>
}

/**
 * Factory that can scan for and produce [ExternalMeasurementDevice] instances.
 *
 * Each transport layer (BLE, USB, keyboard) supplies one implementation.
 */
interface MeasurementDeviceFactory {
    /**
     * Emit discovered devices as they are found.
     *
     * BLE factories scan via GATT advertisement; keyboard factory emits a singleton
     * immediately; USB factory responds to [UsbManager.ACTION_USB_DEVICE_ATTACHED].
     *
     * The flow terminates when scanning is stopped (scope cancellation).
     */
    fun scan(): Flow<ExternalMeasurementDevice>
}
