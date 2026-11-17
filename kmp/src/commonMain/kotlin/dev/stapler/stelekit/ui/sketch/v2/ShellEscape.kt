// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

/**
 * The shell's part of the one Esc order (ux.md section 17, rows 1 to 12): which rows the shell itself can see, and whether the
 * first-run walkthrough (row 8) is the row a press acts on. Rows the host owns (a dialog, "Solving...", an armed picker, a
 * mid-draw preview, Crosshair mode) come in through [walkthroughActs]'s `hostRows`.
 */
object ShellEscape {
    /** The rows of the order that are open in [state]: the Pan pad and a help popup are row 2, a text edit row 3, the tour row 8. */
    fun shellRows(state: SketchEditorState): Set<EscRow> = buildSet {
        if (state.panPadOpen || state.help.shown != null) add(EscRow.MenuOrFlyout)
        if (state.textDraft != null) add(EscRow.TextOverlayOrDimensionField)
        if (state.walkthrough.active) add(EscRow.Walkthrough)
    }

    /**
     * One Esc press at the shell: acts on the first row of the order that applies and returns true when it did. Row 2 here is
     * the help popup or the Pan pad (a host-owned menu consumes its own Esc); row 8 ends the tour through [endTour]. Every other
     * row belongs to a control nearer the focus or to the host, so the press passes on.
     */
    fun press(state: SketchEditorState, hostRows: Set<EscRow>, endTour: () -> Unit): Boolean = when (EscOrder.resolve(shellRows(state) + hostRows)) {
        EscRow.MenuOrFlyout -> closeShellMenu(state)
        EscRow.Walkthrough -> true.also { endTour() }
        else -> false
    }

    private fun closeShellMenu(state: SketchEditorState): Boolean = when {
        state.help.shown != null -> true.also { state.help.dismiss() }
        state.panPadOpen -> true.also { state.panPadOpen = false }
        else -> false
    }

    /** True when Esc ends the tour: it is showing and no row above it ([EscRow.rank] 1 to 7) applies. */
    fun walkthroughActs(state: SketchEditorState, hostRows: Set<EscRow>): Boolean =
        EscOrder.resolve(shellRows(state) + hostRows) == EscRow.Walkthrough
}
