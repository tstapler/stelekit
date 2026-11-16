// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

object ShortcutsSheetTags {
    const val SHEET = "sketch.shortcuts.sheet"
    const val CLOSE = "sketch.shortcuts.close"
    const val SETTING = "sketch.shortcuts.setting"
    const val FOOTNOTE = "sketch.shortcuts.footnote"
    fun row(id: String) = "sketch.shortcuts.row.$id"
}

/**
 * The shortcuts sheet (Task 3.6.1b): opened by `?` or the overflow entry, closed by Esc or Close, after which the host
 * returns focus to the canvas (UX-42). The "Single-key shortcuts" switch is the WCAG 2.1.4 control.
 */
@Composable
fun ShortcutsSheet(
    model: ShortcutsSheetModel,
    onSingleKeyChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(ShortcutsSheetModel.TITLE, modifier = Modifier.semantics { heading() }) },
        text = {
            LazyColumn(Modifier.heightIn(max = SHEET_MAX_HEIGHT).testTag(ShortcutsSheetTags.SHEET)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().testTag(ShortcutsSheetTags.SETTING)
                            .toggleable(model.singleKeyEnabled, role = Role.Switch, onValueChange = onSingleKeyChanged),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(ShortcutsSheetModel.SETTING_LABEL)
                        Switch(checked = model.singleKeyEnabled, onCheckedChange = null)
                    }
                }
                for (section in model.sections) {
                    item { Text(section.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() }) }
                    items(section.rows, key = { "${section.title}.${it.id}" }) { row ->
                        Row(
                            Modifier.fillMaxWidth().testTag(ShortcutsSheetTags.row(row.id)).semantics(mergeDescendants = true) {
                                contentDescription = "${row.label}: ${row.keys.joinToString(", ")}"
                            },
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(row.label, Modifier.weight(1f))
                            Text(row.keys.joinToString("  "), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    for (note in section.notes) item { Text(note, style = MaterialTheme.typography.bodySmall) }
                }
                model.footnote?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(ShortcutsSheetTags.FOOTNOTE)) } }
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 44.dp).testTag(ShortcutsSheetTags.CLOSE)) { Text("Close") } },
    )
}

private val SHEET_MAX_HEIGHT = 420.dp

/** Maps a Compose key event to the pure [KeyPress]; null for a key-up or a key with no produced character or name. */
fun KeyEvent.toKeyPress(): KeyPress? {
    if (type != KeyEventType.KeyDown) return null
    val named = NAMED_KEYS[key]
    val produced = named ?: utf16CodePoint.takeIf { it > 0 }?.let { it.toChar().toString() }
    val physical = when (key) {
        Key.LeftBracket -> PhysicalKey.BracketLeft
        Key.RightBracket -> PhysicalKey.BracketRight
        else -> null
    }
    return KeyPress(produced, physical, ctrl = isCtrlPressed, meta = isMetaPressed, shift = isShiftPressed, alt = isAltPressed)
}

private val NAMED_KEYS: Map<Key, String> = mapOf(
    Key.DirectionUp to "ArrowUp", Key.DirectionDown to "ArrowDown", Key.DirectionLeft to "ArrowLeft", Key.DirectionRight to "ArrowRight",
    Key.Delete to "Delete", Key.Backspace to "Backspace", Key.Escape to "Escape", Key.F6 to "F6",
    Key.PageUp to "PageUp", Key.PageDown to "PageDown", Key.MoveHome to "Home",
)
