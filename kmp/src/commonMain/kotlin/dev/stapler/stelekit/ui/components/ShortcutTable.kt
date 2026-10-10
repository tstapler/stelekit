package dev.stapler.stelekit.ui.components

import androidx.compose.ui.input.key.Key
import dev.stapler.stelekit.ui.screens.FormatAction

/**
 * Single source of truth for "what shortcut string corresponds to what action" (Story F.3.1).
 *
 * Consumed by both [handleKeyEvent]'s key-combination checks (`BlockEditor.kt`) and
 * `StelekitViewModel`'s `Command.shortcut` badges, so the command-palette's display-only
 * shortcut badge (`CommandItem`, `CommandPalette.kt:208-223`) can never drift from
 * `handleKeyEvent`'s actual live binding (stack.md §2's audit finding).
 *
 * All bindings here require Ctrl (or Cmd on macOS/desktop's `isCtrlPressed || isMetaPressed`
 * gate already applied by the caller in `handleKeyEvent`) — this table only encodes the
 * key/shift portion of each binding, matching the existing cascade's structure exactly so the
 * repoint is behavior-preserving.
 *
 * `CODE_BLOCK` and `TABLE_INSERT` have no hardware-keyboard binding yet (mobile-toolbar /
 * command-palette only) — [forAction] returns `null` for those, and no entry exists in
 * [bindings], so `actionForKeyEvent` can never match them (unchanged from the pre-existing
 * cascade, which also never handled them here).
 */
object ShortcutTable {

    /**
     * A key + optional required-shift-state pairing. [requireShift] `null` means "shift state is
     * not checked" (matches the original cascade's behavior for BOLD/ITALIC/etc., which fire
     * regardless of whether Shift happens to also be held); `true` means Shift must be held
     * (NUMBERED_LIST/HEADING, mirroring Google Docs' Ctrl+Shift+7 / Ctrl+Shift+1 convention).
     */
    data class KeyBinding(val key: Key, val requireShift: Boolean? = null)

    // Order mirrors the original `handleKeyEvent` `when` cascade (BlockEditor.kt) exactly, so
    // actionForKeyEvent's first-match semantics are unchanged from before this repoint.
    private val bindings: List<Pair<FormatAction, KeyBinding>> = listOf(
        FormatAction.BOLD to KeyBinding(Key.B),
        FormatAction.ITALIC to KeyBinding(Key.I),
        FormatAction.STRIKETHROUGH to KeyBinding(Key.S),
        FormatAction.HIGHLIGHT to KeyBinding(Key.H),
        FormatAction.CODE to KeyBinding(Key.E),
        FormatAction.LINK to KeyBinding(Key.L),
        FormatAction.QUOTE to KeyBinding(Key.Apostrophe),
        FormatAction.NUMBERED_LIST to KeyBinding(Key.Seven, requireShift = true),
        FormatAction.HEADING to KeyBinding(Key.One, requireShift = true),
    )

    /** The canonical [KeyBinding] for [action], or null if it has no hardware-keyboard shortcut. */
    fun bindingFor(action: FormatAction): KeyBinding? = bindings.firstOrNull { it.first == action }?.second

    /**
     * Resolves the [FormatAction] (if any) bound to [key] with the given [shiftPressed] state.
     * Used by `handleKeyEvent`'s Ctrl/Cmd-pressed cascade in place of the original inline `when`.
     */
    fun actionForKeyEvent(key: Key, shiftPressed: Boolean): FormatAction? =
        bindings.firstOrNull { (_, binding) ->
            binding.key == key && (binding.requireShift == null || binding.requireShift == shiftPressed)
        }?.first

    /** Canonical human-readable display string for a [FormatAction]'s keyboard shortcut, or null if none exists. */
    fun forAction(action: FormatAction): String? {
        val binding = bindingFor(action) ?: return null
        return buildString {
            append("Ctrl+")
            if (binding.requireShift == true) append("Shift+")
            append(displayKey(binding.key))
        }
    }

    private fun displayKey(key: Key): String = when (key) {
        Key.B -> "B"
        Key.I -> "I"
        Key.S -> "S"
        Key.H -> "H"
        Key.E -> "E"
        Key.L -> "L"
        Key.Apostrophe -> "'"
        Key.Seven -> "7"
        Key.One -> "1"
        else -> key.toString()
    }

    /**
     * Canonical shortcut for toggling a block's TODO/DOING/DONE marker (`applyTodoToggle` /
     * `requestTodoToggle`). Not a [FormatAction] case — see the `TodoState` Pattern Decision — so
     * kept as its own named constant rather than folded into [forAction].
     */
    const val TODO_TOGGLE = "Ctrl+Enter"
}
