package dev.stapler.stelekit.performance

import kotlinx.serialization.Serializable
import platform.Foundation.NSBundle

@Serializable
actual class DeviceInfo actual constructor(
    actual val platform: String,
    actual val osVersion: String,
    actual val deviceModel: String,
    actual val availableRamMb: Long,
    actual val appVersion: String
)

actual fun getDeviceInfo(): DeviceInfo = DeviceInfo(
    platform = "iOS",
    osVersion = "unknown",
    deviceModel = "unknown",
    availableRamMb = 0L,
    appVersion = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown"
)
