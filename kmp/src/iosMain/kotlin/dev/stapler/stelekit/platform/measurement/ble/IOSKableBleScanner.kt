package dev.stapler.stelekit.platform.measurement.ble

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.peripheral
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.measurement.DeviceConnectionState
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceFactory
import dev.stapler.stelekit.platform.measurement.MeasurementReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * iOS BLE scanner backed by Kable (CoreBluetooth wrapper).
 *
 * iOS does not require runtime permission checks for BLE scanning — CoreBluetooth
 * handles authorization through Info.plist `NSBluetoothAlwaysUsageDescription`.
 *
 * Scans for:
 * - Leica DISTO devices identified by advertised service UUID prefix
 * - Bosch GLM devices identified by device name prefix ("Bosch" or "GLM")
 */
class IOSKableBleScanner : MeasurementDeviceFactory {

    override fun scan(): Flow<ExternalMeasurementDevice> =
        Scanner().advertisements
            .mapNotNull { advertisement ->
                val name = advertisement.name ?: ""
                val serviceUuids = advertisement.uuids.map { it.toString().lowercase() }
                when {
                    serviceUuids.any { it.startsWith(LeicaDistoProtocol.SERVICE_UUID_PREFIX.lowercase()) } ->
                        IOSLeicaDistoKableDevice(advertisement)
                    name.startsWith("GLM") || name.startsWith("Bosch") ->
                        IOSBoschGlmKableDevice(advertisement)
                    else -> null
                }
            }
}

// ── iOS Leica DISTO device ────────────────────────────────────────────────────

private class IOSLeicaDistoKableDevice(
    private val advertisement: Advertisement,
) : ExternalMeasurementDevice {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peripheral = scope.peripheral(advertisement)

    private val _connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<DeviceConnectionState> = _connectionState

    private val _measurements = MutableSharedFlow<MeasurementReading>(extraBufferCapacity = 16)

    override val deviceName: String get() = advertisement.name ?: "Leica DISTO"
    override val deviceId: String get() = advertisement.identifier

    override fun measurementFlow(): Flow<MeasurementReading> = _measurements.asSharedFlow()

    override suspend fun connect(): Either<DomainError, Unit> {
        _connectionState.value = DeviceConnectionState.CONNECTING
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            try {
                peripheral.connect()
                peripheral.requestMtu(100)
                _connectionState.value = DeviceConnectionState.CONNECTED
                launchMeasurementLoop()
                return Unit.right()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = e.message ?: ""
                if (msg.contains("133") || msg.contains("GATT") || msg.contains("CBError")) {
                    attempt++
                    if (attempt >= MAX_RETRIES) {
                        _connectionState.value = DeviceConnectionState.ERROR
                        return DomainError.BleError.Gatt133(
                            attempts = MAX_RETRIES,
                            message = e.message ?: "CoreBluetooth error",
                        ).left()
                    }
                    val backoffMs = min(BACKOFF_BASE_MS shl (attempt - 1), BACKOFF_MAX_MS)
                    delay(backoffMs.toLong())
                    try { peripheral.disconnect() } catch (de: Exception) { if (de is CancellationException) throw de }
                } else {
                    _connectionState.value = DeviceConnectionState.ERROR
                    return DomainError.BleError.ConnectionFailed(e.message ?: "connect failed").left()
                }
            }
        }
        _connectionState.value = DeviceConnectionState.ERROR
        return DomainError.BleError.ConnectionFailed("Max retries exceeded").left()
    }

    override suspend fun disconnect() {
        try {
            peripheral.disconnect()
        } finally {
            _connectionState.value = DeviceConnectionState.DISCONNECTED
        }
    }

    private fun launchMeasurementLoop() {
        scope.launch {
            try {
                val measureCharacteristic = characteristicOf(
                    service = LeicaDistoProtocol.SERVICE_UUID,
                    characteristic = LeicaDistoProtocol.MEASUREMENT_CHARACTERISTIC_UUID,
                )
                val ackCharacteristic = characteristicOf(
                    service = LeicaDistoProtocol.SERVICE_UUID,
                    characteristic = LeicaDistoProtocol.ACK_CHARACTERISTIC_UUID,
                )
                peripheral.observe(measureCharacteristic).collect { bytes: ByteArray ->
                    val reading = LeicaDistoProtocol.parseNotification(bytes, deviceId)
                    if (reading != null) _measurements.tryEmit(reading)
                    try {
                        peripheral.write(ackCharacteristic, LeicaDistoProtocol.ACK_BYTES, WriteType.WithResponse)
                    } catch (ae: Exception) { if (ae is CancellationException) throw ae }
                }
            } catch (le: Exception) {
                if (le is CancellationException) throw le
                _connectionState.value = DeviceConnectionState.ERROR
            }
        }
    }

    private companion object {
        const val MAX_RETRIES = 5
        const val BACKOFF_BASE_MS = 2_000
        const val BACKOFF_MAX_MS = 60_000
    }
}

