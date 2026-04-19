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
    platform = "Browser (wasmJs)",
    osVersion = "unknown",
    deviceModel = "unknown",
    availableRamMb = 0L,
    appVersion = "dev"
)
