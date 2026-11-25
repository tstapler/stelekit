// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.AlignBaseline
import dev.stapler.stelekit.sketch.v2.commands.AlignEdge
import dev.stapler.stelekit.sketch.v2.commands.AlignElements
import dev.stapler.stelekit.sketch.v2.commands.DistributeAxis
import dev.stapler.stelekit.sketch.v2.commands.DistributeElements
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.tools.toCommand
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings

object ExtraPanelTags {
    const val ALIGN = "sketch.host.align"
    const val ORDER = "sketch.host.order"
    const val TRANSFORM = "sketch.host.transform"
    const val CLOSE = "sketch.host.panel.close"
    const val LIBRARY = "sketch.host.library"
    const val LIBRARY_SAVE = "sketch.host.library.save"
    const val LIBRARY_EMPTY = "sketch.host.library.empty"
    const val LIBRARY_STARTER = "sketch.host.library.starter"
    const val CONSTRAIN_MENU = "sketch.host.constrainmenu"
    fun align(edge: AlignEdge) = "sketch.host.align.${AlignBaseline.alignIds.getValue(edge)}"
    fun distribute(axis: DistributeAxis) = "sketch.host.distribute.${AlignBaseline.distributeIds.getValue(axis)}"
    fun libraryInsert(id: String) = "sketch.host.library.insert.$id"
    fun libraryDelete(id: String) = "sketch.host.library.delete.$id"
    fun constrain(item: ConstrainMenuItem) = "sketch.host.constrainmenu.${item.name.lowercase()}"
}

private const val TARGET_DP = 44
private const val GAP_DP = 8
private const val HALF = 2.0

/** The panel a selection-menu item opened (Align, Order or Transform), over the canvas; closing it leaves the selection as it was. */
@Composable
internal fun ExtraPanelHost(controller: SketchEditorController, extras: EditorExtras, modifier: Modifier = Modifier) {
    val panel = extras.panel ?: return
    Surface(modifier, tonalElevation = 3.dp) {
        when (panel) {
            ExtraPanel.Align -> AlignPanel(controller) { extras.panel = null }
            ExtraPanel.Order -> OrderPanel(controller) { extras.panel = null }
            ExtraPanel.Transform -> TransformPanel(controller) { extras.panel = null }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlignPanel(controller: SketchEditorController, onClose: () -> Unit) {
    val ids = controller.ui.selection.map(::ElementId)
    Column(Modifier.padding(GAP_DP.dp).testTag(ExtraPanelTags.ALIGN), verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
        Text(SketchHostStrings.Align.TITLE)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            for (edge in AlignEdge.entries) {
                PanelButton(alignLabel(edge), ExtraPanelTags.align(edge)) { controller.submit(AlignElements(ids, edge)) }
            }
            PanelButton(SketchHostStrings.Align.DISTRIBUTE_HORIZONTAL, ExtraPanelTags.distribute(DistributeAxis.Horizontal)) { controller.submit(DistributeElements(ids, DistributeAxis.Horizontal)) }
            PanelButton(SketchHostStrings.Align.DISTRIBUTE_VERTICAL, ExtraPanelTags.distribute(DistributeAxis.Vertical)) { controller.submit(DistributeElements(ids, DistributeAxis.Vertical)) }
        }
        PanelButton(SketchHostStrings.Align.CLOSE, ExtraPanelTags.CLOSE, onClose)
    }
}

private fun alignLabel(edge: AlignEdge): String = when (edge) {
    AlignEdge.Left -> SketchHostStrings.Align.LEFT
    AlignEdge.CenterHorizontal -> SketchHostStrings.Align.CENTER_HORIZONTAL
    AlignEdge.Right -> SketchHostStrings.Align.RIGHT
    AlignEdge.Top -> SketchHostStrings.Align.TOP
    AlignEdge.CenterVertical -> SketchHostStrings.Align.CENTER_VERTICAL
    AlignEdge.Bottom -> SketchHostStrings.Align.BOTTOM
}

@Composable
private fun OrderPanel(controller: SketchEditorController, onClose: () -> Unit) {
    Column(Modifier.padding(GAP_DP.dp).testTag(ExtraPanelTags.ORDER), verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
        Text(SketchHostStrings.Order.TITLE)
        ArrangeControls(controller)
        PanelButton(SketchHostStrings.Order.CLOSE, ExtraPanelTags.CLOSE, onClose)
    }
}

/** Group, ungroup and z-order buttons for the selection, running the same plans as the keys. */
@Composable
internal fun ArrangeControls(controller: SketchEditorController, modifier: Modifier = Modifier) {
    ArrangeBar(
        scene = controller.doc.scene,
        selection = controller.ui.selection.map(::ElementId),
        env = controller.keymap,
        ids = controller.ids,
        onPlan = { command, skipped ->
            controller.submit(command)
            controller.notifier.lockedSkipped(skipped.size)
        },
        modifier = modifier,
    )
}

/** Transform and Bind end: the non-drag panel moves, resizes and rotates the selection, binds an arrow end and picks by marquee. */
@Composable
private fun TransformPanel(controller: SketchEditorController, onClose: () -> Unit) {
    val callbacks = remember(controller, onClose) {
        NonDragCallbacks(
            onCommand = { command -> command.toCommand().fold(controller.notifier::refuse) { controller.submit(it) } },
            onNotice = { notice, _ -> controller.notifier.toast(SketchHostStrings.Transform.COMMITTED_KEY, notice.text) },
            onExit = onClose,
            onSelect = controller.tools::select,
        )
    }
    val size = controller.view.canvasSize
    val at = controller.ui.viewport.toScene(ScreenPx(size.x / HALF, size.y / HALF))
    Column(Modifier.testTag(ExtraPanelTags.TRANSFORM)) {
        NonDragTransformPanel(controller.doc.scene, controller.ui.selection.map(::ElementId), callbacks, Modifier.fillMaxWidth(), at.x.value, at.y.value)
        PanelButton(SketchHostStrings.Transform.CLOSE, ExtraPanelTags.CLOSE, onClose)
    }
}

/** The Library tab: saved selections that insert as fresh copies, bounded by the library caps (the exact message shows when one is hit). */
@Composable
internal fun LibraryPanel(controller: SketchEditorController, extras: EditorExtras, modifier: Modifier = Modifier) {
    val library = extras.library
    Column(modifier.padding(horizontal = 16.dp).testTag(ExtraPanelTags.LIBRARY), verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
        TextButton(
            onClick = extras.library::saveSelection,
            enabled = controller.ui.selection.isNotEmpty() && !controller.readOnly,
            modifier = Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(ExtraPanelTags.LIBRARY_SAVE),
        ) { Text(SketchHostStrings.Library.SAVE_SELECTION) }
        if (library.items.isEmpty()) Text(SketchHostStrings.Library.EMPTY, Modifier.testTag(ExtraPanelTags.LIBRARY_EMPTY))
        for (item in library.items) {
            val name = item.name ?: item.id
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(SketchHostStrings.Library.itemDescription(name, item.elements.size), Modifier.weight(1f))
                PanelButton(SketchHostStrings.Library.INSERT, ExtraPanelTags.libraryInsert(item.id)) { extras.library.insert(item.id) }
                PanelButton(SketchHostStrings.Library.DELETE, ExtraPanelTags.libraryDelete(item.id)) { extras.library.delete(item.id) }
            }
        }
        Text(SketchHostStrings.Library.STARTER_TITLE, Modifier.testTag(ExtraPanelTags.LIBRARY_STARTER))
        for (item in library.starterItems) {
            val name = item.name ?: item.id
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f))
                PanelButton(SketchHostStrings.Library.INSERT, ExtraPanelTags.libraryInsert(item.id)) { extras.library.insert(item.id) }
            }
        }
    }
}

