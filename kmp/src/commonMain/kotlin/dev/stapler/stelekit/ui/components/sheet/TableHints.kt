// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.components.TableMessages

/** String, comma-separated ids of the one-time tips already shown on this install. */
const val TABLE_HINTS_SEEN_KEY = "table_hints_seen"

/** One-time tips (ux.md 3.9). H2 and H3 are rendered by the sheet through the same component. */
enum class TableHint(val text: String) {
    H1(TableMessages.TIP_SELECT_ROW_COPY),
    H2("Long-press a cell to select a range, or use Extend... in the strip"),
    H3("Keyboard shortcuts are in the menu (top right)"),
}

/** Which tips were shown, per install in [Settings]; a null [Settings] keeps them for this process only. */
class TableHints(private val settings: Settings?) {
    private var seen: Set<String> by mutableStateOf(read())

    private fun read(): Set<String> =
        settings?.getString(TABLE_HINTS_SEEN_KEY, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    fun isSeen(hint: TableHint): Boolean = hint.name in seen

    /** Records [hint] as shown; it never returns until [showAgain]. */
    fun markSeen(hint: TableHint) {
        if (isSeen(hint)) return
        seen = seen + hint.name
        settings?.putString(TABLE_HINTS_SEEN_KEY, seen.sorted().joinToString(","))
    }

    /** "Show tips again": clears the key so every tip can show once more. */
    fun showAgain() {
        seen = emptySet()
        settings?.putString(TABLE_HINTS_SEEN_KEY, "")
    }
}

/** A non-modal chip: never takes focus, announces its text once politely, and goes away on Dismiss. */
@Composable
internal fun TableHintChip(text: String, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp).heightIn(min = 48.dp).testTag("tableHint"),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(TableMessages.DISMISS) }
    }
}
