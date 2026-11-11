// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.jobs.schedule.RunTimeFormatter
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskRunRecord
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.TaskShape
import dev.stapler.stelekit.jobs.schedule.formatRunTime
import dev.stapler.stelekit.jobs.schedule.platformRunTimeFormatter
import dev.stapler.stelekit.ui.components.settings.BackgroundTaskGraph
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

internal const val RUN_HISTORY_TITLE = "Background task runs"
internal const val RUN_HISTORY_UNREADABLE = "Couldn't read run history"
internal const val RUN_HISTORY_RETRY = "Retry"

/** Most run rows the Logs screen shows; older ones stay in the run log. */
internal const val MAX_RUN_ROWS = 50
private const val APP_WIDE_SCOPE = ""
private const val ROW_PADDING_DP = 8
private const val TARGET_DP = 48

/** What the Logs screen needs to list background runs; tasks and graphs are the keys to read, never scanned. */
class TaskRunLogSource(
    val runLog: TaskRunLog,
    val registry: TaskRegistry,
    val graphs: List<BackgroundTaskGraph>,
    val formatter: RunTimeFormatter = platformRunTimeFormatter(),
    val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val zone: TimeZone = TimeZone.currentSystemDefault(),
)

/** One Logs row: [text] and [time] are what is drawn; [description] is what a screen reader speaks. */
data class TaskRunRow(val startedAtMs: Long, val text: String, val time: String, val description: String)

/** The newest [MAX_RUN_ROWS] rows of [total]. */
data class TaskRunRows(val rows: List<TaskRunRow>, val total: Int)

internal sealed interface TaskRunRowsState {
    data object Loading : TaskRunRowsState
    data class Loaded(val rows: TaskRunRows) : TaskRunRowsState
    data object Unreadable : TaskRunRowsState
}

/**
 * Periodic tasks are keyed per graph, one-shot tasks app-wide (no graph segment). A throwing
 * [TaskRunLog.recent] propagates so the caller can offer Retry.
 */
internal suspend fun loadTaskRunRows(source: TaskRunLogSource): TaskRunRows {
    val titled = source.registry.kinds().mapNotNull { kind ->
        source.registry.specFor(kind)?.let { spec -> Triple(kind, spec.title, spec.shape) }
    }
    val keys = titled.flatMap { (kind, title, shape) ->
        if (shape is TaskShape.OneShot) {
            listOf(TaskKey(kind, TaskScope(APP_WIDE_SCOPE)) to (title to null))
        } else {
            source.graphs.map { g -> TaskKey(kind, TaskScope(g.id)) to (title to g.name) }
        }
    }
    val now = source.nowMs()
    val all = keys.flatMap { (key, label) ->
        source.runLog.recent(key).map { record -> toRow(record, label.first, label.second, now, source) }
    }.sortedByDescending { it.startedAtMs }
    return TaskRunRows(all.take(MAX_RUN_ROWS), all.size)
}

private fun toRow(record: TaskRunRecord, title: String, graphName: String?, now: Long, source: TaskRunLogSource): TaskRunRow {
    val segments = listOfNotNull(title, graphName, resultText(record.result))
    val time = formatRunTime(record.startedAtMs, now, source.zone, source.formatter)
    return TaskRunRow(
        startedAtMs = record.startedAtMs,
        text = segments.joinToString(" / "),
        time = time,
        description = segments.joinToString(", ") + ", " + time,
    )
}

private fun resultText(result: TaskRunResult): String = when (result) {
    TaskRunResult.Success -> "success"
    is TaskRunResult.Partial -> "partial: ${result.detail}"
    is TaskRunResult.Skipped -> "skipped: ${result.reason}"
    is TaskRunResult.Failed -> "failed: ${result.message}"
}

/** Rows whose visible text contains [query] (blank keeps all). */
internal fun List<TaskRunRow>.matching(query: String): List<TaskRunRow> =
    if (query.isBlank()) this else filter { it.text.contains(query, ignoreCase = true) || it.time.contains(query, ignoreCase = true) }

/** Reads the run log, re-reading when [source] changes or Retry is pressed; null [source] stays Loading-free and empty. */
@Composable
internal fun rememberTaskRunRowsState(source: TaskRunLogSource?): Pair<TaskRunRowsState, () -> Unit> {
    var attempt by remember { mutableStateOf(0) }
    var state by remember(source) { mutableStateOf<TaskRunRowsState>(TaskRunRowsState.Loading) }
    LaunchedEffect(source, attempt) {
        if (source == null) return@LaunchedEffect
        state = try {
            TaskRunRowsState.Loaded(loadTaskRunRows(source))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TaskRunRowsState.Unreadable
        }
    }
    return state to { attempt++ }
}

/** Heading plus one row per run; nothing at all while loading or when there are no runs. */
@Composable
internal fun TaskRunLogSection(state: TaskRunRowsState, query: String, onRetry: () -> Unit) {
    when (state) {
        TaskRunRowsState.Loading -> Unit
        TaskRunRowsState.Unreadable -> Column {
            RunHistoryHeading()
            Text(RUN_HISTORY_UNREADABLE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = TARGET_DP.dp)) { Text(RUN_HISTORY_RETRY) }
        }
        is TaskRunRowsState.Loaded -> {
            val shown = state.rows.rows.matching(query)
            if (shown.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(ROW_PADDING_DP.dp)) {
                    RunHistoryHeading()
                    shown.forEach { TaskRunRowItem(it) }
                    if (state.rows.total > state.rows.rows.size) {
                        Text(
                            "Showing newest ${state.rows.rows.size} of ${state.rows.total} runs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunHistoryHeading() {
    Text(
        RUN_HISTORY_TITLE,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = ROW_PADDING_DP.dp).semantics { heading() },
    )
}

@Composable
private fun TaskRunRowItem(row: TaskRunRow) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_PADDING_DP.dp),
    ) {
        Text(row.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(row.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
