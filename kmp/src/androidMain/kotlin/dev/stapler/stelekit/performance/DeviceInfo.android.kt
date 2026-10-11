package dev.stapler.stelekit.performance

import android.os.Build
import dev.stapler.stelekit.platform.SteleKitContext
import kotlinx.serialization.Serializable

@Serializable
actual class DeviceInfo actual constructor(
    actual val platform: String,
    actual val osVersion: String,
    actual val deviceModel: String,
    actual val availableRamMb: Long,
    actual val appVersion: String,
    actual val gitCommit: String
)

actual fun getDeviceInfo(): DeviceInfo = DeviceInfo(
    platform = "Android",
    osVersion = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
    availableRamMb = run {
        val rt = Runtime.getRuntime()
        (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / (1024 * 1024)
    },
    appVersion = runCatching {
        val ctx = SteleKitContext.context
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown"),
    // "git_commit_hash" is a resValue defined in :androidApp (the final app module), not :kmp
    // itself — looked up by name since :kmp's R class has no reference to it.
    gitCommit = runCatching {
        val ctx = SteleKitContext.context
        val resId = ctx.resources.getIdentifier("git_commit_hash", "string", ctx.packageName)
        if (resId != 0) ctx.getString(resId) else "unknown"
    }.getOrDefault("unknown")
)
