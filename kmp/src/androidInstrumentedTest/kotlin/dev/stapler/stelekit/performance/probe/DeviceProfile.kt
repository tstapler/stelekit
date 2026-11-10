// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * What the run happened on (ADR-009). Rides on every [ProbeRecord] so a number is never read
 * without its heap class. [invalidDevice] is true when the guest RAM differs from the requested
 * `expectedRamMb` by more than [RAM_TOLERANCE]; the budget checker then refuses to fail memory metrics.
 */
data class DeviceProfile(
    val memoryClass: Int,
    val largeHeap: Boolean,
    val sdkInt: Int,
    val memTotalKb: Long,
    val expectedRamMb: Long?,
    val coilVersion: String = COIL_VERSION,
) {
    val invalidDevice: Boolean
        get() = expectedRamMb != null && abs(memTotalKb / KB_PER_MB - expectedRamMb) > expectedRamMb * RAM_TOLERANCE

    fun toJson(): JSONObject = JSONObject()
        .put("memoryClass", memoryClass)
        .put("largeHeap", largeHeap)
        .put("sdkInt", sdkInt)
        .put("memTotalKb", memTotalKb)
        .put("coilVersion", coilVersion)
        .put("invalidDevice", invalidDevice)

    companion object {
        /** Keep in step with the Coil dependency; `CoilSeamTest` fails on a bump that changes the seam. */
        const val COIL_VERSION = "3.2.0"
        private const val RAM_TOLERANCE = 0.10
        private const val KB_PER_MB = 1024

        /** [expectedRamMb] comes from the instrumentation argument `expectedRamMb` (the AVD's `ram-size`). */
        fun read(context: Context, expectedRamMb: Long?): DeviceProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return DeviceProfile(
                memoryClass = am.memoryClass,
                largeHeap = context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP != 0,
                sdkInt = Build.VERSION.SDK_INT,
                memTotalKb = memTotalKb(),
                expectedRamMb = expectedRamMb,
            )
        }

        private fun memTotalKb(): Long = runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.first { it.startsWith("MemTotal:") }.filter { it.isDigit() }.toLong()
            }
        }.getOrDefault(0L)
    }
}
