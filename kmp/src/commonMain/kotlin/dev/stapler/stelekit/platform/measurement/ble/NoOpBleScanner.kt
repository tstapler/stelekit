package dev.stapler.stelekit.platform.measurement.ble

import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * No-op [MeasurementDeviceFactory] used on platforms where BLE scanning is not
 * available (JVM desktop, WASM) or when Kable is not yet on the classpath.
 *
 * Returns an empty [Flow] — no devices are ever discovered.
 * Register this factory in [dev.stapler.stelekit.platform.measurement.MeasurementDeviceRegistry]
 * on platforms that do not have a real BLE implementation.
 */
class NoOpBleScanner : MeasurementDeviceFactory {
    override fun scan(): Flow<ExternalMeasurementDevice> = emptyFlow()
}
