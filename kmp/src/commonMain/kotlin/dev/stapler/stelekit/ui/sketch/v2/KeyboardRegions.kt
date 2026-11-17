// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

/**
 * The ONE tab order of the editor (ux.md section 10, D-52). F6 and Shift+F6 and the "Skip canvas elements" link follow it:
 * F6 walks only the [f6] subset, the link goes to the stop after the canvas.
 */
enum class TabStop(val inF6: Boolean) {
    TopBar(true), NoticeRegion(true), ToolStrip(true), Canvas(true), ActionChipRow(false), CrosshairPad(false),
    SelectionBar(true), ViewCluster(true), SidePane(true), NoticesList(false), StatusLine(true),
}

object KeyboardRegions {
    /**
     * The stops Tab walks, in the one order of ux.md section 10, among [present] (the canvas is always there). Shift+Tab walks
     * it reversed; the walk has no trap because every present stop is reachable from every other.
     */
    fun tabOrder(present: Set<TabStop>): List<TabStop> = TabStop.entries.filter { it in present || it == TabStop.Canvas }

    /**
     * The tab order while the walkthrough shows its popover for [anchor] (ux.md section 22): the popover is a labelled region
     * placed directly after its anchor, so Tab moves from the anchor into it and on to the next region without wrapping.
     * When the anchor is not in the order the popover goes last, so it is never unreachable.
     */
    fun tabOrderWithPopover(present: Set<TabStop>, anchor: TabStop): List<TabItem> {
        val regions = tabOrder(present).map { TabItem.Region(it) }
        val at = regions.indexOfFirst { it.stop == anchor }
        return if (at < 0) regions + TabItem.Popover else regions.take(at + 1) + TabItem.Popover + regions.drop(at + 1)
    }

    /** The stops F6 visits, in order. */
    val f6: List<TabStop> = TabStop.entries.filter { it.inF6 }

    /**
     * The region F6 ([backward] false) or Shift+F6 moves to from [current], among [present] (the notice region only while it
     * has an occupant, the selection bar only where it exists; the canvas is always there). Wraps around. A [current] that F6
     * does not own (the chip row, the crosshair pad, the Notices list) moves by its place in the full order. The walkthrough
     * popover is not a stop: pass its anchor, and F6 goes to the region after it while Shift+F6 goes to the anchor itself
     * ([fromPopover]). Null when nothing is present.
     */
    fun f6Target(current: TabStop, present: Set<TabStop>, backward: Boolean): TabStop? {
        val ring = f6.filter { it in present || it == TabStop.Canvas }
        val at = current.ordinal
        return if (backward) ring.lastOrNull { it.ordinal < at } ?: ring.lastOrNull() else ring.firstOrNull { it.ordinal > at } ?: ring.firstOrNull()
    }

    /** F6 from the walkthrough popover anchored on [anchor]: the region after it; Shift+F6: the anchor itself. */
    fun fromPopover(anchor: TabStop, present: Set<TabStop>, backward: Boolean): TabStop? =
        if (backward) anchor else f6Target(anchor, present, backward = false)

    /**
     * Where the "Skip canvas elements" link goes: the next stop of the order that exists, so Align, Order, Lock and Transform
     * are one Tab away (UX-117): the action chip row, then the crosshair pad, the selection bar, the view cluster.
     */
    fun skipCanvasTarget(present: Set<TabStop>): TabStop? =
        TabStop.entries.drop(TabStop.Canvas.ordinal + 1).firstOrNull { it in present }
}

/** The 12 rows of the one Esc order (ux.md section 17, P1): one press undoes the first row that applies and nothing else. */
enum class EscRow(val rank: Int, val label: String) {
    DialogOrSheet(1, "an open dialog or sheet"),
    MenuOrFlyout(2, "an open menu, flyout, zoom menu, Pan pad or help popup"),
    TextOverlayOrDimensionField(3, "a text overlay or a dimension field"),
    Solving(4, "\"Solving...\""),
    ArmedPicker(5, "an armed constraint picker"),
    MidDraw(6, "a mid-draw preview"),
    Crosshair(7, "Crosshair mode"),
    Walkthrough(8, "the first-run walkthrough"),
    NoticeAction(9, "a toast or banner action that has focus"),
    ActiveTool(10, "an active tool other than Select"),
    Selection(11, "the selection"),
    NewestToast(12, "the newest toast"),
}

object EscOrder {
    /** The row one Esc press acts on, or null when none applies. Esc never undoes a committed edit and never closes the editor. */
    fun resolve(applicable: Set<EscRow>): EscRow? = EscRow.entries.firstOrNull { it in applicable }

    /** Presses Esc until nothing applies, removing the row each press takes; returns the rows in the order they were taken. */
    fun drain(applicable: Set<EscRow>): List<EscRow> {
        val remaining = applicable.toMutableSet()
        val taken = ArrayList<EscRow>()
        while (true) {
            val row = resolve(remaining) ?: return taken
            taken += row
            remaining -= row
        }
    }
}
