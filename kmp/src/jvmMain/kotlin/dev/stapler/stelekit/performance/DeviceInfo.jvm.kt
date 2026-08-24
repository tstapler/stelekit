package dev.stapler.stelekit.performance

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
    platform = "JVM / Desktop",
    osVersion = System.getProperty("os.version") ?: "unknown",
    deviceModel = System.getProperty("os.arch") ?: "unknown",
    availableRamMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
    appVersion = System.getProperty("app.version") ?: "dev"
)
