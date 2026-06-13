package dev.stapler.stelekit.platform.measurement.ble

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service stub for managing BLE measurement connections on Android API 31+.
 *
 * Android requires a foreground service with type `connectedDevice` for BLE GATT connections
 * initiated while the app is in the background (API 31+). This stub satisfies the manifest
 * registration requirement and the compilation dependency without containing BLE logic.
 *
 * To activate:
 *   1. Add Kable dependency to build.gradle.kts.
 *   2. Inject [KableBleScanner] and the active [ExternalMeasurementDevice] into this service.
 *   3. Show a persistent notification with "Measuring…" text when a device is CONNECTED.
 *   4. Call [stopForeground] and [stopSelf] when the last device disconnects.
 *
 * Notification requirements (Android 13+):
 *   - POST_NOTIFICATIONS permission must be granted before starting this service.
 *   - Notification must use a dedicated measurement notification channel.
 *   - Notification is auto-dismissed on [DeviceConnectionState.DISCONNECTED].
 *
 * The service is declared in AndroidManifest.xml with:
 *   `android:foregroundServiceType="connectedDevice"`
 */
class AndroidMeasurementForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO(BLE): Start foreground notification and connect active BLE device.
        // val notification = buildMeasuringNotification()
        // startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // TODO(BLE): Disconnect active BLE device and release Kable scanner.
    }

    companion object {
        const val NOTIFICATION_ID: Int = 9001
    }
}
