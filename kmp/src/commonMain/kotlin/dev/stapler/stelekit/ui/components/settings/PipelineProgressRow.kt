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
import androidx.compose.ui.semantics.contentDescription
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

/** What the row's buttons do; bundled so the row has one parameter per concern. */
internal class PipelineRowActions(
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onStartAgain: () -> Unit = {},
    val onViewLog: () -> Unit = {},
    val onProcessNow: () -> Unit = {},
    val onRetryFailed: () -> Unit = {},
)

/**
 * Announce only on a state change or each 10% of progress, so a screen reader is not interrupted by
 * every asset. Equal keys mean "do not announce again".
 */
internal fun announcementKey(progress: PipelineProgress, waitingForCharger: Boolean): String = when {
    waitingForCharger -> "waiting"
    progress is PipelineProgress.Running ->
        "running-" + if (progress.total > 0) (progress.done * 10 / progress.total).toString() else "0"
    else -> progress::class.simpleName.orEmpty() + ((progress as? PipelineProgress.Paused)?.reason ?: "")
}

@Composable
internal fun PipelineProgressSection(controller: AssetPluginsController, onViewLog: () -> Unit) {
    val progress by controller.progress.collectAsState()
    val stoppedByUser by controller.stoppedByUser.collectAsState()
    val waiting by controller.waitingForCharger.collectAsState()
    PipelineProgressRow(
        progress = progress,
        stoppedByUser = stoppedByUser,
        waitingForCharger = waiting,
        actions = PipelineRowActions(
            onPause = controller::pause,
            onResume = controller::resume,
            onCancel = controller::cancel,
            onStartAgain = controller::startAgain,
            onViewLog = onViewLog,
            onProcessNow = controller::processNow,
            onRetryFailed = controller::retryFailed,
        ),
    )
}

/** Stateless progress row: text, a determinate bar while running, and the controls that fit the state. */
@Composable
internal fun PipelineProgressRow(
    progress: PipelineProgress,
    stoppedByUser: Boolean,
    waitingForCharger: Boolean,
    actions: PipelineRowActions,
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
    val text = if (waitingForCharger) {
        "Waiting for charger"
    } else {
        pipelineProgressText(progress, last, stoppedByUser) ?: return
    }
    val failed = when (progress) {
        is PipelineProgress.Running -> progress.failed
        is PipelineProgress.Finished -> progress.failed
        else -> 0
    }
    // The spoken text is frozen between announcement keys; the visible text still counts every asset.
    val key = announcementKey(progress, waitingForCharger)
    val spoken = remember(key) { text }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text,
            modifier = Modifier
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = spoken
                }
                .then(if (failed > 0) Modifier.clickable(onClick = actions.onViewLog) else Modifier),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (progress is PipelineProgress.Running && progress.total > 0 && !waitingForCharger) {
            LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
        }
        RowButtons(progress, waitingForCharger, actions) { finishedDismissed = true }
    }
}

@Composable
private fun RowButtons(progress: PipelineProgress, waitingForCharger: Boolean, actions: PipelineRowActions, onDismiss: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            waitingForCharger -> {
                TextButton(onClick = actions.onProcessNow) { Text("Process now") }
                TextButton(onClick = actions.onCancel) { Text("Cancel") }
            }
            progress is PipelineProgress.Running -> {
                TextButton(onClick = actions.onPause) { Text("Pause") }
                TextButton(onClick = actions.onCancel) { Text("Cancel") }
            }
            progress is PipelineProgress.Paused -> {
                TextButton(onClick = actions.onResume) { Text("Resume") }
                TextButton(onClick = actions.onCancel) { Text("Cancel") }
                TextButton(onClick = actions.onViewLog) { Text("View log") }
            }
            progress is PipelineProgress.Finished -> {
                if (progress.failed > 0) TextButton(onClick = actions.onRetryFailed) { Text("Retry failed") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            else -> TextButton(onClick = actions.onStartAgain) { Text("Start again") }
        }
    }
}
