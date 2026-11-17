// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.ui.components.typedItems
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings

/** One line of the "Pick target" list: an element's name as a heading, or one of its targets. */
internal sealed interface TargetListRow {
    val key: String

    data class Heading(val name: String) : TargetListRow {
        override val key: String get() = "heading:$name"
    }

    data class Entry(val candidate: TargetCandidate) : TargetListRow {
        override val key: String get() = candidate.key
    }
}

/** The candidates grouped by element name, in cycle order ("Line 3 start", "Line 3 end", "Line 3 edge", "Circle 1 centre"). */
internal fun listRowsOf(candidates: List<TargetCandidate>): List<TargetListRow> =
    candidates.groupBy { it.target.elementId }.values.flatMap { group -> listOf<TargetListRow>(TargetListRow.Heading(group.first().group)) + group.map { TargetListRow.Entry(it) } }

/**
 * "Pick target": a list of every target of the selected and nearby elements, by name, grouped by element (Task 4.2.6b, UX-98).
 * The screen-reader and keyboard-free path: choosing a row is a pick, exactly as clicking its marker. The list is virtualised,
 * so a sheet of hundreds of targets costs only the rows in view.
 */
@Composable
fun TargetListPicker(candidates: List<TargetCandidate>, onPick: (ConstraintTarget) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        TextButton(
            onClick = { open = !open },
            modifier = Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(PickerTags.LIST_BUTTON),
        ) { Text(SketchConstraintStrings.Picker.PICK_TARGET) }
        if (open) {
            val rows = remember(candidates) { listRowsOf(candidates) }
            LazyColumn(
                Modifier.heightIn(max = LIST_MAX_DP.dp).testTag(PickerTags.LIST)
                    .semantics { contentDescription = SketchConstraintStrings.Picker.LIST_REGION },
            ) {
                typedItems(rows, key = { it.key }, contentType = { it::class }) { row -> ListRow(row, onPick) }
            }
        }
    }
}

@Composable
private fun ListRow(row: TargetListRow, onPick: (ConstraintTarget) -> Unit) {
    when (row) {
        is TargetListRow.Heading -> Text(
            row.name,
            Modifier.padding(horizontal = ROW_PAD_DP.dp).semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
        )
        is TargetListRow.Entry -> Text(
            row.candidate.listName,
            Modifier.fillMaxWidth().defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(PickerTags.listRow(row.candidate.key))
                .clickable { onPick(row.candidate.target) }.padding(horizontal = ROW_PAD_DP.dp).semantics { role = Role.Button },
        )
    }
}

private const val LIST_MAX_DP = 240
private const val ROW_PAD_DP = 16
