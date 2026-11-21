// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.tools.AddElementCommand
import dev.stapler.stelekit.sketch.v2.tools.AddFrameCommand
import dev.stapler.stelekit.sketch.v2.tools.TextEditSession
import dev.stapler.stelekit.sketch.v2.tools.TextTool
import dev.stapler.stelekit.sketch.v2.tools.ToolCommand
import dev.stapler.stelekit.sketch.v2.tools.ToolContext
import dev.stapler.stelekit.sketch.v2.tools.ToolPreview
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

/**
 * Keyboard-only access to the canvas (Task 3.6.3c, UX-107, WCAG 2.1.1): Crosshair mode drives the active drawing tool with
 * the keys (Enter starts it, arrows move, Enter places, Esc backs out one row) or with the pad's buttons, and Esc disarms
 * an armed constraint picker (row 5). The drawing logic is [CrosshairDrawing]; this holds its state for one editor, turns
 * what it commits into an ordinary undoable edit and reports the Esc rows it owns. Platform-free.
 */
class EditorKeyboardAccess(private val controller: SketchEditorController) {
    private var session: CrosshairDrawing? by mutableStateOf(null)

    /** The shape being placed by the keyboard, drawn like a pointer gesture's preview. */
    var preview: ToolPreview? by mutableStateOf(null)
        private set

    private var clock = 0L

    /** The Crosshair while it is on; it ends when another tool is chosen. */
    val crosshair: CrosshairDrawing? get() = session?.takeIf { it.toolId == controller.ui.activeTool }

    /** The text edit a Crosshair-placed Text tool has open, shown by the host's text overlay. */
    val textSession: TextEditSession? get() = (crosshair?.tool as? TextTool.Editing)?.session

    /** True when the active tool has a keyboard path and Crosshair is not already on, so the chip can start it. */
    val canStart: Boolean
        get() = !controller.readOnly && crosshair == null && KeyboardDrawingPolicy.ruleOf(controller.ui.activeTool) != null

    /** The Esc rows of the Crosshair (text overlay, mid-draw, Crosshair); the armed picker (row 5) is the controller's. */
    fun escRows(): Set<EscRow> = crosshair?.escRows().orEmpty()

    /** The Crosshair chip of the action chip row: starts Crosshair mode for the active tool. */
    fun chips(): List<ActionChip> =
        if (canStart) listOf(ActionChip(CHIP_TAG, SketchStrings.KeyboardDrawing.CHIP, SketchStrings.KeyboardDrawing.CHIP_TAG_NAME) { start() }) else emptyList()

    /** Starts Crosshair mode at the canvas centre; false when the active tool has no keyboard path. */
    fun start(): Boolean {
        if (!canStart) return false
        val tool = controller.newTool(controller.ui.activeTool) ?: return false
        val size = controller.view.canvasSize
        val centre = controller.ui.viewport.toScene(ScreenPx(size.x / HALF, size.y / HALF))
        session = CrosshairDrawing.begin(tool, centre) ?: return false
        preview = null
        controller.notifier.announce(SketchStrings.KeyboardDrawing.STARTED)
        return true
    }

    /** Ends Crosshair mode (the pad's exit button); nothing in progress is committed. */
    fun exit() {
        session = null
        preview = null
        controller.notifier.announce(SketchStrings.KeyboardDrawing.ENDED)
    }

    /** One of the pad's buttons or a key: [key] goes to the Crosshair. */
    fun press(key: CrosshairKey) {
        val live = crosshair ?: return
        val step = live.press(key, toolContext(), nextClock())
        session = step.session
        preview = step.preview
        step.notice?.let(controller.notifier::toolNotice)
        step.command?.let(::commit)
        step.announcement?.let(controller.notifier::announce)
    }

    /** The text overlay's Done: Esc commits a non-empty text and drops an empty one (D-100). */
    fun commitText() = press(CrosshairKey.Escape)

    /**
     * Handles a canvas key: Crosshair keys while it is on, Enter to start it, Esc for an armed picker. [rows] is every Esc row in
     * effect, so Esc acts here only when the first row of the one order is one of ours. True when the key was used.
     */
    fun onKey(keyPress: KeyPress, focus: KeyFocus, rows: () -> Set<EscRow>): Boolean {
        val key = crosshairKeyOf(keyPress, focus)
        val live = crosshair
        if (live != null) return key != null && ownsKey(key, live, rows).also { owns -> if (owns) press(key) }
        session = null
        return !focus.textInputOrDialogFocused && (disarmPicker(key, rows) || startsOnEnter(key))
    }

    /** Every key is the Crosshair's while it is on, except an Esc that an earlier row of the one order (a dialog, a picker) takes first. */
    private fun ownsKey(key: CrosshairKey, live: CrosshairDrawing, rows: () -> Set<EscRow>): Boolean =
        key != CrosshairKey.Escape || EscOrder.resolve(rows()) in live.escRows()

    /** The Crosshair key [keyPress] is while Crosshair mode is on (arrows, Enter, Esc), by the dispatcher's own table; null for any other key. */
    private fun crosshairKeyOf(keyPress: KeyPress, focus: KeyFocus): CrosshairKey? =
        ((KeyDispatcher.dispatch(keyPress, keyContext(focus)) as? KeyOutcome.Handled)?.action as? KeyAction.Crosshair)?.key

    private fun startsOnEnter(key: CrosshairKey?): Boolean = key == CrosshairKey.Enter && controller.tools.textSession == null && start()

    private fun disarmPicker(key: CrosshairKey?, rows: () -> Set<EscRow>): Boolean {
        if (key != CrosshairKey.Escape || EscOrder.resolve(rows()) != EscRow.ArmedPicker) return false
        controller.constraints.cancel()
        return true
    }

    private fun keyContext(focus: KeyFocus) = KeyContext(
        env = controller.keymap,
        singleKeyEnabled = focus.singleKeyEnabled,
        canvasFocused = focus.canvasFocused,
        textInputOrDialogFocused = focus.textInputOrDialogFocused,
        hasSelection = controller.ui.selection.isNotEmpty(),
        crosshairActive = true,
    )

    private fun toolContext() =
        ToolContext(controller.doc.scene, controller.ui.viewport.zoom, controller.defaultStyle, controller.ids, controller.spatialIndex, controller.view.pxPerDp)

    private fun commit(command: ToolCommand) {
        controller.commitToolCommand(command)
        val added = when (command) {
            is AddElementCommand -> command.element
            is AddFrameCommand -> command.frame
            else -> null
        } ?: return
        controller.notifier.announce(CrosshairDrawing.announceAdded(controller.doc.scene[added.id] ?: added))
    }

    private fun nextClock(): Long = clock.also { clock += CLOCK_STEP_MS }

    /** The crosshair's place in the scene, for the marker. */
    val point: ScenePoint? get() = crosshair?.crosshair

    private companion object {
        const val CHIP_TAG = "sketch.chip.crosshair"
        const val HALF = 2.0
        const val CLOCK_STEP_MS = 1_000L
    }
}