// ── iOS Bosch GLM device ──────────────────────────────────────────────────────

private class IOSBoschGlmKableDevice(
    private val advertisement: Advertisement,
) : ExternalMeasurementDevice {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peripheral = scope.peripheral(advertisement)

    private val _connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<DeviceConnectionState> = _connectionState

    private val _measurements = MutableSharedFlow<MeasurementReading>(extraBufferCapacity = 16)

    override val deviceName: String get() = advertisement.name ?: "Bosch GLM"
    override val deviceId: String get() = advertisement.identifier

    override fun measurementFlow(): Flow<MeasurementReading> = _measurements.asSharedFlow()

    override suspend fun connect(): Either<DomainError, Unit> {
        _connectionState.value = DeviceConnectionState.CONNECTING
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            try {
                peripheral.connect()
                _connectionState.value = DeviceConnectionState.CONNECTED
                launchMeasurementLoop()
                return Unit.right()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = e.message ?: ""
                if (msg.contains("133") || msg.contains("GATT") || msg.contains("CBError")) {
                    attempt++
                    if (attempt >= MAX_RETRIES) {
                        _connectionState.value = DeviceConnectionState.ERROR
                        return DomainError.BleError.Gatt133(
                            attempts = MAX_RETRIES,
                            message = e.message ?: "CoreBluetooth error",
                        ).left()
                    }
                    val backoffMs = min(BACKOFF_BASE_MS shl (attempt - 1), BACKOFF_MAX_MS)
                    delay(backoffMs.toLong())
                    try { peripheral.disconnect() } catch (de: Exception) { if (de is CancellationException) throw de }
                } else {
                    _connectionState.value = DeviceConnectionState.ERROR
                    return DomainError.BleError.ConnectionFailed(e.message ?: "connect failed").left()
                }
            }
        }
        _connectionState.value = DeviceConnectionState.ERROR
        return DomainError.BleError.ConnectionFailed("Max retries exceeded").left()
    }

    override suspend fun disconnect() {
        try {
            peripheral.disconnect()
        } finally {
            _connectionState.value = DeviceConnectionState.DISCONNECTED
        }
    }

    private fun launchMeasurementLoop() {
        scope.launch {
            try {
                val sppCharacteristic = characteristicOf(
                    service = BoschGlmProtocol.SPP_SERVICE_UUID,
                    characteristic = SPP_CHARACTERISTIC_UUID,
                )
                peripheral.observe(sppCharacteristic).collect { bytes: ByteArray ->
                    val text = bytes.decodeToString()
                    val reading = BoschGlmProtocol.parseResponse(text, deviceId)
                    if (reading != null) _measurements.tryEmit(reading)
                }
            } catch (le: Exception) {
                if (le is CancellationException) throw le
                _connectionState.value = DeviceConnectionState.ERROR
            }
        }
    }

    private companion object {
        const val MAX_RETRIES = 5
        const val BACKOFF_BASE_MS = 2_000
        const val BACKOFF_MAX_MS = 60_000

        // SPP data characteristic — same UUID as the SPP service for GLM's BLE transport
        const val SPP_CHARACTERISTIC_UUID = "00001101-0000-1000-8000-00805f9b34fb"
    }
}
