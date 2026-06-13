package dev.stapler.stelekit.platform.measurement

import dev.stapler.stelekit.platform.measurement.ble.NoOpBleScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [MeasurementDeviceRegistry] — Story 5.1.
 */
class MeasurementDeviceRegistryTest {

    @BeforeTest
    fun setUp() {
        MeasurementDeviceRegistry.clearForTesting()
    }

    @AfterTest
    fun tearDown() {
        MeasurementDeviceRegistry.clearForTesting()
    }

    @Test
    fun `getAllDevices returns empty flow when no factories registered`() = runTest {
        val devices = MeasurementDeviceRegistry.getAllDevices().toList()
        assertTrue(devices.isEmpty(), "Expected empty list but got $devices")
    }

    @Test
    fun `register adds factory to registered list`() {
        val factory = NoOpBleScanner()
        MeasurementDeviceRegistry.register(factory)
        assertEquals(1, MeasurementDeviceRegistry.registeredFactories().size)
    }

    @Test
    fun `registeredFactories returns all registered factories`() {
        val factory1 = NoOpBleScanner()
        val factory2 = NoOpBleScanner()
        MeasurementDeviceRegistry.register(factory1)
        MeasurementDeviceRegistry.register(factory2)
        assertEquals(2, MeasurementDeviceRegistry.registeredFactories().size)
    }

    @Test
    fun `getAllDevices merges results from multiple factories`() = runTest {
        // NoOpBleScanner returns empty flows — register the keyboard factory which emits a device.
        val keyboardFactory = dev.stapler.stelekit.platform.measurement.keyboard.KeyboardEmulationDeviceFactory()
        MeasurementDeviceRegistry.register(keyboardFactory)

        val devices = MeasurementDeviceRegistry.getAllDevices().toList()
        assertEquals(1, devices.size, "Expected 1 keyboard device from KeyboardEmulationDeviceFactory")
        assertEquals("keyboard", devices.first().deviceId)
    }

    @Test
    fun `getAllDevices with NoOpBleScanner returns empty flow`() = runTest {
        MeasurementDeviceRegistry.register(NoOpBleScanner())
        val devices = MeasurementDeviceRegistry.getAllDevices().toList()
        assertTrue(devices.isEmpty())
    }

    @Test
    fun `clearForTesting resets registry`() {
        MeasurementDeviceRegistry.register(NoOpBleScanner())
        assertEquals(1, MeasurementDeviceRegistry.registeredFactories().size)
        MeasurementDeviceRegistry.clearForTesting()
        assertEquals(0, MeasurementDeviceRegistry.registeredFactories().size)
    }
}
