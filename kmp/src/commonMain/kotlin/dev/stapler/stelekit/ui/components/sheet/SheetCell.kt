// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.stapler.stelekit.table.CellAddress

/** Colors a sheet cell draws with; resolved once per grid so a row does not re-read the theme per cell. */
internal class SheetColors(
    val header: Color,
    val zebra: Color,
    val divider: Color,
    val formulaTint: Color,
    val outline: Color,
    val link: Color,
)

/**
 * One body or header cell. Non-editing cells show the computed value (`fx` marker and tint for formulas); the cell
 * being edited mirrors the draft text, and the edit bar owns the live field (the conservative pinned-editor default,
 * UNVERIFIED on device until spike 0.4a). Hover over an error cell shows its reason in a dismissible popup.
 */
@Composable
@Suppress("LongParameterList")
internal fun SheetCell(
    state: SheetState,
    hits: CellHitRegistry,
    address: CellAddress,
    width: Dp,
    textAlign: TextAlign,
    colors: SheetColors,
    modifier: Modifier = Modifier,
) {
    val isHeader = address.row == 1
    val editing = state.editingAddress == address
    val display = state.display(address)
    val edges = if (editing) OutlineEdges.None else outlineEdges(state.selection, address)
    val background = when {
        isHeader -> colors.header
        display is CellDisplay.Formula || display is CellDisplay.Failed -> colors.formulaTint
        address.row % 2 == 0 -> colors.zebra
        else -> Color.Transparent
    }
    DisposableEffect(address) { onDispose { hits.remove(address) } }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var popupDismissed by remember { mutableStateOf(false) }
    val hoverDetail = if (hovered && !popupDismissed) state.detailFor(address) else null
    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .drawBehind {
                drawRect(background)
                drawLine(colors.divider, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
                drawLine(colors.divider, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
            }
            .selectionOutline(edges, colors.outline, OutlineWidths.of(LocalTableVisualPrefs.current))
            .onGloballyPositioned { hits.setCell(address, it) }
            .hoverable(hover)
            .testTag("cell-$address")
            .semantics(mergeDescendants = !editing) { sheetCellSemantics(state, address, display) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val textColor = if (isHeader) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        if (editing) {
            Text(
                text = state.fieldValue.text,
                color = textColor,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth().testTag("cellDraft"),
            )
        } else {
            CellContent(display, address, hits, textColor, colors.link, textAlign, if (isHeader) FontWeight.Bold else null)
        }
        SheetAnchoredMenu(state, SheetMenuTarget.Cell(address))
        if (hoverDetail != null) {
            Popup(
                alignment = androidx.compose.ui.Alignment.BottomStart,
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
                onDismissRequest = { popupDismissed = true },
            ) {
                Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.inverseSurface) {
                    Text(
                        hoverDetail,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(8.dp).testTag("errorPopup"),
                    )
                }
            }
        }
    }
}
