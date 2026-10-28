// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.platform.measurement.ble.KableBleScanner
import dev.stapler.stelekit.platform.measurement.keyboard.KeyboardEmulationDevice
import dev.stapler.stelekit.platform.measurement.keyboard.KeyboardEmulationDeviceFactory
import dev.stapler.stelekit.platform.measurement.usb.AndroidUsbSerialFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 7.3a: executes the same [defaultMeasurementDeviceFactories] list
 * `SteleKitApplication.onCreate` registers, so dropping the keyboard fallback or the USB factory
 * from the Android root fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MeasurementDeviceFactoriesTest {

    private val factories = defaultMeasurementDeviceFactories(ApplicationProvider.getApplicationContext())

    @Test
    fun androidRoot_registersBleKeyboardAndUsbSerial() {
        assertEquals(
            setOf(KableBleScanner::class, KeyboardEmulationDeviceFactory::class, AndroidUsbSerialFactory::class),
            factories.map { it::class }.toSet(),
        )
    }

    @Test
    fun keyboardFallback_emitsItsDevice() = runBlocking {
        val device = factories.filterIsInstance<KeyboardEmulationDeviceFactory>().single().scan().first()

        assertTrue(device is KeyboardEmulationDevice)
        assertEquals(KeyboardEmulationDevice.DEVICE_ID, device.deviceId)
    }
}
