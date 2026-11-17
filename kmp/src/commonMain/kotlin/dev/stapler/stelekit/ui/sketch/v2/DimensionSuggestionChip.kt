// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.constraints.SolverTier
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.DimensionSuggestion
import dev.stapler.stelekit.sketch.v2.constraints.DimensionSuggestions
import dev.stapler.stelekit.sketch.v2.constraints.SolverTierGate
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings

object DimensionChipTags {
    const val CHIP = "sketch.dimension.chip"
    const val ACTION = "sketch.dimension.chip.action"
}

/** What the smart-dimension aid shows for a selection on a given solver tier (Task 4.2.7d, UX-115, D-21). */
object DimensionChips {
    /** The suggestible kinds a tier exposes: `GeometricOnly` has Distance but no Radius, and `Hidden` has none (ADR-003). */
    fun exposedKinds(tier: SolverTier): Set<ConstraintKind> = DimensionSuggestions.DefaultKinds.filterTo(HashSet()) { SolverTierGate.exposes(tier, it) }

    /**
     * The chip for [selection], or null when nothing natural can be dimensioned (text, an arrow, two lines), the tier hides
     * constraint UI, or the constraint model cannot hold the dimension. With nothing selected and [degreesOfFreedom] above zero
     * it is the "Suggested: Width of Line 1" chip for the first unconstrained length or radius.
     */
    fun suggestion(doc: SketchDocumentV2, selection: List<ElementId>, degreesOfFreedom: Int?, tier: SolverTier, newId: () -> ConstraintId): DimensionSuggestion? {
        if (tier == SolverTier.Hidden) return null
        return DimensionSuggestions.suggest(doc, selection, degreesOfFreedom, newId, exposedKinds(tier))?.takeIf { it.addable }
    }

    /** The chip as an entry of the action chip row (touch has no Enter or D): its label is the suggestion and the action word. */
    fun actionChip(suggestion: DimensionSuggestion, onAccept: () -> Unit): ActionChip {
        val label = "${suggestion.label} ${SketchConstraintStrings.Dimension.CHIP_ACTION}"
        return ActionChip(DimensionChipTags.CHIP, label, SketchConstraintStrings.Dimension.chipName(suggestion.label), onAccept)
    }
}

/**
 * The suggestion chip, for a canvas overlay outside the action chip row: "Length 6 in? Dimension". Tap, click, or Enter or D
 * while it has focus accepts it, which only opens the label editor: the chip never adds a constraint until a typed value is
 * committed (UX-115).
 */
@Composable
fun DimensionSuggestionChip(suggestion: DimensionSuggestion, onAccept: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.testTag(DimensionChipTags.CHIP)
            .defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .onKeyEvent { e -> acceptKey(e.type, e.key).also { if (it) onAccept() } }
            .focusable()
            .clickable(onClick = onAccept)
            .semantics { contentDescription = SketchConstraintStrings.Dimension.chipName(suggestion.label); role = Role.Button }
            .padding(horizontal = CHIP_PAD_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(suggestion.label, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(SketchConstraintStrings.Dimension.CHIP_ACTION, Modifier.testTag(DimensionChipTags.ACTION), color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private fun acceptKey(type: KeyEventType, key: Key): Boolean = type == KeyEventType.KeyDown && (key == Key.Enter || key == Key.NumPadEnter || key == Key.D)

private const val CHIP_PAD_DP = 12
private const val CHIP_GAP_DP = 8
