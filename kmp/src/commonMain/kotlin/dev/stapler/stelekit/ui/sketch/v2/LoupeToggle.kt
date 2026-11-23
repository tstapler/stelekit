// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.runtime.settings.FingerDrawingSettings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

object LoupeTags {
    const val TOGGLE = "sketch.loupe.toggle"
    const val BUBBLE = "sketch.loupe.bubble"
}

/**
 * The touch magnifier's on/off switch: reads and writes `SketchEditorState.touchLoupe`, and keeps the choice per device in
 * [store] when the host has one (without it the choice lasts for the session).
 */
class LoupePreference(private val state: SketchEditorState, private val store: FingerDrawingSettings?) {
    init {
        store?.let { state.touchLoupe = it.loupeOn }
    }

    val on: Boolean get() = state.touchLoupe

    fun toggle() {
        state.touchLoupe = !state.touchLoupe
        store?.chooseLoupe(state.touchLoupe)
    }
}

/** The switch row of the overflow menu; its semantics carry the current state, like the auto-constrain row. */
@Composable
fun LoupeToggle(preference: LoupePreference, modifier: Modifier = Modifier) {
    val on = preference.on
    val description = if (on) SketchStrings.Loupe.TOGGLE_ON else SketchStrings.Loupe.TOGGLE_OFF
    Row(
        modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(LoupeTags.TOGGLE)
            .toggleable(value = on, role = Role.Switch, onValueChange = { preference.toggle() })
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Switch
                stateDescription = if (on) SketchStrings.Loupe.STATE_ON else SketchStrings.Loupe.STATE_OFF
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TOGGLE_GAP),
    ) {
        Text(SketchStrings.Loupe.TOGGLE)
        Switch(checked = on, onCheckedChange = null)
    }
}

private val TOGGLE_GAP = 12.dp
