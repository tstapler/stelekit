package dev.stapler.stelekit.platform.measurement.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceFactory
import dev.stapler.stelekit.platform.measurement.ble.NoOpBleScanner
import kotlinx.coroutines.flow.Flow

/**
 * Android USB serial (OTG) factory stub.
 *
 * NOTE: `usb-serial-for-android` (kai-morich fork) is NOT currently on the classpath.
 * This stub delegates to [NoOpBleScanner] and returns an empty Flow.
 *
 * To enable USB serial support:
 *   1. Add to androidMain in kmp/build.gradle.kts:
 *      `implementation("com.github.kai-morich:usb-serial-for-android:<version>")`
 *   2. Confirm LGPL 2.1 compliance via dynamic `.aar` linking (confirmed acceptable per plan ADR-004).
 *   3. Replace the [scan] body with:
 *      ```kotlin
 *      val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
 *      val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
 *      return flow {
 *          drivers.forEach { driver ->
 *              requestPermission(usbManager, driver.device)
 *              emit(AndroidUsbSerialDevice(driver, usbManager, context))
 *          }
 *      }
 *      ```
 *   4. Implement `AndroidUsbSerialDevice` wrapping the driver's serial port in a Flow.
 *
 * USB_PERMISSION broadcast action for permission request/response:
 *   [USB_PERMISSION_ACTION]
 *
 * To request permission before opening a device, broadcast a [PendingIntent] via:
 *   `usbManager.requestPermission(usbDevice, pendingIntent)`
 * and receive the response in a [BroadcastReceiver] registered for [USB_PERMISSION_ACTION].
 */
class AndroidUsbSerialFactory(
    @Suppress("UnusedPrivateMember")
    private val context: Context,
) : MeasurementDeviceFactory {

    /**
     * Custom action string for the USB_PERMISSION PendingIntent broadcast.
     */
    companion object {
        const val USB_PERMISSION_ACTION: String =
            "dev.stapler.stelekit.USB_PERMISSION"
    }

    // TODO(USB): Replace with real implementation once usb-serial-for-android is added.
    private val noOp = NoOpBleScanner()

    override fun scan(): Flow<ExternalMeasurementDevice> = noOp.scan()
}
