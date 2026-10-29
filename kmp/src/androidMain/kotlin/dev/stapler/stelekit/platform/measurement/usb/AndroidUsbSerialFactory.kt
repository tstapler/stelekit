package dev.stapler.stelekit.platform.measurement.usb

import android.content.Context
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [MeasurementDeviceFactory] for laser meters on a USB-OTG serial adapter
 * (`com.github.mik3y:usb-serial-for-android`, MIT).
 *
 * Each serial port the [finder] reports becomes one [UsbSerialMeasurementDevice]. With no adapter
 * attached, no permission, or any failure while probing, the flow completes empty rather than
 * throwing. When Android has not yet granted USB permission the finder asks for it and omits
 * the device; the user re-scans (the panel's "Try Again") once they have accepted the system dialog.
 */
class AndroidUsbSerialFactory(
    context: Context,
    private val finder: SerialMeterPortFinder = UsbSerialPortFinder(context),
) : MeasurementDeviceFactory {

    override fun scan(): Flow<ExternalMeasurementDevice> = flow {
        val ports = try {
            finder.findPorts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("USB serial scan failed: ${e.message}")
            emptyList()
        }
        ports.forEach { emit(UsbSerialMeasurementDevice(it)) }
    }

    companion object {
        /** Action for the USB-permission PendingIntent; see [UsbSerialPortFinder]. */
        const val USB_PERMISSION_ACTION: String = "dev.stapler.stelekit.USB_PERMISSION"
        private val logger = Logger("AndroidUsbSerialFactory")
    }
}
