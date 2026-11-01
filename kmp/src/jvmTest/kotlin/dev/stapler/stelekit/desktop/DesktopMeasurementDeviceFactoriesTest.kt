// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.desktop

import dev.stapler.stelekit.platform.measurement.ble.NoOpBleScanner
import dev.stapler.stelekit.platform.measurement.keyboard.KeyboardEmulationDevice
import dev.stapler.stelekit.platform.measurement.keyboard.KeyboardEmulationDeviceFactory
import dev.stapler.stelekit.wiring.ObservableEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 7.3a: executes the list `main()` registers with `MeasurementDeviceRegistry`. jvmTest-reason:
 * the function lives in the desktop entry file; no display is needed.
 */
class DesktopMeasurementDeviceFactoriesTest {

    @Test
    fun desktopRoot_registersKeyboardFallbackAndNoBleStub() {
        assertEquals(
            setOf(KeyboardEmulationDeviceFactory::class, NoOpBleScanner::class),
            defaultMeasurementDeviceFactories().map { it::class }.toSet(),
        )
    }

    @Test
    fun keyboardFallback_emitsItsDevice() = runBlocking {
        val factories = defaultMeasurementDeviceFactories()
        val device = factories.filterIsInstance<KeyboardEmulationDeviceFactory>().single().scan().first()

        ObservableEffect.assert("desktop-keyboard-device-emitted") {
            assertTrue(device is KeyboardEmulationDevice)
            assertEquals(KeyboardEmulationDevice.DEVICE_ID, device.deviceId)
        }
    }
}
