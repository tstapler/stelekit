// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import arrow.core.Either
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.stapler.stelekit.merge.SketchCopyAction
import dev.stapler.stelekit.merge.SketchCopyDialogModel
import dev.stapler.stelekit.merge.SketchCopyError
import dev.stapler.stelekit.merge.SketchCopyResult
import dev.stapler.stelekit.projectbridge.SketchCopyHost
import dev.stapler.stelekit.projectbridge.SketchCopyTarget
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEngineStrings
import kotlinx.coroutines.launch

const val SKETCH_COPY_PICK_TAG = "sketch-copy-pick"

/** Test tag of the picker button of the graph [id]. */
fun sketchCopyTargetTag(id: String): String = "sketch-copy-target-$id"

/**
 * "Copy to graph..." for one sketch (Story 6.1.2): pick another graph, run the copy through [host] and show the outcome as
 * [SketchCopyDialog] (Open, Rename and copy, Retry, Done). [onFinish] closes the whole flow. The copy itself survives this
 * composable leaving (the host runs it to its end), so only the dialog is transient state here.
 */
@Composable
fun SketchCopyFlow(host: SketchCopyHost, sketch: ProjectRow, onFinish: () -> Unit) {
    var target by remember(sketch) { mutableStateOf<SketchCopyTarget?>(null) }
    var result by remember(sketch) { mutableStateOf<Either<SketchCopyError, SketchCopyResult>?>(null) }
    val scope = rememberCoroutineScope()
    val chosen = target
    val outcome = result
    val run = { to: SketchCopyTarget, asSlug: String ->
        result = null
        scope.launch { result = host.copy(sketch.slug, to.id, asSlug) }
        Unit
    }
    when {
        chosen == null -> TargetPicker(host.targets(), onPick = { target = it; run(it, sketch.slug) }, onCancel = onFinish)
        outcome == null -> AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(SketchEngineStrings.SketchCopy.COPYING) })
        else -> SketchCopyDialog(
            model = SketchCopyDialogModel.of(sketch.slug, chosen.name, outcome),
            currentSlug = sketch.slug,
            onAction = { action -> onCopyAction(action, host, onFinish) { run(chosen, sketch.slug) } },
            onRenameAndCopy = { name -> run(chosen, name) },
        )
    }
}

private fun onCopyAction(action: SketchCopyAction, host: SketchCopyHost, onFinish: () -> Unit, retry: () -> Unit) {
    when (action) {
        is SketchCopyAction.Open -> { host.open(action.target); onFinish() }
        SketchCopyAction.Retry -> retry()
        SketchCopyAction.Done, SketchCopyAction.Cancel, SketchCopyAction.RenameAndCopy -> onFinish()
    }
}

@Composable
private fun TargetPicker(targets: List<SketchCopyTarget>, onPick: (SketchCopyTarget) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(SKETCH_COPY_PICK_TAG),
        title = { Text(SketchEngineStrings.SketchCopy.PICK_TITLE) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                for (graph in targets) {
                    TextButton(onClick = { onPick(graph) }, modifier = Modifier.testTag(sketchCopyTargetTag(graph.id.value))) { Text(graph.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(SketchEngineStrings.SketchCopy.CANCEL) } },
    )
}
