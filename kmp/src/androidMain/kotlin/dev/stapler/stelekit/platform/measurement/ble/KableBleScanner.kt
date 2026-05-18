package dev.stapler.stelekit.platform.measurement.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementDeviceFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Android BLE scanner backed by Kable (com.juul.kable).
 *
 * NOTE: Kable is NOT currently on the classpath. This stub returns an empty Flow
 * unconditionally. To enable real BLE scanning:
 *   1. Add `implementation("com.juul.kable:core:<version>")` to androidMain dependencies
 *      in kmp/build.gradle.kts.
 *   2. Replace the [scan] body with:
 *      ```kotlin
 *      return Scanner { }.advertisements
 *          .mapNotNull { advertisement ->
 *              LeicaDistoDeviceFactory.fromAdvertisement(advertisement, context)
 *                  ?: BoschGlmDeviceFactory.fromAdvertisement(advertisement, context)
 *          }
 *      ```
 *   3. Remove the permission guard in [scan] (Kable handles it internally via its own
 *      Scanner builder).
 *
 * Required Android manifest permissions (already declared):
 *   - android.permission.BLUETOOTH_SCAN   (API 31+)
 *   - android.permission.BLUETOOTH_CONNECT (API 31+)
 *   - android.permission.BLUETOOTH_ADVERTISE (API 31+)
 *   - android.hardware.bluetooth_le (uses-feature)
 *
 * GATT pitfalls mitigated by the full implementation (not this stub):
 *   - GATT 133 exponential backoff: min 2s, max 60s, max 5 retries
 *   - gatt.disconnect() + gatt.close() on every exit path (prevents 30-object leak)
 *   - MTU negotiated to 100 bytes before first characteristic read
 */
class KableBleScanner(
    private val context: Context,
) : MeasurementDeviceFactory {

    override fun scan(): Flow<ExternalMeasurementDevice> {
        // Guard: require BLE permissions on API 31+
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
                // Permissions not granted — return empty flow.
                // The UI layer must request permissions before calling scan().
                return emptyFlow()
            }
        }

        // TODO(BLE): Replace with Kable Scanner once dependency is added.
        //   val scanner = Scanner {
        //       filters { match { services = listOf(LeicaDistoProtocol.SERVICE_UUID) } }
        //   }
        //   return scanner.advertisements.mapNotNull { adv ->
        //       LeicaDistoDeviceFactory.fromAdvertisement(adv, context)
        //           ?: BoschGlmDeviceFactory.fromAdvertisement(adv, context)
        //   }
        return emptyFlow()
    }
}
