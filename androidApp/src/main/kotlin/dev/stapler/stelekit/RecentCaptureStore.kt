// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One share the overlay saved on Back; [noticed] is set once the next-start notice has been shown. */
data class RecentCapture(
    val captureId: String,
    val graphId: String,
    val graphName: String,
    val atMs: Long,
    val text: String,
    val journalPage: String,
    val undone: Boolean = false,
    val noticed: Boolean = false,
)

/** The last [MAX] Back auto-saves, app-private. Backs the next-start "Last share saved to ..." notice. */
class RecentCaptureStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<RecentCapture> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).toRecord() }
    } catch (_: org.json.JSONException) {
        emptyList()
    }

    @Synchronized
    fun add(record: RecentCapture) {
        write((listOf(record) + all().filterNot { it.captureId == record.captureId }).take(MAX))
    }

    @Synchronized
    fun markUndone(captureId: String) = update(captureId) { it.copy(undone = true) }

    @Synchronized
    fun markNoticed(captureId: String) = update(captureId) { it.copy(noticed = true) }

    /** The newest record, if it is still undoable and not yet shown ("still the last one added"). */
    fun pendingNotice(): RecentCapture? = all().firstOrNull()?.takeIf { !it.undone && !it.noticed }

    private fun update(captureId: String, change: (RecentCapture) -> RecentCapture) =
        write(all().map { if (it.captureId == captureId) change(it) else it })

    private fun write(records: List<RecentCapture>) {
        val arr = JSONArray()
        records.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun RecentCapture.toJson() = JSONObject()
        .put("captureId", captureId).put("graphId", graphId).put("graphName", graphName)
        .put("atMs", atMs).put("text", text).put("journalPage", journalPage)
        .put("undone", undone).put("noticed", noticed)

    private fun JSONObject.toRecord() = RecentCapture(
        captureId = getString("captureId"),
        graphId = getString("graphId"),
        graphName = getString("graphName"),
        atMs = getLong("atMs"),
        text = getString("text"),
        journalPage = optString("journalPage"),
        undone = optBoolean("undone"),
        noticed = optBoolean("noticed"),
    )

    companion object {
        const val MAX = 5
        private const val PREFS = "stelekit_recent_captures"
        private const val KEY = "records"
    }
}
