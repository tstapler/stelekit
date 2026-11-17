// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.tools.ToolId

/** Which pointers the device has shown: touch and pen set the 44 dp floor, a mouse alone 24 dp (UX-46, UX-92; D-17). */
enum class PointerClass { MouseOnly, TouchOrPen }

/**
 * The ONE touch-target floor (D-17, Story 3.1.3): 44 dp whenever a touch or pen pointer is present, 24 dp (WCAG 2.5.8)
 * on a mouse-only device. Visible sizes never change with the pointer class, only hit areas may grow to the floor.
 * Figures come from the chrome table of design/ux.md (`toolTarget`, `mouseFloor`, the pinned 48 dp).
 */
object TouchTargetFloor {
    const val TOUCH_DP = 44
    const val MOUSE_DP = 24
    const val PRIMARY_DP = 48
    const val VISIBLE_HANDLE_DP = 8
    const val PART_HIT_TOUCH_TOLERANCE_DP = 22
    const val DELETE_GAP_DP = 16
    const val PINNED_COUNT = 3

    fun floorDp(pointer: PointerClass): Int = if (pointer == PointerClass.TouchOrPen) TOUCH_DP else MOUSE_DP

    /** Pinned Undo, Redo and Delete: 48 dp in every pointer class. */
    fun pinnedDp(): Int = PRIMARY_DP

    /**
     * Hit area of a selection handle: at least the floor when "Larger touch handles" is on and a touch or pen is
     * present, else the floor of the pointer class; never below the visible 8 dp handle.
     */
    fun handleHitDp(pointer: PointerClass, largerTouchHandles: Boolean): Int =
        if (pointer == PointerClass.TouchOrPen && largerTouchHandles) PRIMARY_DP else maxOf(floorDp(pointer), VISIBLE_HANDLE_DP)

    /** The default of "Larger touch handles": on for a touch-primary device. */
    fun largerHandlesDefault(touchPrimary: Boolean): Boolean = touchPrimary

    /** Whether a control of [sizeDp] meets the floor of [pointer]. */
    fun meets(pointer: PointerClass, sizeDp: Float): Boolean = sizeDp >= floorDp(pointer)
}

/** Strip groups (chrome table `[groups]`): a split button with a main target and a flyout target. */
enum class StripGroup(val label: String, val tools: List<ToolId>, val technicalOnly: Boolean) {
    Shapes("Shapes", listOf(ToolId.Rectangle, ToolId.Diamond, ToolId.Ellipse), false),
    Lines("Lines", listOf(ToolId.Arrow, ToolId.Line, ToolId.Freedraw), false),
    Geometry("Geometry", listOf(ToolId.Point, ToolId.Arc), true),
}

/** One tool of the strip: its label, shortcut text and group (null for the singles). */
data class StripTool(val id: ToolId, val label: String, val shortcut: String, val group: StripGroup?) {
    val key: String get() = id.name.lowercase()
}

/** Why a tool cannot be used right now; the button stays focusable and announces it (ux.md section 2, error states). */
sealed interface ToolBlock {
    data object ReadOnly : ToolBlock
    data class LayerLocked(val layerName: String) : ToolBlock

    fun reason(): String = when (this) {
        ReadOnly -> "read-only sketch"
        is LayerLocked -> "locked layer: $layerName"
    }
}

/**
 * The tools of the strip in the chrome table's order, with their shortcut text from the keymap. [technicalSheet]
 * turns on the Geometry group (Point, Arc); on a diagram sheet those two stay in the overflow menu.
 */
class ToolStripModel(val technicalSheet: Boolean, private val keymapEnv: KeymapEnv = KeymapEnv(isMac = false, isWeb = false)) {
    val tools: List<StripTool> = ToolId.entries.map { id ->
        StripTool(id, id.name, shortcutOf(id), StripGroup.entries.firstOrNull { id in it.tools })
    }

    /** Tools shown on the strip: every tool except Point and Arc on a diagram sheet. */
    val stripTools: List<StripTool> = tools.filter { technicalSheet || it.group?.technicalOnly != true }

    /** The groups present on this sheet, in strip order. */
    val groups: List<StripGroup> = StripGroup.entries.filter { technicalSheet || !it.technicalOnly }

    /** Tools in the overflow menu under "More tools": Point and Arc on a diagram sheet. */
    val overflowTools: List<StripTool> = tools.filter { it !in stripTools }

    fun tool(id: ToolId): StripTool = tools.first { it.id == id }

    /** The semantics text of a tool button, e.g. "Rectangle (R)" or "Rectangle, unavailable: read-only sketch". */
    fun description(tool: StripTool, block: ToolBlock?): String =
        if (block == null) "${tool.label} (${tool.shortcut})" else "${tool.label}, unavailable: ${block.reason()}"

    /** Flat items are the singles and group members; grouped items are the singles and one split button per group. */
    fun flatExtentDp(): Int = ChromeBudget.flatExtentDp(stripTools.size)

    fun groupedExtentDp(): Int = ChromeBudget.groupedExtentDp(stripTools.count { it.group == null }, groups.size)

    /** ONE rule at every width class (`ChromeBudget.isFlat`): flat whenever the flat extent fits the strip extent, else grouped. */
    fun isFlat(stripExtentDp: Int): Boolean = ChromeBudget.isFlat(stripTools.size, stripExtentDp)

    private fun shortcutOf(id: ToolId): String {
        val entry = KeymapSpec.tools.first { it.id == id.name.lowercase() }
        return KeymapSpec.displayLabel(entry.bindings.first(), keymapEnv)
    }
}

/** Where the strip sits and where panels open (UX-06, UX-119). [choose] reads its rule from `ChromeBudget` (Story 3.1.4). */
enum class StripPlacement { BottomDock, LeftRail }

enum class PanelHost { BottomSheet, SideSheet, PersistentPane }

data class ShellLayout(val placement: StripPlacement, val panels: PanelHost) {
    companion object {
        const val COMPACT_HEIGHT_DP = ChromeBudget.COMPACT_HEIGHT_DP
        const val EXPANDED_WIDTH_DP = ChromeBudget.EXPANDED_WIDTH_DP

        /** Layout from both axes, never width alone; the rule and its figures are `ChromeBudget.layout`. */
        fun choose(widthDp: Int, heightDp: Int): ShellLayout = ChromeBudget.layout(widthDp, heightDp)
    }
}

/** Tool lock (Q): with it on a finished shape keeps its tool, else the strip returns to Select (UX-04). */
object ToolLockPolicy {
    fun toolAfterCommit(current: ToolId, locked: Boolean): ToolId = if (locked || current == ToolId.Select || current == ToolId.Hand) current else ToolId.Select
}
