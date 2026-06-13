package dev.stapler.stelekit.platform.measurement.keyboard

import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [MeasurementDeviceFactory] that immediately emits a single [KeyboardEmulationDevice].
 *
 * Unlike BLE factories, there is no scanning involved — the keyboard device is always
 * "available". Register this factory in [dev.stapler.stelekit.platform.measurement.MeasurementDeviceRegistry]
 * at app startup so the keyboard mode is always accessible as a fallback.
 *
 * The emitted [KeyboardEmulationDevice] instance is retained as a singleton so that
 * all callers that collect [scan] receive the same object and can call [KeyboardEmulationDevice.submitText]
 * on it directly.
 */
class KeyboardEmulationDeviceFactory : MeasurementDeviceFactory {

    // Singleton device — one keyboard device per factory instance.
    val device: KeyboardEmulationDevice = KeyboardEmulationDevice()

    override fun scan(): Flow<ExternalMeasurementDevice> = flow {
        emit(device)
    }
}
