// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.constraints.AutoConstrainDefault
import dev.stapler.stelekit.sketch.v2.constraints.AutoConstrainOptions
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings

object AutoConstrainTags {
    const val TOGGLE = "sketch.autoconstrain"
    const val FREE_DRAW_CHIP = "sketch.chip.freedraw"
}

/**
 * The per-sheet auto-constrain setting and its touch-accessible suppression (Task 4.2.2c, UX-82, D-2). A sheet starts with the
 * default of its kind (OFF on a diagram, ON on a technical or converted sheet); the user's choice is kept per sheet by
 * [onStored] and is not a document edit. "Free draw" is a one-shot: it suppresses inference for the next shape only, so a
 * touch user with no Ctrl key can still draw a shape that is not constrained.
 */
class AutoConstrainState(
    private val kindNow: () -> SheetKind,
    stored: Boolean? = null,
    private val onStored: (Boolean) -> Unit = {},
) {
    constructor(kind: SheetKind, stored: Boolean? = null, onStored: (Boolean) -> Unit = {}) : this({ kind }, stored, onStored)

    val kind: SheetKind get() = kindNow()
    private var storedChoice by mutableStateOf(stored)
    private var freeDrawNext by mutableStateOf(false)

    val enabled: Boolean get() = storedChoice ?: AutoConstrainDefault.enabledFor(kind)

    /** The "Free draw" chip is armed for the next shape. */
    val freeDrawArmed: Boolean get() = freeDrawNext

    fun toggle() {
        val next = !enabled
        storedChoice = next
        onStored(next)
    }

    /** The chip: arms the one-shot; activating it again before the shape disarms it. */
    fun toggleFreeDraw() {
        freeDrawNext = !freeDrawNext
    }

    /** The options for the shape just drawn; [ctrlHeld] is the desktop path, the armed chip the touch path. The one-shot is spent here. */
    fun optionsForNextShape(ctrlHeld: Boolean): AutoConstrainOptions {
        val suppressed = ctrlHeld || freeDrawNext
        freeDrawNext = false
        return AutoConstrainOptions(enabled = enabled, suppressed = suppressed)
    }
}

/** The toggle row for the view cluster and the overflow menu: a switch whose semantics carry the current state. */
@Composable
fun AutoConstrainToggle(state: AutoConstrainState, modifier: Modifier = Modifier) {
    val description = if (state.enabled) SketchConstraintStrings.AutoConstrain.TOGGLE_ON else SketchConstraintStrings.AutoConstrain.TOGGLE_OFF
    Row(
        modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(AutoConstrainTags.TOGGLE)
            .toggleable(value = state.enabled, role = Role.Switch, onValueChange = { state.toggle() })
            .clearAndSetSemantics { contentDescription = description; role = Role.Switch; stateDescription = if (state.enabled) SketchConstraintStrings.AutoConstrain.STATE_ON else SketchConstraintStrings.AutoConstrain.STATE_OFF },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(SketchConstraintStrings.AutoConstrain.TOGGLE)
        Switch(checked = state.enabled, onCheckedChange = null)
    }
}

/** The "Free draw" chip for the action chip row, present while auto-constrain is on (nothing to suppress otherwise). */
object AutoConstrainChips {
    fun freeDraw(state: AutoConstrainState): ActionChip? =
        if (!state.enabled) {
            null
        } else {
            ActionChip(AutoConstrainTags.FREE_DRAW_CHIP, SketchConstraintStrings.AutoConstrain.FREE_DRAW_CHIP, SketchConstraintStrings.AutoConstrain.FREE_DRAW_NAME, state::toggleFreeDraw)
        }
}
