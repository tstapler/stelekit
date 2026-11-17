// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.DofWording
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.ui.components.typedItems
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings

object ConstraintPanelTags {
    const val ROOT = "sketch.constraints"
    const val STATUS = "sketch.constraints.status"
    const val STATUS_DETAILS = "sketch.constraints.status.details"
    const val EXPLANATION = "sketch.constraints.status.explanation"
    const val EMPTY = "sketch.constraints.empty"
    const val CONSTRAIN = "sketch.constraints.constrain"
    const val CONSTRAIN_REASON = "sketch.constraints.constrain.reason"
    const val CARD = "sketch.constraints.card"
    const val CARD_MESSAGE = "sketch.constraints.card.message"
    const val DELETE = "sketch.constraints.delete"
    const val MAKE_REFERENCE = "sketch.constraints.reference"
    fun row(id: ConstraintId) = "sketch.constraints.row.${id.value}"
    fun item(item: ConstrainMenuItem) = "sketch.constraints.item.${item.name.lowercase()}"
    fun itemReason(item: ConstrainMenuItem) = "sketch.constraints.item.${item.name.lowercase()}.reason"
    fun cardAction(kind: ConflictActionKind) = "sketch.constraints.card.${kind.name.lowercase()}"
}

/** What the Constraints tab shows: the sheet, the last solve's analysis, whether it is a technical sheet, the selection, the solver tier and the open conflict card. */
data class ConstraintPanelInput(
    val doc: SketchDocumentV2,
    val analysis: ConstraintAnalysis,
    val technical: Boolean,
    val selection: List<SceneElement>,
    val tier: SolverTier = SolverTier.Full,
    val card: ConflictCard? = null,
)

/** What the panel asks its host to do; the host commits any command through `SolveRunner` and updates the highlight. */
class ConstraintPanelActions(
    val onConstrain: (ConstrainMenuItem) -> Unit = {},
    val onRowSelected: (ConstraintRow?) -> Unit = {},
    val onDelete: (ConstraintId) -> Unit = {},
    val onSetReference: (ConstraintId, Boolean) -> Unit = { _, _ -> },
    val onConflictAction: (ConflictAction) -> Unit = {},
    /** Enter or F2 on a focused dimension row opens its label editor (UX-99). */
    val onEditDimension: (ConstraintId) -> Unit = {},
)

/**
 * The Constraints tab (Story 4.2.3, Task 4.2.3a): the status line, the Constrain trigger and flyout, the list of
 * constraints and the conflict card. The tab is present with nothing selected and no constraints (UX-84); a `Hidden` solver
 * tier hides all of it (ADR-003). A row's state is a glyph AND a word, never colour alone (UX-33); selecting a row asks the
 * host to highlight its geometry (UX-34).
 */
@Composable
fun ConstraintPanel(
    input: ConstraintPanelInput,
    actions: ConstraintPanelActions,
    modifier: Modifier = Modifier,
) {
    val doc = input.doc
    val flyout = ConstrainFlyoutModel.of(input.selection, input.tier, selectionHasDimension = selectionHasDimension(doc, input.selection))
    if (flyout == ConstrainFlyout.Hidden) return
    var selectedId by remember { mutableStateOf<ConstraintId?>(null) }
    val rows = ConstraintPanelModel.rows(doc, input.analysis, input.technical)
    val card = input.card
    Column(
        modifier.testTag(ConstraintPanelTags.ROOT).padding(horizontal = 16.dp).onPreviewKeyEvent { e ->
            val escape = e.type == KeyEventType.KeyDown && e.key == Key.Escape
            val remove = card?.escape?.takeIf { escape }
            remove?.let(actions.onConflictAction) != null
        },
    ) {
        ConstraintStatusLine(ConstraintPanelModel.status(doc, input.analysis, input.technical))
        ConstrainTrigger(flyout, actions.onConstrain)
        card?.let { ConflictCardView(it, actions.onConflictAction) }
        if (rows.isEmpty()) {
            Text(SketchConstraintStrings.Panel.EMPTY, Modifier.testTag(ConstraintPanelTags.EMPTY))
        } else {
            ConstraintList(rows, selectedId, ConstraintPanelModel.touching(rows, input.selection.mapTo(HashSet()) { it.id }).mapTo(HashSet()) { it.id }, actions) { row ->
                selectedId = row?.id
                actions.onRowSelected(row)
            }
        }
    }
}

private fun selectionHasDimension(doc: SketchDocumentV2, selection: List<SceneElement>): Boolean {
    val ids = selection.mapTo(HashSet()) { it.id }
    return doc.constraints.any { it.isDimensional && it.elementIds.any { id -> id in ids } }
}

