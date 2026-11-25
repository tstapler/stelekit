// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.ui.WindowSizeClass
import dev.stapler.stelekit.ui.components.typedItems
import dev.stapler.stelekit.ui.LocalWindowSizeClass

/** What a column, row or cell menu was opened for (ux.md S8, S9). */
internal sealed interface SheetMenuTarget {
    data class Column(val col: Int) : SheetMenuTarget
    data class Row(val row: Int) : SheetMenuTarget
    data class Cell(val address: CellAddress) : SheetMenuTarget
}

/**
 * One menu entry. A disabled entry keeps its place and says why in [reason]; [helper] lines state what choosing it
 * will do before it is chosen (the sort's rewritten-formula count). [checked] is non-null for toggles.
 */
internal data class SheetMenuItem(
    val id: String,
    val label: String,
    val action: () -> Unit,
    val enabled: Boolean = true,
    val reason: String? = null,
    val helper: List<String> = emptyList(),
    val checked: Boolean? = null,
)

internal data class SheetMenuModel(val title: String, val items: List<SheetMenuItem>)

/** The popup form (anchored at the header, gutter or cell) for windows of 600dp and up; compact windows use [SheetBottomMenu]. */
@Composable
internal fun SheetAnchoredMenu(state: SheetState, target: SheetMenuTarget) {
    if (LocalWindowSizeClass.current == WindowSizeClass.COMPACT || state.menu != target) return
    val model = state.sink.menuFor(target) ?: return
    DropdownMenu(expanded = true, onDismissRequest = state::closeMenu, modifier = Modifier.testTag("sheetMenu")) {
        model.items.forEach { item ->
            DropdownMenuItem(
                text = { MenuItemText(item) },
                enabled = item.enabled,
                onClick = { state.closeMenu(); item.action() },
                modifier = Modifier.testTag("menu-${item.id}").menuSemantics(item),
            )
        }
    }
}

/** The bottom-sheet form below 600dp; a scrim tap, Back and Esc close it through the one ladder. */
@Composable
internal fun SheetBottomMenu(state: SheetState) {
    val target = state.menu ?: return
    if (LocalWindowSizeClass.current != WindowSizeClass.COMPACT) return
    val model = state.sink.menuFor(target) ?: return
    Box(Modifier.fillMaxSize().testTag("sheetBottomMenu")) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = state::closeMenu).testTag("menuScrim"))
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Column {
                Text(model.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    typedItems(model.items, key = { it.id }) { item ->
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(enabled = item.enabled) { state.closeMenu(); item.action() }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("menu-${item.id}")
                                .menuSemantics(item),
                            contentAlignment = Alignment.CenterStart,
                        ) { MenuItemText(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItemText(item: SheetMenuItem) {
    Column {
        val mark = when (item.checked) {
            true -> "[x] "
            false -> "[ ] "
            null -> ""
        }
        Text(mark + item.label, color = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        item.helper.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("menuHelper")) }
        item.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("menuReason")) }
    }
}

private fun Modifier.menuSemantics(item: SheetMenuItem): Modifier = semantics {
    role = Role.Button
    if (!item.enabled) disabled()
    item.checked?.let { stateDescription = if (it) "On" else "Off" }
}