/** The Constrain menu the K key and the strip button open: enabled entries arm the picker, the rest show why they cannot (UX-37, UX-111). */
@Composable
internal fun ConstrainMenuDialog(controller: SketchEditorController, extras: EditorExtras) {
    val selection = controller.ui.selection.mapNotNull { controller.doc.scene[ElementId(it)] }
    val open = ConstrainFlyoutModel.of(selection, extras.tier) as? ConstrainFlyout.Open
    val close = { controller.overlay = null }
    if (open == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val choose = { item: ConstrainMenuItem ->
        controller.tools.chooseTool(ToolId.Select)
        controller.constraints.arm(item)
        close()
    }
    AlertDialog(
        onDismissRequest = close,
        modifier = Modifier.testTag(ExtraPanelTags.CONSTRAIN_MENU).onPreviewKeyEvent { e -> mnemonicOf(e, open)?.also(choose) != null },
        title = { Text(SketchHostStrings.ConstrainMenu.TITLE) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                for (state in open.items) {
                    TextButton(
                        onClick = { choose(state.item) },
                        enabled = state.enabled,
                        modifier = Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(ExtraPanelTags.constrain(state.item)),
                    ) { Text(state.reason?.let { "${state.item.label} ($it)" } ?: "${state.item.label} (${state.item.mnemonic})") }
                }
            }
        },
        confirmButton = { PanelButton(SketchHostStrings.ConstrainMenu.CLOSE, ExtraPanelTags.CLOSE, close) },
    )
}

private fun mnemonicOf(e: KeyEvent, flyout: ConstrainFlyout.Open): ConstrainMenuItem? {
    if (e.type != KeyEventType.KeyDown) return null
    val typed = e.utf16CodePoint.takeIf { it > 0 }?.toChar()?.toString() ?: return null
    return ConstrainMnemonics.pick(typed, flyout.enabled)
}

@Composable
private fun PanelButton(label: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.defaultMinSize(minHeight = TARGET_DP.dp, minWidth = TARGET_DP.dp).testTag(tag)) { Text(label) }
}
