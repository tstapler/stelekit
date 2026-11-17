// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.ZOrderMove

/** What a key press asks the editor to do; the host turns each into a command, a tool change or a view change. */
sealed interface KeyAction {
    data class SelectTool(val tool: SketchToolId) : KeyAction
    data object ToolLock : KeyAction
    data object Undo : KeyAction
    data object Redo : KeyAction
    data object Copy : KeyAction
    data object Paste : KeyAction
    data object Cut : KeyAction
    data object Duplicate : KeyAction
    data object Group : KeyAction
    data object Ungroup : KeyAction
    data class Arrange(val move: ZOrderMove) : KeyAction
    data object Delete : KeyAction

    /** Nudge the selection by [dx], [dy] scene units ([far] is the Shift step of 10 units). */
    data class Nudge(val dx: Int, val dy: Int, val far: Boolean) : KeyAction

    /** Pan the view by one step of the direction ([dx], [dy] are -1, 0 or 1); [far] is Shift (200 dp), else 40 dp. */
    data class Pan(val dx: Int, val dy: Int, val far: Boolean) : KeyAction

    /** PageUp ([direction] -1) or PageDown (+1): one screen height. */
    data class PanPage(val direction: Int) : KeyAction

    data object FitToContent : KeyAction
    data object ResetZoom : KeyAction
    data object ZoomIn : KeyAction
    data object ZoomOut : KeyAction
    data object ZoomToFit : KeyAction
    data object ToggleGrid : KeyAction
    data object ToggleSnap : KeyAction
    data object OpenShortcuts : KeyAction
    data object OpenConstrainMenu : KeyAction
    data object NextRegion : KeyAction
    data object PreviousRegion : KeyAction
    data object Escape : KeyAction

    /** A key Crosshair mode owns while it is active: arrows move the crosshair, Enter places a point, Esc backs out one row. */
    data class Crosshair(val key: CrosshairKey) : KeyAction
}

/** What the editor knows when a key arrives. */
data class KeyContext(
    val env: KeymapEnv,
    /** The `SingleKeyShortcuts` setting. */
    val singleKeyEnabled: Boolean,
    val canvasFocused: Boolean,
    /** A text field, the style panel or a dialog holds focus (UX-22). */
    val textInputOrDialogFocused: Boolean,
    val hasSelection: Boolean,
    val handToolActive: Boolean = false,
    /** Crosshair mode is active, so arrows, Enter and Esc go to it instead of nudging, panning or the Esc row below it. */
    val crosshairActive: Boolean = false,
)

/** What the dispatcher decided. */
sealed interface KeyOutcome {
    data class Handled(val action: KeyAction) : KeyOutcome

    /** Not ours: pass the key on (a text field, an unbound key, a focus the canvas does not own). */
    data object Ignored : KeyOutcome

    /** A single-character shortcut matched but the setting is off; nothing changes and nothing is announced (WCAG 2.1.4). */
    data class SingleKeyBlocked(val entryId: String) : KeyOutcome
}

/**
 * Maps a [KeyPress] to a [KeyAction] from [KeymapSpec], the one table the shortcuts sheet also reads (Task 3.6.1a).
 * Pure. Rules: nothing fires while a text field or dialog has focus (UX-22); a bare printable key needs the canvas focus
 * AND the single-key setting (WCAG 2.1.4, D-53), a modifier chord and a non-printable key do not; arrows nudge a selection
 * and pan otherwise (UX-105), Ctrl or Cmd with an arrow always pans; Backspace deletes like Delete.
 */
object KeyDispatcher {
    fun dispatch(press: KeyPress, ctx: KeyContext): KeyOutcome {
        if (ctx.textInputOrDialogFocused) return KeyOutcome.Ignored
        if (ctx.crosshairActive) crosshairKey(press)?.let { return KeyOutcome.Handled(KeyAction.Crosshair(it)) }
        val matched = matching(press, ctx.env)
        val hit = matched.firstOrNull() ?: return KeyOutcome.Ignored
        val (entry, binding) = hit
        if (isSingleCharacter(binding) && !SingleKeyShortcuts.isActive(ctx.singleKeyEnabled, ctx.canvasFocused, ctx.textInputOrDialogFocused)) {
            return KeyOutcome.SingleKeyBlocked(entry.id)
        }
        // Chords and named keys act while the canvas or a sketch region has focus; the host only calls with that focus.
        return actionFor(entry, binding, ctx)?.let { KeyOutcome.Handled(it) } ?: KeyOutcome.Ignored
    }

