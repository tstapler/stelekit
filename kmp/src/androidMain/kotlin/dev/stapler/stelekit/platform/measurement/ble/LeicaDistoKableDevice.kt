package dev.stapler.stelekit.platform.measurement.ble

import android.content.Context
import android.content.Intent
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.juul.kable.Advertisement
import com.juul.kable.AndroidPeripheral
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.peripheral
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.measurement.DeviceConnectionState
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
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
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.min

/**
 * [ExternalMeasurementDevice] implementation for Leica DISTO laser rangefinders.
 *
 * Uses Kable for GATT connection management. Subscribes to the measurement notification
 * characteristic and writes an ACK within 2 seconds per protocol requirement.
 *
 * GATT error 133 retry logic: exponential backoff starting at 2 s, capped at 60 s,
 * max 5 attempts before emitting [DeviceConnectionState.ERROR].
 */
class LeicaDistoKableDevice(
    private val advertisement: Advertisement,
    private val context: Context,
) : ExternalMeasurementDevice {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peripheral = scope.peripheral(advertisement)

    private val _connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<DeviceConnectionState> = _connectionState

    private val _measurements = MutableSharedFlow<MeasurementReading>(extraBufferCapacity = 16)

    override val deviceName: String
        get() = advertisement.name ?: "Leica DISTO"

    override val deviceId: String
        get() = advertisement.identifier

    override fun measurementFlow(): Flow<MeasurementReading> = _measurements.asSharedFlow()

    override suspend fun connect(): Either<DomainError, Unit> {
        context.startForegroundService(
            Intent(context, AndroidMeasurementForegroundService::class.java),
        )
        _connectionState.value = DeviceConnectionState.CONNECTING
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            try {
                peripheral.connect()
                // requestMtu is AndroidPeripheral-specific
                (peripheral as? AndroidPeripheral)?.requestMtu(100)
                _connectionState.value = DeviceConnectionState.CONNECTED
                launchMeasurementLoop()
                return Unit.right()
            } catch (e: IOException) {
                val msg = e.message ?: ""
                if (msg.contains("133") || msg.contains("GATT")) {
                    attempt++
                    if (attempt >= MAX_RETRIES) {
                        _connectionState.value = DeviceConnectionState.ERROR
                        return DomainError.BleError.Gatt133(
                            attempts = MAX_RETRIES,
                            message = e.message ?: "GATT error",
                        ).left()
                    }
                    val backoffMs = min(BACKOFF_BASE_MS shl (attempt - 1), BACKOFF_MAX_MS)
                    delay(backoffMs.toLong())
                    try { peripheral.disconnect() } catch (de: Exception) { if (de is CancellationException) throw de }
                } else {
                    _connectionState.value = DeviceConnectionState.ERROR
                    return DomainError.BleError.ConnectionFailed(e.message ?: "connect failed").left()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _connectionState.value = DeviceConnectionState.ERROR
                return DomainError.BleError.ConnectionFailed(e.message ?: "connect failed").left()
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
                    if (reading != null) {
                        _measurements.tryEmit(reading)
                    }
                    // Must ACK within 2 seconds regardless of parse result.
                    try {
                        peripheral.write(ackCharacteristic, LeicaDistoProtocol.ACK_BYTES, WriteType.WithResponse)
                    } catch (ae: Exception) {
                        if (ae is CancellationException) throw ae
                        /* best effort — don't crash the observe loop */
                    }
                }
            } catch (le: Exception) {
                if (le is CancellationException) throw le
                _connectionState.value = DeviceConnectionState.ERROR
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 5
        private const val BACKOFF_BASE_MS = 2_000
        private const val BACKOFF_MAX_MS = 60_000
    }
}
