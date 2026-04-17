package dev.stapler.stelekit.performance

import kotlinx.serialization.Serializable

expect class DeviceInfo(
    platform: String,
    osVersion: String,
    deviceModel: String,
    availableRamMb: Long,
    appVersion: String
) {
    val platform: String
    val osVersion: String
    val deviceModel: String
    val availableRamMb: Long
    val appVersion: String
}

expect fun getDeviceInfo(): DeviceInfo
