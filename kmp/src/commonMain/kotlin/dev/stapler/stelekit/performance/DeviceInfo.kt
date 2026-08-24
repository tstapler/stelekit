package dev.stapler.stelekit.performance


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

/** Returns a compact heap-usage string (e.g. "heap:42/256MB") for diagnostic logging. No-op on non-JVM platforms. */
expect fun heapSummary(): String
