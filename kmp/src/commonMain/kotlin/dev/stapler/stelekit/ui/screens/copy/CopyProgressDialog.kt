// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.MergePhase
import dev.stapler.stelekit.merge.MergeProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample

private const val COUNT_ANNOUNCE_INTERVAL_MS = 2_000L

/**
 * S5. Determinate progress with a **Stop** button (never "Cancel": written pages stay written).
 * Back/Esc = Stop; outside taps do nothing. [stopping] flips the label to "Stopping..." while the
 * page in flight finishes. [backgroundNotice] true shows the polite "continues in the background" line
 * (push copy after a graph switch). When [progress] reaches Finished the completion text is announced.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Composable
fun CopyProgressDialog(
    direction: CopyDirection,
    sourceName: String,
    targetName: String,
    progress: MergeProgress,
    currentPage: String?,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    stopping: Boolean = false,
    backgroundNotice: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // The "x of y" text is a live region, so it updates at most every 2s; the bar itself is immediate.
    val latest = rememberUpdatedState(progress)
    var announced by remember { mutableStateOf(progress) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest.value }.sample(COUNT_ANNOUNCE_INTERVAL_MS).collect { announced = it }
    }
    val finished = progress.phase == MergePhase.Finished || progress.phase == MergePhase.Stopped
    val shown = if (finished) progress else announced

    CopyDialogSurface(
        title = CopyDialogStrings.progressTitle(direction, sourceName, targetName),
        onDismissRequest = onStop,
        modifier = modifier,
    ) {
        Column {
            val fraction = if (progress.total > 0) (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                },
            )
            Text(
                if (finished) CopyDialogStrings.COMPLETE else CopyDialogStrings.progressCount(shown.done, shown.total),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.politeLiveRegion(),
            )
            if (!currentPage.isNullOrEmpty()) {
                Text("Current: $currentPage", style = MaterialTheme.typography.bodySmall)
            }
            if (backgroundNotice) {
                Text(
                    CopyDialogStrings.backgroundNotice(targetName),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.politeLiveRegion(),
                )
            }
            Text(
                CopyDialogStrings.STOP_HELPER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionColumn {
                Button(
                    onClick = onStop,
                    enabled = !stopping,
                    modifier = ActionButtonModifier.focusRequester(focus),
                ) { Text(if (stopping) "Stopping..." else CopyDialogStrings.STOP) }
            }
        }
    }
}

/** Persistent notice for when a graph switch dismissed [CopyProgressDialog] (host shows it as a snackbar/banner). */
@Composable
fun CopyBackgroundNotice(targetName: String, modifier: Modifier = Modifier) {
    Text(
        CopyDialogStrings.backgroundNotice(targetName),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.politeLiveRegion(),
    )
}

/** Pull copies need the destination to stay active, so switching mid-run asks first. */
@Composable
fun CopyPullSwitchConfirmDialog(
    graphName: String,
    onStopAndSwitch: () -> Unit,
    onKeepCopying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CopyDialogSurface(title = CopyDialogStrings.pullSwitchConfirm(graphName), onDismissRequest = onKeepCopying, modifier = modifier) {
        ActionColumn {
            Button(onClick = onStopAndSwitch, modifier = ActionButtonModifier) { Text(CopyDialogStrings.STOP_AND_SWITCH) }
            OutlinedButton(onClick = onKeepCopying, modifier = ActionButtonModifier) { Text(CopyDialogStrings.KEEP_COPYING) }
        }
    }
}
