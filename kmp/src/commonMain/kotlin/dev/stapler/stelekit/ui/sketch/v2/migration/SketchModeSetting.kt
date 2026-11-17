// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings

object ModeSettingTags {
    const val GROUP = "sketch.mode.group"
    const val LEARN = "sketch.mode.learn"
    const val TEMPORARY = "sketch.mode.temporary"
    const val REST_REASON = "sketch.mode.rest-reason"

    fun option(mode: V2Mode) = "sketch.mode.option.${mode.name}"
}

private val OptionGap = 8.dp

/**
 * Settings > Sketches > New editor (Task 1.4.6h): ONE radiogroup named "New editor"; each radio's explanatory line is its
 * accessible description. Arrow keys move focus without selecting (selection happens only on click, Space or Enter); a
 * disabled radio stays focusable, is announced unavailable, and its reason is visible text and part of its description.
 * Choosing writes nothing here: [onChoose] hands the mode to [ModeChangePlanner].
 */
@Composable
fun SketchModeSetting(model: ModeControlModel, onChoose: (V2Mode) -> Unit, onLearn: () -> Unit, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(OptionGap), modifier = modifier) {
        Text(model.heading, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Column(
            modifier = Modifier
                .selectableGroup()
                .testTag(ModeSettingTags.GROUP)
                .semantics { contentDescription = model.groupName },
        ) {
            model.options.forEach { option -> ModeRadio(option, onChoose) }
        }
        model.temporaryGraphLine?.let { Text(it, modifier = Modifier.testTag(ModeSettingTags.TEMPORARY)) }
        model.restDisabledReason?.let { Text(it, modifier = Modifier.testTag(ModeSettingTags.REST_REASON)) }
        TextButton(onClick = onLearn, modifier = Modifier.testTag(ModeSettingTags.LEARN)) { Text(SketchMigrationStrings.Mode.LEARN_WHAT_CHANGES) }
    }
}

@Composable
private fun ModeRadio(option: ModeOption, onChoose: (V2Mode) -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = option.selected, role = Role.RadioButton, onClick = { if (option.enabled) onChoose(option.mode) })
            .testTag(ModeSettingTags.option(option.mode))
            .semantics(mergeDescendants = true) {
                contentDescription = option.accessibleDescription
                if (!option.enabled) disabled()
            },
    ) {
        RadioButton(selected = option.selected, onClick = null)
        Column(modifier = Modifier.padding(start = OptionGap)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            Text(option.line, style = MaterialTheme.typography.bodySmall)
            option.disabledReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
