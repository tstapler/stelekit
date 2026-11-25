// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.tools.RotatePivot
import dev.stapler.stelekit.sketch.v2.tools.RotatePlan
import dev.stapler.stelekit.sketch.v2.tools.Selectability
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings.Rotate as R

object RotateTags {
    const val CHIP = "sketch.rotate.chip"
    const val DIALOG = "sketch.rotate.dialog"
    const val FIELD = "sketch.rotate.field"
    const val SET = "sketch.rotate.set"
    const val CANCEL = "sketch.rotate.cancel"
    const val ERROR = "sketch.rotate.error"
    fun step(label: String) = "sketch.rotate.step.$label"
    fun pivot(p: RotatePivot) = "sketch.rotate.pivot.${p.name.lowercase()}"
}

private const val BUTTON_MIN_DP = 48
private const val PIVOTS_PER_ROW = 2

/** The sign and size of each quick-step button: +15, +45, +90 and -90 degrees. */
internal val ROTATE_QUICK_STEPS: List<Pair<String, Double>> = listOf(R.PLUS_15 to 15.0, R.PLUS_45 to 45.0, R.PLUS_90 to 90.0, R.MINUS_90 to -90.0)

/** Rotates the selection by a typed angle or a quick step, about its centre or a corner of its box, as one undo step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RotateDialog(controller: SketchEditorController, autoFocus: Boolean = true) {
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf(false) }
    var pivot by remember { mutableStateOf(RotatePivot.Centre) }
    val focus = remember { FocusRequester() }
    // A focused field blinks its cursor forever, which keeps a Robolectric test from ever idling; the test turns this off.
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    val close = { controller.overlay = null }
    val turn = { degrees: Double ->
        val scene = controller.doc.scene
        val ids = controller.ui.selection.map(::ElementId).filter { id -> scene[id]?.let { Selectability.isSelectable(scene, it) } == true }
        val command = RotatePlan.command(scene, ids, degrees, pivot)
        val failed = command != null && controller.submit(command) != null
        if (failed) problem = true else close()
    }
    val apply = {
        val degrees = RotatePlan.parseDegrees(text)
        if (degrees == null) problem = true else turn(degrees)
    }
    AlertDialog(
        onDismissRequest = close,
        modifier = Modifier.testTag(RotateTags.DIALOG),
        title = { Text(R.TITLE) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text,
                    { text = it; problem = false },
                    Modifier.fillMaxWidth().focusRequester(focus).testTag(RotateTags.FIELD),
                    label = { Text(R.FIELD) },
                    singleLine = true,
                    isError = problem,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (problem) Text(R.INVALID, Modifier.testTag(RotateTags.ERROR))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ROTATE_QUICK_STEPS.forEach { (label, degrees) ->
                        TextButton({ turn(degrees) }, Modifier.defaultMinSize(minHeight = BUTTON_MIN_DP.dp).testTag(RotateTags.step(label))) { Text(label) }
                    }
                }
                Text(R.PIVOT)
                RotatePivot.entries.chunked(PIVOTS_PER_ROW).forEach { rowOfPivots ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowOfPivots.forEach { p ->
                            FilterChip(pivot == p, { pivot = p }, { Text(R.pivotName(p)) }, Modifier.defaultMinSize(minHeight = BUTTON_MIN_DP.dp).testTag(RotateTags.pivot(p)))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(apply, Modifier.testTag(RotateTags.SET)) { Text(R.SET) } },
        dismissButton = { TextButton(close, Modifier.testTag(RotateTags.CANCEL)) { Text(R.CANCEL) } },
    )
}