/** The status line (UX-31): a live region in one of three states, with the plain-language gloss and a Details expander (UX-83). */
@Composable
private fun ConstraintStatusLine(status: ConstraintStatus?) {
    if (status == null) return
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                status.text,
                Modifier.testTag(ConstraintPanelTags.STATUS).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(ConstraintPanelTags.STATUS_DETAILS),
            ) { Text(SketchConstraintStrings.Panel.DETAILS) }
        }
        if (expanded) Text(DofWording.Explanation, Modifier.testTag(ConstraintPanelTags.EXPLANATION))
    }
}

/** The Constrain trigger and its flyout: items are disabled with their reason, not hidden (UX-37); mnemonics pick an enabled item. */
@Composable
private fun ConstrainTrigger(flyout: ConstrainFlyout, onChoose: (ConstrainMenuItem) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val reason = (flyout as? ConstrainFlyout.Disabled)?.reason
    Column {
        Box {
            TextButton(
                onClick = { open = true },
                enabled = flyout is ConstrainFlyout.Open,
                modifier = Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(ConstraintPanelTags.CONSTRAIN)
                    .semantics { contentDescription = SketchConstraintStrings.Panel.CONSTRAIN_SEMANTICS; reason?.let { stateDescription = it } },
            ) { Text(SketchConstraintStrings.Panel.CONSTRAIN) }
            if (flyout is ConstrainFlyout.Open) {
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    modifier = Modifier.onPreviewKeyEvent { e -> mnemonic(e, flyout)?.also { open = false; onChoose(it) } != null },
                ) {
                    for (state in flyout.items) ConstrainMenuRow(state) { open = false; onChoose(state.item) }
                }
            }
        }
        if (reason != null) Text(reason, Modifier.testTag(ConstraintPanelTags.CONSTRAIN_REASON), style = MaterialTheme.typography.bodySmall)
    }
}

private fun mnemonic(e: KeyEvent, flyout: ConstrainFlyout.Open): ConstrainMenuItem? {
    if (e.type != KeyEventType.KeyDown) return null
    val typed = e.utf16CodePoint.takeIf { it > 0 }?.let { it.toChar().toString() } ?: return null
    return ConstrainMnemonics.pick(typed, flyout.enabled)
}

@Composable
private fun ConstrainMenuRow(state: ConstrainItemState, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text("${state.item.label}  (${state.item.mnemonic})")
                state.reason?.let { Text(it, Modifier.testTag(ConstraintPanelTags.itemReason(state.item)), style = MaterialTheme.typography.bodySmall) }
            }
        },
        enabled = state.enabled,
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(ConstraintPanelTags.item(state.item))
            .semantics { state.reason?.let { stateDescription = it }; if (!state.enabled) disabled() },
    )
}

@Composable
private fun ConstraintList(rows: List<ConstraintRow>, selectedId: ConstraintId?, highlighted: Set<ConstraintId>, actions: ConstraintPanelActions, onSelect: (ConstraintRow?) -> Unit) {
    LazyColumn {
        typedItems(rows, key = { it.id.value }) { row ->
            val selected = row.id == selectedId
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(ConstraintPanelTags.row(row.id))
                    .onKeyEvent { e -> editKey(e, row).also { if (it) actions.onEditDimension(row.id) } }
                    .clickable { onSelect(if (selected) null else row) }
                    .semantics {
                        contentDescription = SketchConstraintStrings.Panel.rowSemantics(row.text, row.state.stateText)
                        role = Role.Button
                        this.selected = selected || row.id in highlighted
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.state.glyph)
                Text(row.text, Modifier.weight(1f))
            }
            if (selected) RowActions(row, actions)
        }
    }
}

/** Enter or F2 on a dimension row edits its value; other rows leave the key alone. */
private fun editKey(e: KeyEvent, row: ConstraintRow): Boolean = row.dimensional && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.F2)

@Composable
private fun RowActions(row: ConstraintRow, actions: ConstraintPanelActions) {
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        if (row.dimensional) {
            val label = if (row.reference) SketchConstraintStrings.Panel.MAKE_DRIVING else SketchConstraintStrings.Panel.MAKE_REFERENCE
            TextButton({ actions.onSetReference(row.id, !row.reference) }, Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(ConstraintPanelTags.MAKE_REFERENCE)) { Text(label) }
        }
        TextButton({ actions.onDelete(row.id) }, Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(ConstraintPanelTags.DELETE)) {
            Text(SketchConstraintStrings.Panel.DELETE)
        }
    }
}

/** The inline card of a refused or loaded conflict: the message in a live region, then the actions (UX-32). */
@Composable
private fun ConflictCardView(card: ConflictCard, onAction: (ConflictAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(ConstraintPanelTags.CARD)) {
        Text(card.message, Modifier.testTag(ConstraintPanelTags.CARD_MESSAGE).semantics { liveRegion = LiveRegionMode.Assertive })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (action in card.actions) {
                TextButton({ onAction(action) }, Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(ConstraintPanelTags.cardAction(action.kind))) {
                    Text(action.label)
                }
            }
        }
    }
}
