// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** The bottom sheet is half or full height (UX-120). */
enum class SheetSize { Half, Full }

enum class VerticalArrow { Up, Down }

/** The rules of the sheet's size buttons; none of them involves a drag (WCAG 2.5.7). */
object SheetSizing {
    /** The handle's tap: half to full, full back to half. */
    fun cycle(size: SheetSize): SheetSize = if (size == SheetSize.Half) SheetSize.Full else SheetSize.Half

    /** Up arrow with the handle focused grows the sheet, Down shrinks it. */
    fun forArrow(size: SheetSize, arrow: VerticalArrow?): SheetSize = when (arrow) {
        VerticalArrow.Up -> SheetSize.Full
        VerticalArrow.Down -> SheetSize.Half
        null -> size
    }

    /** The header button that does what the handle's tap does, named for its result. */
    fun toggleLabel(size: SheetSize): String = if (size == SheetSize.Half) "Expand" else "Collapse"

    /** "Panel size, half height" / "Panel size, full height": the handle's state for a screen reader. */
    fun describe(size: SheetSize): String = if (size == SheetSize.Half) "half height" else "full height"
}

object SheetTags {
    const val HANDLE = "sketch.sheet.handle"
    const val TOGGLE = "sketch.sheet.toggle"
    const val CLOSE = "sketch.sheet.close"
}

/**
 * The header of the bottom sheet: the drag handle that is also a 44 dp "Panel size" button (tap cycles half, full, half;
 * Up and Down resize it while focused), [Expand] or [Collapse], and [Close]. Esc closes. Nothing here needs a drag.
 */
@Composable
fun PanelSheetHeader(size: SheetSize, onSize: (SheetSize) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            val esc = e.type == KeyEventType.KeyDown && e.key == Key.Escape
            if (esc) onClose()
            esc
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(HEADER_TARGET).testTag(SheetTags.HANDLE)
                .onPreviewKeyEvent { e -> arrowResize(e.type, e.key, size, onSize) }
                .semantics { role = Role.Button; contentDescription = "Panel size"; stateDescription = SheetSizing.describe(size) }
                .clickable { onSize(SheetSizing.cycle(size)) }
                .focusable(),
            contentAlignment = Alignment.Center,
        ) { Text("═", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            SheetSizing.toggleLabel(size),
            Modifier.size(width = BUTTON_WIDTH, height = HEADER_TARGET).testTag(SheetTags.TOGGLE).clickable { onSize(SheetSizing.cycle(size)) },
        )
        Text("Close", Modifier.size(width = BUTTON_WIDTH, height = HEADER_TARGET).testTag(SheetTags.CLOSE).clickable(onClick = onClose))
    }
}

private fun arrowResize(type: KeyEventType, key: Key, size: SheetSize, onSize: (SheetSize) -> Unit): Boolean {
    if (type != KeyEventType.KeyDown) return false
    val arrow = when (key) {
        Key.DirectionUp -> VerticalArrow.Up
        Key.DirectionDown -> VerticalArrow.Down
        else -> return false
    }
    val next = SheetSizing.forArrow(size, arrow)
    if (next != size) onSize(next)
    return true
}

private val HEADER_TARGET = 44.dp
private val BUTTON_WIDTH = 88.dp
