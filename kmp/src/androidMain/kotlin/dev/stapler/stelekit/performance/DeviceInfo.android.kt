package dev.stapler.stelekit.performance

import android.os.Build
import kotlinx.serialization.Serializable

@Serializable
actual class DeviceInfo actual constructor(
    actual val platform: String,
    actual val osVersion: String,
    actual val deviceModel: String,
    actual val availableRamMb: Long,
    actual val appVersion: String
)

actual fun getDeviceInfo(): DeviceInfo = DeviceInfo(
    platform = "Android",
    osVersion = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
    availableRamMb = run {
        val rt = Runtime.getRuntime()
        (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / (1024 * 1024)
    },
    appVersion = "1.0.0"
)
