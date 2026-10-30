// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.measurement.usb

import android.content.Context
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [UsbSerialPortFinder] over Robolectric's USB host shadow with a hand-built FTDI adapter (the
 * library's prober recognizes it by vendor/product id). No real hardware: opening the port is
 * expected to fail because the shadow cannot hand out a working connection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbSerialPortFinderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private fun ftdiAdapter(): UsbDevice {
        val usbInterface = UsbInterface::class.java
            .getConstructor(Int::class.java, Int::class.java, String::class.java, Int::class.java, Int::class.java, Int::class.java)
            .newInstance(0, 0, "serial", 0xff, 0, 0)
        val configuration = UsbConfiguration::class.java
            .getConstructor(Int::class.java, String::class.java, Int::class.java, Int::class.java)
            .newInstance(1, "config", 0, 100)
        UsbConfiguration::class.java.getDeclaredMethod("setInterfaces", Array<android.os.Parcelable>::class.java)
            .apply { isAccessible = true }
            .invoke(configuration, arrayOf<android.os.Parcelable>(usbInterface))
        val ctor = UsbDevice::class.java.declaredConstructors.first { it.parameterTypes.size == 11 && it.isSynthetic.not() }
            .apply { isAccessible = true }
        val readerType = Class.forName("android.hardware.usb.IUsbSerialReader")
        val reader = java.lang.reflect.Proxy.newProxyInstance(readerType.classLoader, arrayOf(readerType)) { _, _, _ -> "serial" }
        return ctor.newInstance(
            "/dev/bus/usb/001/002", 0x0403, 0x6001, 0, 0, 0, "FTDI", "Laser meter", "1.0",
            arrayOf(configuration), reader,
        ) as UsbDevice
    }

    @Test
    fun noAdapterAttached_findsNothing() {
        assertEquals(emptyList(), UsbSerialPortFinder(context).findPorts())
    }

    @Test
    fun adapterWithoutPermission_isOmittedAndPermissionIsRequested() {
        val device = ftdiAdapter()
        val shadow = shadowOf(usbManager)
        shadow.addOrUpdateUsbDevice(device, false)

        assertEquals(emptyList(), UsbSerialPortFinder(context).findPorts())
        assertTrue(!usbManager.hasPermission(device))
    }

    @Test
    fun adapterWithPermission_isReportedWithItsNameAndFailsLoudlyWhenItCannotBeOpened() {
        val device = ftdiAdapter()
        shadowOf(usbManager).addOrUpdateUsbDevice(device, true)

        val port = UsbSerialPortFinder(context).findPorts().single()

        assertEquals("/dev/bus/usb/001/002", port.id)
        assertEquals("Laser meter", port.name)
        assertFailsWith<Exception> { port.open() }
    }
}
