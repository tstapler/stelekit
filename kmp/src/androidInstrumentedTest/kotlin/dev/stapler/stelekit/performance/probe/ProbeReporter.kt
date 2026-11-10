// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import android.content.Context
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One scenario's result: flat bare-name metrics plus the context the budget checker needs. */
data class ProbeRecord(
    val scenario: String,
    val metrics: Map<String, Double>,
    val device: DeviceProfile,
    val scenarioStartMs: Long,
    val exitReasons: List<ExitRecord> = emptyList(),
    val pid: Int = Process.myPid(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("scenario", scenario)
        .put("pid", pid)
        .put("scenarioStartMs", scenarioStartMs)
        .put("device", device.toJson())
        .put("metrics", JSONObject().also { m -> metrics.forEach { (k, v) -> m.put(k, v) } })
        .put(
            "exitReasons",
            JSONArray().also { a ->
                exitReasons.forEach {
                    a.put(JSONObject().put("reason", it.reason).put("pid", it.pid).put("timestamp", it.timestamp))
                }
            },
        )
}

/**
 * Output channel (plan B.4a). CI reads `ANDROID_RESOURCE` logcat lines through
 * `scripts/android-benchmark-summarize.py`. A copy also lands in the app's external files dir
 * (`probe/<scenario>.json`) so a local run can `adb pull` it without logcat; the owner cannot run
 * `adb logcat` on their machine (CLAUDE.md, Android debugging).
 */
object ProbeReporter {
    const val LOG_TAG = "ANDROID_RESOURCE"

    fun report(context: Context, record: ProbeRecord) {
        val json = record.toJson().toString()
        // Logcat caps a line near 4 KB; a record is a few hundred bytes. The prefix matches the summarizer regex.
        Log.i(LOG_TAG, "$LOG_TAG: $json")
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "probe").apply { mkdirs() }
            File(dir, "${record.scenario}.json").writeText(json)
        }.onFailure { Log.w(LOG_TAG, "could not write probe file: $it") }
    }
}
