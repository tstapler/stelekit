// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.SketchViewport

/**
 * The input the user last used: hint and walkthrough texts follow it (UX-124, ux.md section 22). Touch covers touch and pen
 * with no hardware keyboard; a key press switches to [Keyboard], a pointer move away from it to [Mouse] or [TouchOrPen].
 */
enum class InputKind { Mouse, TouchOrPen, Keyboard }

/** The panel tabs, ONE list (ux.md section 1): Layers is hidden until a second layer exists. */
enum class PanelTab { Style, Layers, Constraints, Elements, Library, Technical }

/**
 * Editor state that must survive a fold or unfold, a rotation and a resize (UX-93, Story 3.1.3): the active tool, tool
 * lock, the open panel tab, the selection, the zoom and pan, the text overlay's contents and the focused control.
 * It is owned above the adaptive layout, so switching the layout never recreates it, and [Saver] keeps it across an
 * activity recreation. Pure Compose state: no scope, no platform types.
 */
class SketchEditorState(
    activeTool: ToolId = ToolId.Select,
    toolLock: Boolean = false,
    panelTab: PanelTab? = null,
    selection: Set<String> = emptySet(),
    viewport: SketchViewport = SketchViewport.of(),
    textDraft: String? = null,
    fingerDrawing: Boolean = false,
    largerTouchHandles: Boolean = true,
    focusedControl: String? = null,
    sheetSize: SheetSize = SheetSize.Half,
) {
    var activeTool by mutableStateOf(activeTool)
    var toolLock by mutableStateOf(toolLock)
    var panelTab by mutableStateOf(panelTab)
    var selection by mutableStateOf(selection)
    var viewport by mutableStateOf(viewport)

    /** The text being edited in place, or null when no edit is open. */
    var textDraft by mutableStateOf(textDraft)
    var fingerDrawing by mutableStateOf(fingerDrawing)
    var largerTouchHandles by mutableStateOf(largerTouchHandles)

    /** The magnifier bubble that shows beside a finger driving a tool; on by default, never shown for a mouse or a pen. Not saved. */
    var touchLoupe by mutableStateOf(true)

    /** Key of the strip control that last had focus; the next layout gives it focus again. */
    var focusedControl by mutableStateOf(focusedControl)

    /** The bottom sheet is half or full height (UX-120); survives a fold or rotation like the open tab. */
    var sheetSize by mutableStateOf(sheetSize)

    /** The pan pad is open (opened from the Pan button or the overflow menu; never saved, a recreation closes it). */
    var panPadOpen by mutableStateOf(false)

    /** The input last used; not saved. */
    var lastInput by mutableStateOf(InputKind.Mouse)

    /** The first-run walkthrough (Task 3.6.8e); not saved, a recreation ends it. */
    val walkthrough = WalkthroughState()

    /** The touch and keyboard equivalent of hover tooltips (UX-90); not saved, a popup never survives a recreation. */
    val help = HelpPopupState()

    /** The tool each group's main target activates: the last one chosen from that group. */
    val lastUsedInGroup = mutableStateMapOf<StripGroup, ToolId>()

    /** Picks [tool]; the strip's single entry point so lock and group memory stay in one place. */
    fun chooseTool(tool: ToolId) {
        activeTool = tool
        StripGroup.entries.firstOrNull { tool in it.tools }?.let { lastUsedInGroup[it] = tool }
    }

    /** A shape was committed: the tool returns to Select unless tool lock is on (UX-04). */
    fun onShapeCommitted() {
        activeTool = ToolLockPolicy.toolAfterCommit(activeTool, toolLock)
    }

    companion object {
        val Saver: Saver<SketchEditorState, Any> = listSaver(
            save = {
                listOf(
                    it.activeTool.name, it.toolLock, it.panelTab?.name ?: "", it.selection.toList().joinToString("\u0000"),
                    it.viewport.zoom, it.viewport.panX, it.viewport.panY, it.textDraft ?: NO_TEXT,
                    it.fingerDrawing, it.largerTouchHandles, it.focusedControl ?: "", it.sheetSize.name,
                )
            },
            restore = {
                SketchEditorState(
                    activeTool = ToolId.valueOf(it[0] as String),
                    toolLock = it[1] as Boolean,
                    panelTab = (it[2] as String).takeIf { name -> name.isNotEmpty() }?.let(PanelTab::valueOf),
                    selection = (it[3] as String).split('\u0000').filter { id -> id.isNotEmpty() }.toSet(),
                    viewport = SketchViewport.of(it[4] as Double, it[5] as Double, it[6] as Double),
                    textDraft = (it[7] as String).takeIf { text -> text != NO_TEXT },
                    fingerDrawing = it[8] as Boolean,
                    largerTouchHandles = it[9] as Boolean,
                    focusedControl = (it[10] as String).takeIf { key -> key.isNotEmpty() },
                    sheetSize = (it.getOrNull(11) as? String)?.let(SheetSize::valueOf) ?: SheetSize.Half,
                )
            },
        )

        /** Marks "no text edit open"; a lone private-use character cannot be typed, so it never collides with a draft. */
        private const val NO_TEXT = ""
    }
}

@Composable
fun rememberSketchEditorState(largerTouchHandles: Boolean = true): SketchEditorState =
    rememberSaveable(saver = SketchEditorState.Saver) { SketchEditorState(largerTouchHandles = largerTouchHandles) }
