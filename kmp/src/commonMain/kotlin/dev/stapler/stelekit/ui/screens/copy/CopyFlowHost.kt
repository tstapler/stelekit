// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.MergeProgress
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.PlatformBackHandler
import kotlinx.coroutines.delay

/** The app's copy flow, for entry points deep in the tree (page overflow); null where copy is unavailable. */
val LocalCopyFlow = staticCompositionLocalOf<CopyFlowController?> { null }

/**
 * Binds [controller] to the open graph for as long as this is composed and renders the flow. The controller
 * (not this composable) owns the state, so a graph switch recreates the host without losing a running copy.
 *
 * @param conflictReviewFactory builds the conflict list for the open graph (only called when it is the copy's target)
 */
@Composable
fun CopyFlowHost(
    controller: CopyFlowController,
    binding: CopyGraphBinding,
    conflictReviewFactory: () -> ConflictReviewViewModel?,
    onOpenPage: (PageUuid) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(controller, binding) {
        controller.attachGraph(binding)
        onDispose { controller.detachGraph(binding) }
    }
    LaunchedEffect(controller) { controller.noticeFlow.collect(onNotice) }
    LaunchedEffect(controller) { controller.checkInterrupted() }
    val state by controller.state.collectAsState()
    val progress by controller.progress.collectAsState()
    CopyFlowContent(
        state = state,
        progress = progress,
        nameOf = controller::nameOf,
        actions = controller,
        modifier = modifier,
        conflicts = { target ->
            ConflictsOfTarget(binding.graphId, target, conflictReviewFactory, onOpenPage, controller::reviewConflicts, controller::closeConflicts)
        },
    )
}

/** Stateless renderer of [CopyFlowState]; Robolectric tests drive it with hand-built states. */
@Composable
fun CopyFlowContent(
    state: CopyFlowState,
    progress: MergeProgress,
    nameOf: (GraphId) -> String,
    actions: CopyFlowActions,
    modifier: Modifier = Modifier,
    conflicts: @Composable (target: GraphId) -> Unit = {},
) {
    val request = state.request
    val pickerState = state.picker?.state?.collectAsState()?.value
    val sourceName = request?.sourceGraphName ?: pickerState?.activeGraphName.orEmpty()
    val targetName = request?.let { nameOf(it.targetGraphId) } ?: pickerState?.chosenDestination?.name.orEmpty()

    Box(modifier) {
        if (state.stage == CopyStage.Picking && state.picker != null) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                CopyPagesScreen(state.picker, actions::onPickerEvent)
            }
        }
        state.dryRun?.let { view ->
            DryRunDialog(
                direction = CopyDirection.Push,
                sourceName = sourceName,
                targetName = targetName,
                state = (view.ui as? DryRunUiState.Checking)?.copy(checked = progress.done, total = progress.total) ?: view.ui,
                onConfirm = actions::dryRunConfirm,
                onBack = actions::dryRunBack,
                onRetry = actions::dryRunRetry,
                onChooseAnotherDestination = actions::dryRunChooseAnother,
            )
        }
        StageOverlay(state, progress, sourceName, targetName, nameOf, actions, conflicts)
    }
}

@Composable
private fun StageOverlay(
    state: CopyFlowState,
    progress: MergeProgress,
    sourceName: String,
    targetName: String,
    nameOf: (GraphId) -> String,
    actions: CopyFlowActions,
    conflicts: @Composable (GraphId) -> Unit,
) {
    when (state.stage) {
        CopyStage.Running -> if (state.backgrounded) {
            BackgroundBanner(targetName, actions::stop)
        } else {
            CopyProgressDialog(CopyDirection.Push, sourceName, targetName, progress, currentPage = null, onStop = actions::stop, stopping = state.stopping)
        }
        CopyStage.Finished -> state.result?.let { result ->
            CopyResultDialog(
                direction = CopyDirection.Push,
                sourceName = sourceName,
                targetName = targetName,
                result = result,
                onDone = actions::done,
                onRetryFailures = actions::retryFailedPages,
                onReviewConflicts = actions::reviewConflicts,
                onUndo = actions::requestUndo,
                onContinue = actions::continueStopped,
            )
        }
        CopyStage.RunFailed -> CopyRunFailedDialog(
            targetName = targetName,
            reason = state.failure.orEmpty(),
            onRetry = actions::runFailedRetry,
            onChooseAnotherDestination = actions::runFailedChooseAnother,
            onDone = actions::closeFlow,
        )
        CopyStage.ConfirmUndo -> UndoConfirmDialog(targetName, actions::confirmUndo, actions::cancelUndo)
        CopyStage.Conflicts -> state.conflictsTarget?.let { conflicts(it) }
        CopyStage.Interrupted -> state.interrupted?.let { notice ->
            InterruptedCopyDialog(notice, onResume = actions::resume, onDismiss = actions::dismissInterrupted, onOpenPicker = actions::openPickerFromInterrupted)
        }
        CopyStage.Idle, CopyStage.Picking -> Unit
    }
}

@Composable
private fun UndoConfirmDialog(targetName: String, onUndo: () -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Undo this copy?") },
        text = {
            Text("Removes the pages and blocks this copy added to \"$targetName\". Anything you edited since stays in place.")
        },
        confirmButton = { Button(onClick = onUndo) { Text("Undo copy") } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.focusRequester(focus)) { Text("Cancel") } },
    )
}

/** Shown instead of the progress dialog once a graph switch left the run going on its own. */
@Composable
private fun BackgroundBanner(targetName: String, onStop: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(tonalElevation = 6.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CopyBackgroundNotice(targetName, Modifier.weight(1f))
                TextButton(onClick = onStop) { Text(CopyDialogStrings.STOP) }
            }
        }
    }
}

/** Max wait for the target graph to open before the review offers Retry. */
private const val OPEN_TARGET_TIMEOUT_MS = 15_000L

/** The conflict list backs onto the open graph's database, so it renders only once the target is the open graph. */
@Composable
internal fun ConflictsOfTarget(
    openGraph: GraphId,
    target: GraphId,
    factory: () -> ConflictReviewViewModel?,
    onOpenPage: (PageUuid) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    openTimeoutMs: Long = OPEN_TARGET_TIMEOUT_MS,
) {
    if (openGraph != target) {
        OpeningGraph(openTimeoutMs, onRetry, onClose)
        return
    }
    val vm = remember(openGraph) { factory() }
    if (vm == null) {
        val close by rememberUpdatedState(onClose)
        LaunchedEffect(Unit) { close() }
        return
    }
    DisposableEffect(vm) { onDispose { vm.close() } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ConflictReviewScreen(vm, onOpenPage = onOpenPage, onClose = onClose)
    }
}

@Composable
private fun OpeningGraph(timeoutMs: Long, onRetry: () -> Unit, onClose: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        timedOut = false
        delay(timeoutMs)
        timedOut = true
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    PlatformBackHandler(enabled = true) { onClose() }
    Surface(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false
            }
            .focusRequester(focus)
            .focusable(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (timedOut) {
                Text("Couldn't open the graph. It may still be loading, or it failed to open.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { attempt++; onRetry() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                }
            } else {
                Text("Opening graph...")
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
        }
    }
}
