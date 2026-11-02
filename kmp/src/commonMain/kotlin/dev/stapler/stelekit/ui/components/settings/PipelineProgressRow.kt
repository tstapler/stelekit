package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.asset.pipeline.AssetPluginsController
import dev.stapler.stelekit.asset.pipeline.PauseReason
import dev.stapler.stelekit.asset.pipeline.PipelineProgress

/** Thousands-grouped count, e.g. 2100 -> "2,100". */
internal fun groupedCount(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

/** Last known totals, so Paused and Stopped lines can still say "at 340 of 2,100". */
internal data class RunSnapshot(val done: Int, val total: Int)

/** The row text for [progress]; null means the row is hidden (idle, nothing to report). */
internal fun pipelineProgressText(progress: PipelineProgress, last: RunSnapshot?, stoppedByUser: Boolean): String? = when (progress) {
    is PipelineProgress.Running -> buildString {
        append("Image processing: ${groupedCount(progress.done)} of ${groupedCount(progress.total)} done")
        if (progress.failed > 0) append(" · ${progress.failed} failed - view log")
    }
    is PipelineProgress.Paused -> {
        val counts = last?.let { "${groupedCount(it.done)} of ${groupedCount(it.total)}" }
        when (progress.reason) {
            PauseReason.Memory -> "Paused: low memory." + (counts?.let { " $it done" } ?: "")
            PauseReason.Charger -> "Waiting for charger"
            else -> "Paused" + (counts?.let { " at $it" } ?: "")
        }
    }
    is PipelineProgress.Finished -> buildString {
        append("${groupedCount(progress.done)} done")
        if (progress.failed > 0) append(" · ${progress.failed} failed - view log")
    }
    PipelineProgress.Idle ->
        if (stoppedByUser) {
            "Stopped" + (last?.let { " at ${groupedCount(it.done)} of ${groupedCount(it.total)}" } ?: "") +
                ". Already processed items are kept."
        } else {
            null
        }
}

@Composable
internal fun PipelineProgressSection(controller: AssetPluginsController, onViewLog: () -> Unit) {
    val progress by controller.progress.collectAsState()
    val stoppedByUser by controller.stoppedByUser.collectAsState()
    PipelineProgressRow(
        progress = progress,
        stoppedByUser = stoppedByUser,
        onPause = controller::pause,
        onResume = controller::resume,
        onCancel = controller::cancel,
        onStartAgain = controller::startAgain,
        onViewLog = onViewLog,
    )
}

/** Stateless progress row: text, a determinate bar while running, and the controls that fit the state. */
@Composable
internal fun PipelineProgressRow(
    progress: PipelineProgress,
    stoppedByUser: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onStartAgain: () -> Unit,
    onViewLog: () -> Unit,
) {
    var last by remember { mutableStateOf<RunSnapshot?>(null) }
    var finishedDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(progress) {
        if (progress is PipelineProgress.Running) {
            last = RunSnapshot(progress.done, progress.total)
            finishedDismissed = false
        }
    }
    if (progress is PipelineProgress.Finished && finishedDismissed) return
    val text = pipelineProgressText(progress, last, stoppedByUser) ?: return
    val failed = when (progress) {
        is PipelineProgress.Running -> progress.failed
        is PipelineProgress.Finished -> progress.failed
        else -> 0
    }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .then(if (failed > 0) Modifier.clickable(onClick = onViewLog) else Modifier),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (progress is PipelineProgress.Running && progress.total > 0) {
            LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (progress) {
                is PipelineProgress.Running -> {
                    TextButton(onClick = onPause) { Text("Pause") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                is PipelineProgress.Paused -> {
                    TextButton(onClick = onResume) { Text("Resume") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = onViewLog) { Text("View log") }
                }
                is PipelineProgress.Finished -> TextButton(onClick = { finishedDismissed = true }) { Text("Dismiss") }
                PipelineProgress.Idle -> TextButton(onClick = onStartAgain) { Text("Start again") }
            }
        }
    }
}
