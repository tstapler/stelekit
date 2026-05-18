package dev.stapler.stelekit.platform.measurement

import dev.stapler.stelekit.platform.measurement.ble.NoOpBleScanner
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for [NoOpBleScanner] — Story 5.2 (compile-time stub verification).
 */
class NoOpBleScannerTest {

    @Test
    fun `NoOpBleScanner scan returns empty flow`() = runTest {
        val scanner = NoOpBleScanner()
        val devices = scanner.scan().toList()
        assertTrue(devices.isEmpty(), "NoOpBleScanner must return an empty flow, got $devices")
    }

    @Test
    fun `NoOpBleScanner can be registered in registry`() {
        MeasurementDeviceRegistry.clearForTesting()
        try {
            val scanner = NoOpBleScanner()
            MeasurementDeviceRegistry.register(scanner)
            assertTrue(MeasurementDeviceRegistry.registeredFactories().contains(scanner))
        } finally {
            MeasurementDeviceRegistry.clearForTesting()
        }
    }
}
