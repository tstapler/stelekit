// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CancellationException

/**
 * [TaskRunLog] over [Settings], one newline-separated string per (key, [RunSource]) under
 * [TaskKeyEncoding.runLogKey]. Append is a read-modify-write of that string, safe because each
 * (key, source) has exactly one writer (worker / in-process loop for Background, the
 * `PeriodicSyncDriver` tick recorder for Foreground). Persists the newest [cap] per source.
 */
class SettingsTaskRunLog(
    private val settings: Settings,
    private val cap: Int = DEFAULT_CAP,
) : TaskRunLog {
    private val logger = Logger("TaskRunLog")

    override suspend fun append(record: TaskRunRecord) {
        logger.info(TaskRunRecordCodec.logLine(record))
        try {
            val settingsKey = TaskKeyEncoding.runLogKey(record.source, record.key)
            val existing = settings.getString(settingsKey, "").lines().filter { it.isNotEmpty() }
            val kept = (existing + TaskRunRecordCodec.encode(record)).takeLast(cap)
            settings.putString(settingsKey, kept.joinToString("\n"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("run log append failed for ${TaskKeyEncoding.encode(record.key)}", e)
        }
    }

    override suspend fun recent(key: TaskKey): List<TaskRunRecord> = when (val r = recentResult(key)) {
        is TaskRunReadResult.Loaded -> r.records
        is TaskRunReadResult.ReadFailed -> emptyList()
    }

    override suspend fun recentResult(key: TaskKey): TaskRunReadResult = try {
        TaskRunReadResult.Loaded(
            RunSource.entries
                .flatMap { source -> load(source, key) }
                .withIndex()
                .sortedWith(compareByDescending<IndexedValue<TaskRunRecord>> { it.value.startedAtMs }.thenByDescending { it.index })
                .map { it.value }
                .take(cap),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("run log read failed for ${TaskKeyEncoding.encode(key)}", e)
        TaskRunReadResult.ReadFailed(e)
    }

    override suspend fun lastSuccessAtMs(key: TaskKey): Long? =
        recent(key).filter { it.result is TaskRunResult.Success }.maxOfOrNull { it.startedAtMs }

    private fun load(source: RunSource, key: TaskKey): List<TaskRunRecord> =
        settings.getString(TaskKeyEncoding.runLogKey(source, key), "").lines()
            .mapNotNull { if (it.isEmpty()) null else TaskRunRecordCodec.decode(it, key) }

    companion object {
        const val DEFAULT_CAP = 20
    }
}

/**
 * Single-line, human-readable record format shared by the persisted value and the `Logger` line:
 * `v1 source=.. scheduledAt=<ms|-> startedAt=<ms> result=<Success|Partial:d|Skipped:r|Failed:m> retryable=.. online=.. metered=<true|false|unknown> charging=..`.
 * Free-text values are percent-escaped so a record never spans lines or splits on a space.
 */
internal object TaskRunRecordCodec {
    fun encode(r: TaskRunRecord): String = "v1 " + fields(r)

    fun logLine(r: TaskRunRecord): String =
        "TaskRun kind=${r.key.kind.id} scope=${escape(r.key.scope.value)} " + fields(r)

    fun decode(line: String, key: TaskKey): TaskRunRecord? {
        val parts = line.split(' ')
        if (parts.firstOrNull() != "v1") return null
        val f = parts.drop(1).mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to unescape(p.substring(it + 1)) } }.toMap()
        val source = RunSource.entries.firstOrNull { it.name == f["source"] } ?: return null
        val started = f["startedAt"]?.toLongOrNull() ?: return null
        val scheduled = f["scheduledAt"]?.let { if (it == "-") null else it.toLongOrNull() ?: return null }
        val result = decodeResult(f["result"] ?: return null, f["retryable"] == "true") ?: return null
        val online = f["online"]?.toBooleanStrictOrNull() ?: return null
        return TaskRunRecord(key, source, scheduled, started, result, ConstraintState(online, triState(f["metered"]), triState(f["charging"])))
    }

    private fun fields(r: TaskRunRecord): String {
        val retryable = (r.result as? TaskRunResult.Failed)?.retryable == true
        return "source=${r.source.name} scheduledAt=${r.scheduledAtMs ?: "-"} startedAt=${r.startedAtMs} " +
            "result=${escape(encodeResult(r.result))} retryable=$retryable online=${r.constraintState.online} " +
            "metered=${tri(r.constraintState.isMetered)} charging=${tri(r.constraintState.charging)}"
    }

    private fun encodeResult(r: TaskRunResult): String = when (r) {
        TaskRunResult.Success -> "Success"
        is TaskRunResult.Partial -> "Partial:${r.detail}"
        is TaskRunResult.Skipped -> "Skipped:${r.reason}"
        is TaskRunResult.Failed -> "Failed:${r.message}"
    }

    private fun decodeResult(raw: String, retryable: Boolean): TaskRunResult? {
        val arg = raw.substringAfter(':', "")
        return when (raw.substringBefore(':')) {
            "Success" -> TaskRunResult.Success
            "Partial" -> TaskRunResult.Partial(arg)
            "Skipped" -> TaskRunResult.Skipped(arg)
            "Failed" -> TaskRunResult.Failed(arg, retryable)
            else -> null
        }
    }

    private fun tri(b: Boolean?): String = b?.toString() ?: "unknown"
    private fun triState(s: String?): Boolean? = s?.toBooleanStrictOrNull()

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '%' -> append("%25")
            ' ' -> append("%20")
            '\n' -> append("%0A")
            '\r' -> append("%0D")
            else -> append(c)
        }
    }

    private fun unescape(s: String): String = s.replace("%20", " ").replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%")
}
