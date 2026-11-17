// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.tools.ToolId

/** Where keyboard focus is when a key arrives. */
data class KeyFocus(
    val canvasFocused: Boolean = true,
    val textInputOrDialogFocused: Boolean = false,
    /** The `SingleKeyShortcuts` setting (WCAG 2.1.4). */
    val singleKeyEnabled: Boolean = true,
)

/** What a decided key does to the editor; the controller names each effect once. */
class KeyEffects(
    val chooseTool: (ToolId) -> Unit,
    val undo: () -> Unit,
    val redo: () -> Unit,
    val escape: () -> Unit,
    val open: (EditorOverlay) -> Unit,
    val submit: (Command) -> Unit,
    val lockedSkipped: (Int) -> Unit,
    val view: (KeyAction) -> Boolean,
    val textEditOpen: () -> Boolean,
)

/**
 * Routes a key press through [KeyDispatcher] and runs what it decided (Task 6.2.0a). Edits come from [KeyActionPlanner], so a
 * locked element or an element on a locked layer is skipped, never moved or deleted.
 */
class EditorKeys(
    private val ui: SketchEditorState,
    private val ports: EditorPorts,
    private val doc: () -> SketchDocumentV2,
    private val effects: KeyEffects,
) {
    /**
     * Returns true when the key was ours. [focus] says what holds keyboard focus: while a text field or a dialog does, nothing fires
     * (UX-22).
     */
    fun onKey(press: KeyPress, focus: KeyFocus = KeyFocus()): Boolean {
        val context = KeyContext(
            env = ports.keymap,
            singleKeyEnabled = focus.singleKeyEnabled,
            canvasFocused = focus.canvasFocused,
            textInputOrDialogFocused = focus.textInputOrDialogFocused || effects.textEditOpen(),
            hasSelection = ui.selection.isNotEmpty(),
            handToolActive = ui.activeTool == ToolId.Hand,
        )
        return when (val outcome = KeyDispatcher.dispatch(press, context)) {
            is KeyOutcome.Handled -> run(outcome.action)
            else -> false
        }
    }

    /** Runs one decided key action; false for an action this editor does not own (clipboard, grid, region focus). */
    fun run(action: KeyAction): Boolean {
        val edit = KeyActionPlanner.plan(action, doc().scene, ui.selection.map(::ElementId), ports.ids)
        val command = edit.command
        return when {
            action is KeyAction.SelectTool -> true.also { effects.chooseTool(ToolId.valueOf(action.tool.name)) }
            action == KeyAction.ToolLock -> true.also { ui.toolLock = !ui.toolLock }
            action == KeyAction.Undo -> true.also { effects.undo() }
            action == KeyAction.Redo -> true.also { effects.redo() }
            action == KeyAction.Escape -> true.also { effects.escape() }
            action == KeyAction.OpenShortcuts -> true.also { effects.open(EditorOverlay.Shortcuts) }
            action == KeyAction.OpenGoToElement -> true.also { effects.open(EditorOverlay.GoToElement) }
            action == KeyAction.OpenConstrainMenu -> true.also { effects.open(EditorOverlay.ConstrainMenu) }
            command != null -> true.also { effects.submit(command) }
            action is KeyAction.Delete || action is KeyAction.Nudge || action is KeyAction.Arrange -> true.also { effects.lockedSkipped(edit.skippedLocked.size) }
            else -> effects.view(action)
        }
    }
}
