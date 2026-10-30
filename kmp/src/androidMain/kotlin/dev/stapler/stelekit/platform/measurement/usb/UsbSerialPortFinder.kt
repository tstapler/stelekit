package dev.stapler.stelekit.platform.measurement.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Finds serial adapters on the USB host bus via `usb-serial-for-android`. Hardware-only code: the
 * parsing and flow behavior above it is covered through [SerialMeterPort] fakes.
 *
 * Devices without USB permission trigger the system permission dialog and are left out of the
 * result; the next scan sees them once the user has accepted.
 */
class UsbSerialPortFinder(private val context: Context) : SerialMeterPortFinder {

    override fun findPorts(): List<SerialMeterPort> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).mapNotNull { driver ->
            if (usbManager.hasPermission(driver.device)) {
                driver.ports.firstOrNull()?.let { LibraryPort(usbManager, it) }
            } else {
                requestPermission(usbManager, driver.device)
                null
            }
        }
    }

    private fun requestPermission(usbManager: UsbManager, device: android.hardware.usb.UsbDevice) {
        val intent = Intent(AndroidUsbSerialFactory.USB_PERMISSION_ACTION).setPackage(context.packageName)
        // FLAG_MUTABLE: the system fills in EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED.
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pending)
    }

    private class LibraryPort(
        private val usbManager: UsbManager,
        private val port: UsbSerialPort,
    ) : SerialMeterPort {
        override val id: String = port.device.deviceName
        override val name: String = port.device.productName ?: "USB serial meter"

        override fun open() {
            val connection = usbManager.openDevice(port.device)
                ?: error("could not open USB device ${port.device.deviceName}")
            port.open(connection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }

        override fun read(buffer: ByteArray, timeoutMs: Int): Int = port.read(buffer, timeoutMs)

        override fun close() = port.close()
    }

    private companion object {
        // Common default for serial laser meters; adjustable per device once one is in hand (OD-4).
        const val BAUD_RATE = 9600
    }
}
