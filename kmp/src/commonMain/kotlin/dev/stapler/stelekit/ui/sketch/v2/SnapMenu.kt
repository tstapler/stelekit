// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.runtime.settings.SnapPreferences
import dev.stapler.stelekit.sketch.v2.snap.GridPreset
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKind
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKinds
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings

object SnapMenuTags {
    fun kind(kind: PointSnapKind) = "sketch.snap.kind.${kind.name.lowercase()}"
    const val SECTION = "sketch.snap.section"
    const val ALIGNMENT = "sketch.snap.alignment"
    const val GRID_STEP = "sketch.snap.gridstep"
}

/**
 * What the Snap menu has switched on: each point kind, the edge-and-centre alignment and the coarse grid step. Every choice is
 * stored per device in [prefs] when the host has one (otherwise it lasts for the session); all kinds start on, so object snap
 * is on by default, and the grid itself is a separate per-session toggle that starts off.
 */
class SnapChoices(private val prefs: SnapPreferences?) {
    var kinds: PointSnapKinds by mutableStateOf(prefs?.kinds ?: PointSnapKinds.All)
        private set
    var alignment: Boolean by mutableStateOf(prefs?.alignment ?: true)
        private set
    var gridPreset: GridPreset by mutableStateOf(prefs?.gridPreset ?: GridPreset.Default)
        private set

    fun toggle(kind: PointSnapKind) {
        val on = kind !in kinds
        kinds = kinds.with(kind, on)
        prefs?.choose(kind, on)
    }

    fun toggleAlignment() {
        alignment = !alignment
        prefs?.chooseAlignment(alignment)
    }

    fun cycleGrid() {
        gridPreset = gridPreset.next()
        prefs?.chooseGrid(gridPreset)
    }
}

/**
 * The "Snap to" entry of the overflow menu: one row that opens, in place, a switch per kind and, on a technical sheet, the grid
 * step. Closed it costs one row, so Import and Export stay on screen on a phone.
 */
@Composable
fun SnapMenuSection(choices: SnapChoices, technical: Boolean, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        DropdownMenuItem(
            text = { Text(SketchHostStrings.Snap.SECTION, style = MaterialTheme.typography.labelLarge) },
            trailingIcon = { Text(if (open) SketchHostStrings.Snap.COLLAPSE_MARK else SketchHostStrings.Snap.EXPAND_MARK) },
            // The menu stays open so the switches can be reached.
            onClick = { open = !open },
            modifier = Modifier.testTag(SnapMenuTags.SECTION)
                .semantics { role = Role.Button; stateDescription = if (open) SketchHostStrings.Snap.EXPANDED else SketchHostStrings.Snap.COLLAPSED },
        )
        if (open) {
            PointSnapKind.entries.forEach { kind ->
                SnapSwitchRow(SketchHostStrings.Snap.kindName(kind), kind in choices.kinds, SnapMenuTags.kind(kind)) { choices.toggle(kind) }
            }
            SnapSwitchRow(SketchHostStrings.Snap.ALIGNMENT, choices.alignment, SnapMenuTags.ALIGNMENT, choices::toggleAlignment)
            if (technical) {
                val preset = choices.gridPreset
                DropdownMenuItem(
                    text = { Text(SketchHostStrings.Snap.gridRow(preset)) },
                    // The menu stays open so the step can be tapped through to the one wanted.
                    onClick = choices::cycleGrid,
                    modifier = Modifier.testTag(SnapMenuTags.GRID_STEP)
                        .semantics { contentDescription = SketchHostStrings.Snap.gridRowDescription(preset); role = Role.Button },
                )
            }
        }
    }
}

@Composable
private fun SnapSwitchRow(name: String, on: Boolean, tag: String, onToggle: () -> Unit) {
    val description = SketchHostStrings.Snap.toggleDescription(name, on)
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(tag)
            .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Switch
                stateDescription = if (on) SketchHostStrings.Snap.ON else SketchHostStrings.Snap.OFF
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name)
        Switch(checked = on, onCheckedChange = null)
    }
}