    private fun crosshairKey(press: KeyPress): CrosshairKey? {
        if (press.hasModifier()) return null
        return when (val direction = ARROW_DIRECTIONS[press.produced?.lowercase()]) {
            null -> NAMED_CROSSHAIR_KEYS[press.produced?.lowercase()]
            else -> CrosshairKey.Move(direction.first, direction.second, press.shift)
        }
    }

    private fun KeyPress.hasModifier(): Boolean = ctrl || meta || alt

    private fun matching(press: KeyPress, env: KeymapEnv): List<Pair<KeymapEntry, KeyBinding>> {
        val normal = KeymapSpec.entries.flatMap { e -> e.bindings.filter { KeymapSpec.matches(it, press, env) }.map { e to it } }
        if (normal.isNotEmpty()) return normal
        // Backspace deletes like Delete without a second row in the table.
        return if (press.produced == BACKSPACE && !press.hasModifier()) {
            KeymapSpec.others.filter { it.id == "delete" }.map { it to it.bindings.first() }
        } else {
            emptyList()
        }
    }

    /** An unmodified printable character: a tool letter or digit, Q, K, and the `?` sheet key (D-53). */
    private fun isSingleCharacter(binding: KeyBinding): Boolean = binding.singleKey || binding.key == "?"

    private fun actionFor(entry: KeymapEntry, binding: KeyBinding, ctx: KeyContext): KeyAction? {
        val key = binding.key
        val ctrlArrow = Modifier.Primary in binding.modifiers
        return when (entry.id) {
            KeymapSpec.TOOL_LOCK_ID -> KeyAction.ToolLock
            KeymapSpec.CONSTRAIN_ID -> KeyAction.OpenConstrainMenu
            "undo" -> KeyAction.Undo
            "redo" -> KeyAction.Redo
            "copy" -> KeyAction.Copy
            "paste" -> KeyAction.Paste
            "cut" -> KeyAction.Cut
            "duplicate" -> KeyAction.Duplicate
            "group" -> KeyAction.Group
            "ungroup" -> KeyAction.Ungroup
            "sendBackward" -> KeyAction.Arrange(ZOrderMove.Backward)
            "bringForward" -> KeyAction.Arrange(ZOrderMove.Forward)
            "sendToBack" -> KeyAction.Arrange(ZOrderMove.ToBack)
            "bringToFront" -> KeyAction.Arrange(ZOrderMove.ToFront)
            "delete" -> KeyAction.Delete
            "nudge", "nudgeFar", "pan", "panFar", "panAlways" -> arrow(key, Modifier.Shift in binding.modifiers, ctrlArrow, ctx)
            "panPage" -> KeyAction.PanPage(if (key == "PageUp") -1 else 1)
            "panHome" -> KeyAction.FitToContent
            "resetZoom" -> KeyAction.ResetZoom
            "zoomIn" -> KeyAction.ZoomIn
            "zoomOut" -> KeyAction.ZoomOut
            "zoomToFit" -> KeyAction.ZoomToFit
            "grid" -> KeyAction.ToggleGrid
            "snap" -> KeyAction.ToggleSnap
            "shortcutsSheet" -> KeyAction.OpenShortcuts
            "nextRegion" -> KeyAction.NextRegion
            "previousRegion" -> KeyAction.PreviousRegion
            "escape" -> KeyAction.Escape
            else -> SketchToolId.entries.firstOrNull { it.id == entry.id }?.let { KeyAction.SelectTool(it) }
        }
    }

    private fun arrow(key: String, shift: Boolean, ctrl: Boolean, ctx: KeyContext): KeyAction {
        val dx = when (key) { "ArrowLeft" -> -1; "ArrowRight" -> 1; else -> 0 }
        val dy = when (key) { "ArrowUp" -> -1; "ArrowDown" -> 1; else -> 0 }
        val nudges = ctx.hasSelection && !ctx.handToolActive && !ctrl
        return if (nudges) KeyAction.Nudge(dx, dy, far = shift) else KeyAction.Pan(dx, dy, far = shift)
    }

    private const val BACKSPACE = "Backspace"

    private const val ARROW_LEFT = "arrowleft"
    private const val ARROW_RIGHT = "arrowright"
    private const val ARROW_UP = "arrowup"
    private const val ARROW_DOWN = "arrowdown"
    private const val ENTER = "enter"
    private const val ESCAPE = "escape"

    private val ARROW_DIRECTIONS = mapOf(ARROW_LEFT to (-1 to 0), ARROW_RIGHT to (1 to 0), ARROW_UP to (0 to -1), ARROW_DOWN to (0 to 1))
    private val NAMED_CROSSHAIR_KEYS = mapOf(ENTER to CrosshairKey.Enter, ESCAPE to CrosshairKey.Escape)
}
