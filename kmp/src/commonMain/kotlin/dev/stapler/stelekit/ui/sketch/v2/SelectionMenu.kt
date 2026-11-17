// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

/** The actions of the compact-height Selection menu (UX-127); the host wires each to the command it already has. */
enum class SelectionAction(val label: String, val keymapId: String?) {
    Duplicate(SketchStrings.SelectionMenu.DUPLICATE, "duplicate"),
    Group(SketchStrings.SelectionMenu.GROUP, "group"),
    Ungroup(SketchStrings.SelectionMenu.UNGROUP, "ungroup"),
    Align(SketchStrings.SelectionMenu.ALIGN, null),
    Order(SketchStrings.SelectionMenu.ORDER, null),
    Transform(SketchStrings.SelectionMenu.TRANSFORM, null),
    Lock(SketchStrings.SelectionMenu.LOCK, null),
    Unlock(SketchStrings.SelectionMenu.UNLOCK, null),
    BindEnd(SketchStrings.SelectionMenu.BIND_END, null),
}

object SelectionMenuTags {
    const val BUTTON = "sketch.selectionmenu"
    fun item(action: SelectionAction) = "sketch.selectionmenu.${action.name.lowercase()}"
}

/** The items for a selection: Lock or Unlock by state, and "Bind end..." only when exactly one arrow is selected. */
object SelectionMenuModel {
    fun items(anyUnlocked: Boolean, oneArrowSelected: Boolean): List<SelectionAction> = buildList {
        add(SelectionAction.Duplicate)
        add(SelectionAction.Group)
        add(SelectionAction.Ungroup)
        add(SelectionAction.Align)
        add(SelectionAction.Order)
        add(SelectionAction.Transform)
        add(if (anyUnlocked) SelectionAction.Lock else SelectionAction.Unlock)
        if (oneArrowSelected) add(SelectionAction.BindEnd)
    }

    fun label(action: SelectionAction, env: KeymapEnv = KeymapEnv(isMac = false, isWeb = false)): String {
        val shortcut = action.keymapId?.let { id -> KeymapSpec.entries.firstOrNull { it.id == id }?.bindings?.firstOrNull() }
        return if (shortcut == null) action.label else "${action.label}  ${KeymapSpec.displayLabel(shortcut, env)}"
    }
}

/**
 * The Selection menu of the top bar at compact height (UX-127): a 48 dp button whose semantics name the selection count.
 * In the degraded window the top bar shows the same actions as items of Overflow instead.
 */
@Composable
fun SelectionMenuButton(
    selectionCount: Int,
    items: List<SelectionAction>,
    onAction: (SelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier.size(ChromeBudget.SELECTION_MENU_DP.dp).testTag(SelectionMenuTags.BUTTON)
                .clickable { open = true }
                .semantics { contentDescription = SketchStrings.SelectionMenu.semantics(selectionCount); role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { Text(GLYPH) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (action in items) {
                DropdownMenuItem(
                    text = { Text(SelectionMenuModel.label(action)) },
                    onClick = { open = false; onAction(action) },
                    modifier = Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(SelectionMenuTags.item(action)),
                )
            }
        }
    }
}

private const val GLYPH = "☰"
