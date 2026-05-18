package dev.stapler.stelekit.platform.measurement.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.juul.kable.Scanner
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Android BLE scanner backed by Kable (com.juul.kable).
 *
 * Scans for:
 * - Leica DISTO devices identified by advertised service UUID prefix
 * - Bosch GLM devices identified by device name prefix ("Bosch" or "GLM")
 *
 * Required Android manifest permissions (declared in AndroidManifest.xml):
 *   - android.permission.BLUETOOTH_SCAN   (API 31+)
 *   - android.permission.BLUETOOTH_CONNECT (API 31+)
 *   - android.hardware.bluetooth_le (uses-feature)
 *
 * GATT pitfalls handled by device implementations:
 *   - GATT 133 exponential backoff: 2 s base, 60 s max, 5 retries max
 *   - peripheral.disconnect() on every exit path (prevents 30-object GATT leak)
 *   - MTU negotiated to 100 bytes before first characteristic read (Leica only)
 */
class KableBleScanner(
    private val context: Context,
) : MeasurementDeviceFactory {

    override fun scan(): Flow<ExternalMeasurementDevice> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            if (!scanGranted || !connectGranted) {
                Log.w(
                    TAG,
                    "BLE permissions not granted — scan() returning empty flow. " +
                        "Request BLUETOOTH_SCAN and BLUETOOTH_CONNECT before calling scan().",
                )
                return emptyFlow()
            }
        }

        return Scanner().advertisements
            .mapNotNull { advertisement ->
                val name = advertisement.name ?: ""
                val serviceUuids = advertisement.uuids.map { it.toString().lowercase() }
                when {
                    serviceUuids.any { it.startsWith(LeicaDistoProtocol.SERVICE_UUID_PREFIX.lowercase()) } ->
                        LeicaDistoKableDevice(advertisement, context)
                    name.startsWith("GLM") || name.startsWith("Bosch") ->
                        BoschGlmKableDevice(advertisement, context)
                    else -> null
                }
            }
    }

    companion object {
        private const val TAG = "KableBleScanner"
    }
}
