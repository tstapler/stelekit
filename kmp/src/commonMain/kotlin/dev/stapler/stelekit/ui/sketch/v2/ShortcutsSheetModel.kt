// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort

data class ShortcutRow(val id: String, val label: String, val keys: List<String>, val sameAsExcalidraw: Boolean)

data class ShortcutSection(val title: String, val rows: List<ShortcutRow>, val notes: List<String> = emptyList())

/**
 * What the shortcuts sheet shows (Task 3.6.1b), read from [KeymapSpec], the table the dispatcher reads, so the two cannot
 * drift. Groups: Tools, Edit, Arrange, View (panning and F6 included) and Constraints.
 */
data class ShortcutsSheetModel(
    val sections: List<ShortcutSection>,
    /** Set while the map rests on a default for U8, U11 or U12; null once all are recorded. */
    val footnote: String?,
    val singleKeyEnabled: Boolean,
) {
    val toolRows: List<ShortcutRow> get() = sections.first { it.title == TOOLS }.rows.filter { row -> SketchToolId.entries.any { it.id == row.id } }

    companion object {
        const val TOOLS = "Tools"
        const val EDIT = "Edit"
        const val ARRANGE = "Arrange"
        const val VIEW = "View"
        const val CONSTRAINTS = "Constraints"

        const val REGION_NOTE = "F6 and Shift+F6 move between the regions of the editor without stepping through every element; Esc returns focus to the canvas."
        const val COLLISION_NOTE = "In browse mode NVDA and JAWS reserve the letters H, F, E, T, L and D, which are also our tool letters; the shortcuts work in focus mode."
        const val SETTING_LABEL = "Single-key shortcuts"
        const val TITLE = "Keyboard shortcuts"

        const val MNEMONIC_ID_PREFIX = "constrain.mnemonic."

        /** The menu letters, listed with the menu so the sheet says they work only while it is open. */
        private fun mnemonicRows() = ConstrainMenuItem.entries.map {
            ShortcutRow("$MNEMONIC_ID_PREFIX${it.name}", SketchStrings.Shortcuts.constrainMenuRow(it.label), listOf(it.mnemonic.toString()), sameAsExcalidraw = false)
        }

        fun of(env: KeymapEnv, singleKeyEnabled: Boolean, layout: KeyboardLayoutInfo? = null): ShortcutsSheetModel {
            fun row(e: KeymapEntry) = ShortcutRow(
                e.id,
                e.label,
                e.bindings.filter { KeymapSpec.isAvailable(it, env) }.map { KeymapSpec.displayLabel(it, env, layout) },
                sameAsExcalidraw = e.bindings.none { !KeymapSpec.claimsExcalidrawParity(it.key) },
            )
            fun group(vararg groups: KeymapGroup) = KeymapSpec.entries.filter { it.group in groups }.map(::row)
            val sections = listOf(
                ShortcutSection(TOOLS, group(KeymapGroup.Tools)),
                ShortcutSection(EDIT, group(KeymapGroup.Edit)),
                ShortcutSection(ARRANGE, group(KeymapGroup.Arrange)),
                ShortcutSection(VIEW, group(KeymapGroup.View, KeymapGroup.Pan), notes = listOf(REGION_NOTE)),
                ShortcutSection(CONSTRAINTS, group(KeymapGroup.Constrain) + mnemonicRows(), notes = listOf(COLLISION_NOTE)),
            )
            return ShortcutsSheetModel(sections, KeymapSpec.gate.footnote, singleKeyEnabled)
        }
    }
}

/** Emits the web `SingleKeyChip` the first time a single-character shortcut is blocked by the setting (UX-108, E2). */
class SingleKeyHintProducer(private val diagnostics: DiagnosticsPort, private val isWeb: Boolean) {
    private var raised = false

    /** Producers only emit a candidate; the banner slot decides whether and when to show it (Story 3.6.4). */
    fun onBlocked() {
        if (!isWeb || raised) return
        raised = true
        diagnostics.raise(BannerRowId.SingleKeyChip, DETAILS)
    }

    /** "Got it" resolves the chip; it is not raised again in this session. */
    fun resolve() = diagnostics.resolve(BannerRowId.SingleKeyChip)

    /** "Turn on": stores the `SingleKeyShortcuts` setting and settles the chip. */
    fun turnOn(settings: Settings) {
        SingleKeyShortcuts.write(settings, true)
        resolve()
    }

    companion object {
        const val DETAILS = "Single-key tool shortcuts are off."
    }
}

/** The dispatcher plus the hint producer: the one object the canvas key handler calls. */
class SketchKeyboard(private val hint: SingleKeyHintProducer) {
    fun onKey(press: KeyPress, ctx: KeyContext): KeyOutcome = KeyDispatcher.dispatch(press, ctx).also {
        if (it is KeyOutcome.SingleKeyBlocked) hint.onBlocked()
    }
}
