// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.ui.sketch.DimensionField
import dev.stapler.stelekit.ui.sketch.lengthTextOf
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import dev.stapler.stelekit.units.Inches


/**
 * The Stats section of the style panel (Task 3.5.4b): x, y, width, height and angle of one selected element. A number
 * commits on Enter or when the field loses focus; text that is not a number keeps the old value and shows the reason
 * inline. For a technical sheet ([technical]) width and height reuse [DimensionField], so "12 3/16\"" works and an
 * invalid length shows the length parser's own message.
 */
@Composable
fun ElementStatsPanel(
    element: SceneElement,
    technical: Boolean,
    onCommand: (Command) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = ElementStats.of(element) ?: return
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Stats", style = MaterialTheme.typography.titleSmall)
        // Commands do not enforce the lock (ElementCommands), so the panel must: a locked element is shown, not edited.
        if (element.base.locked) {
            Text(SketchStrings.NonDrag.LOCKED, Modifier.testTag(StylePanelTags.STATS_PREFIX + "locked"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StatNumber("x", StatField.X, stats.x, element, onCommand, caption = if (technical) "x (1/16\")" else "x")
            StatNumber("y", StatField.Y, stats.y, element, onCommand, caption = if (technical) "y (1/16\")" else "y")
        }
        if (technical) {
            DimensionField(
                initial = lengthTextOf(kotlin.math.round(stats.width).toLong()),
                onCommit = { inches -> onCommand(sizeCommand(element, stats, widthUnits = inches)) },
                label = "Width",
            )
            DimensionField(
                initial = lengthTextOf(kotlin.math.round(stats.height).toLong()),
                onCommit = { inches -> onCommand(sizeCommand(element, stats, heightUnits = inches)) },
                label = "Height",
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatNumber("w", StatField.Width, stats.width, element, onCommand)
                StatNumber("h", StatField.Height, stats.height, element, onCommand)
            }
        }
        StatNumber("angle", StatField.Angle, stats.angleDegrees, element, onCommand)
    }
}

/** The size command for a length typed in inches-and-fractions: one scene unit is a sixteenth of an inch. */
private fun sizeCommand(element: SceneElement, stats: ElementStats, widthUnits: Inches? = null, heightUnits: Inches? = null): Command {
    val w = widthUnits?.sixteenths?.toDouble() ?: stats.width
    val h = heightUnits?.sixteenths?.toDouble() ?: stats.height
    return dev.stapler.stelekit.sketch.v2.commands.ResizeElement(element.id, stats.x, stats.y, w, h)
}

@Composable
private fun StatNumber(label: String, field: StatField, value: Double, element: SceneElement, onCommand: (Command) -> Unit, caption: String = label) {
    val shown = formatStat(value)
    var text by remember(element.id, element.base.version, shown) { mutableStateOf(shown) }
    var error by remember(element.id, element.base.version, shown) { mutableStateOf<String?>(null) }
    fun commit() {
        when (val r = ElementStatsEdits.commit(element, field, text)) {
            is ElementStatsEdits.CommitResult.Edit -> { error = null; onCommand(r.command) }
            is ElementStatsEdits.CommitResult.Invalid -> { error = r.message; text = shown }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; error = null },
        label = { Text(caption) },
        singleLine = true,
        isError = error != null,
        supportingText = { error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(StylePanelTags.STATS_PREFIX + label + ".error")) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.widthIn(min = 96.dp, max = 160.dp).testTag(StylePanelTags.STATS_PREFIX + label).onFocusChanged { if (!it.isFocused && text != shown) commit() },
    )
}

private const val STAT_DECIMALS = 100.0

private fun formatStat(v: Double): String {
    val rounded = kotlin.math.round(v * STAT_DECIMALS) / STAT_DECIMALS
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
