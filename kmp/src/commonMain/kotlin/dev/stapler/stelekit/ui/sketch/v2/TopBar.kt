// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

enum class SaveStatus { Saved, Saving, Unsaved, Failed }

object TopBarTags {
    const val BACK = "sketch.topbar.back"
    const val NAME = "sketch.topbar.name"
    const val SAVE = "sketch.topbar.save"
}

/** Everything the top bar shows (Story 3.1.4d): the tier comes from `ChromeBudget.topBar`, so this composable decides nothing about fit. */
class TopBarContent(
    val title: String,
    val plan: TopBarPlan,
    val fingerOn: Boolean,
    val pointer: PointerClass,
    val saveStatus: SaveStatus = SaveStatus.Saved,
    val selectionCount: Int = 0,
    val selectionItems: List<SelectionAction> = emptyList(),
)

class TopBarActions(
    val onBack: (() -> Unit)? = null,
    val onFingerToggle: () -> Unit = {},
    val onSelectionAction: (SelectionAction) -> Unit = {},
)

/**
 * The top bar (Story 3.1.4d, UX-80, UX-119): back, the sketch name, the pinned Finger drawing chip, the save status, the
 * Selection menu (rail with a side sheet only) and Overflow. It never wraps and keeps one row height. The Finger state is TEXT
 * at every font scale ("Finger: On" below scale 1.5, "On" from it), and the save status is text below 1.5 and a glyph-only
 * 44 dp button from it, with its full state in the semantics. [overflowMenu] is the host's menu; the Selection actions
 * are appended to it when the plan moved the menu into Overflow.
 */
@Composable
fun TopBar(
    content: TopBarContent,
    actions: TopBarActions,
    modifier: Modifier = Modifier,
    overflowModifier: Modifier = Modifier,
    onOverflowOpened: () -> Unit = {},
    overflowMenu: @Composable ColumnScope.(close: () -> Unit) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(ChromeRow.TopBar.heightDp.dp).testTag(ShellTags.TOP_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        actions.onBack?.let { back ->
            Box(
                Modifier.size(ChromeBudget.BACK_DP.dp).testTag(TopBarTags.BACK).clickable(onClick = back)
                    .semantics { contentDescription = SketchStrings.TopBar.BACK; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { Text(BACK_GLYPH) }
        }
        Text(
            content.title,
            Modifier.weight(1f).testTag(TopBarTags.NAME).semantics { contentDescription = content.title },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FingerChip(content, actions)
        SaveStatusButton(content.plan, content.saveStatus)
        if (content.plan.selection == SelectionMenuPlace.InBar) {
            SelectionMenuButton(content.selectionCount, content.selectionItems, actions.onSelectionAction)
        }
        Box {
            Text(
                OVERFLOW_GLYPH,
                Modifier.testTag(ShellTags.OVERFLOW).size(ChromeBudget.OVERFLOW_DP.dp).then(overflowModifier)
                    .clickable { onOverflowOpened(); menu = true }
                    .semantics { contentDescription = SketchStrings.Chrome.MORE_OPTIONS },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                overflowMenu { menu = false }
                if (content.plan.selection == SelectionMenuPlace.InOverflow) {
                    SelectionOverflowItems(content, actions) { menu = false }
                }
            }
        }
    }
}

@Composable
private fun FingerChip(content: TopBarContent, actions: TopBarActions) {
    val short = content.plan.form == TopBarForm.Short
    val label = when {
        content.fingerOn && short -> SketchStrings.TopBar.FINGER_ON_SHORT
        content.fingerOn -> SketchStrings.TopBar.FINGER_ON
        short -> SketchStrings.TopBar.FINGER_OFF_SHORT
        else -> SketchStrings.TopBar.FINGER_OFF
    }
    val state = if (content.fingerOn) SketchStrings.TopBar.STATE_ON else SketchStrings.TopBar.STATE_OFF
    val width = if (short) ChromeBudget.FINGER_SHORT_DP else ChromeBudget.FINGER_FULL_DP
    Box(
        Modifier.testTag(ShellTags.FINGER_CHIP)
            .size(width = width.dp, height = TouchTargetFloor.floorDp(content.pointer).dp.coerceAtLeast(ChromeBudget.BACK_DP.dp))
            .clickable(onClick = actions.onFingerToggle)
            .semantics {
                contentDescription = SketchStrings.TopBar.FINGER_SEMANTICS
                stateDescription = state
                role = Role.Switch
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, maxLines = 1) }
}

@Composable
private fun SaveStatusButton(plan: TopBarPlan, status: SaveStatus) {
    val glyphOnly = ChromeBudget.large(LocalDensity.current.fontScale.toDouble())
    val full = saveFullText(status)
    val width = if (!glyphOnly && plan.form == TopBarForm.Full) ChromeBudget.SAVE_FULL_DP else ChromeBudget.SAVE_SHORT_DP
    Box(
        Modifier.testTag(TopBarTags.SAVE).size(width = width.dp, height = ChromeBudget.SAVE_SHORT_DP.dp).semantics { contentDescription = full },
        contentAlignment = Alignment.Center,
    ) { Text(if (glyphOnly) saveGlyph(status) else saveShortText(status), maxLines = 1) }
}

@Composable
private fun SelectionOverflowItems(content: TopBarContent, actions: TopBarActions, close: () -> Unit) {
    for (action in content.selectionItems) {
        DropdownMenuItem(
            text = { Text(SelectionMenuModel.label(action)) },
            onClick = { close(); actions.onSelectionAction(action) },
            modifier = Modifier.testTag(SelectionMenuTags.item(action)),
        )
    }
}

private fun saveShortText(status: SaveStatus): String = when (status) {
    SaveStatus.Saved -> SketchStrings.TopBar.SAVED
    SaveStatus.Saving -> SketchStrings.TopBar.SAVING
    SaveStatus.Unsaved -> SketchStrings.TopBar.UNSAVED
    SaveStatus.Failed -> SketchStrings.TopBar.FAILED
}

private fun saveFullText(status: SaveStatus): String = when (status) {
    SaveStatus.Saved -> SketchStrings.TopBar.SAVED_FULL
    SaveStatus.Saving -> SketchStrings.TopBar.SAVING_FULL
    SaveStatus.Unsaved -> SketchStrings.TopBar.UNSAVED_FULL
    SaveStatus.Failed -> SketchStrings.TopBar.FAILED_FULL
}

private fun saveGlyph(status: SaveStatus): String = when (status) {
    SaveStatus.Saved -> "✓"
    SaveStatus.Saving -> "…"
    SaveStatus.Unsaved -> "●"
    SaveStatus.Failed -> "!"
}

private const val BACK_GLYPH = "←"
private const val OVERFLOW_GLYPH = "⋮"
