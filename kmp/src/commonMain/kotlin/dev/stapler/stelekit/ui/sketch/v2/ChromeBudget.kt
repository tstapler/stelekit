// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTextBudget
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

/** The rows of the chrome table's `[rows]` section: height at font scale below and from [ChromeBudget.LARGE_SCALE], collapse order. */
enum class ChromeRow(val heightDp: Int, val heightLargeDp: Int, val collapseOrder: Int?) {
    TopBar(48, 56, null),
    Dock(56, 56, null),
    BannerSlot(56, 96, 2),
    HintStatus(24, 48, 1),
    SelectionBar(48, 56, 3),
}

/** How a banner row is drawn at one width and font scale (UX-126): the summary, the short title, or a sheet for a consent row. */
enum class BannerForm { Summary, ShortTitle, ConsentSheet }

/** The top bar's tier at one window (UX-80, UX-119): full or short forms, and where the Selection menu sits. */
enum class TopBarForm { Full, Short }

enum class SelectionMenuPlace { None, InBar, InOverflow }

data class TopBarPlan(val form: TopBarForm, val selection: SelectionMenuPlace, val nameDp: Int)

/** The view cluster and the chip row at one window: one or two cluster rows, the chips beside or above, and the overlay height. */
data class OverlayPlan(val clusterRows: Int, val chipsAbove: Boolean, val heightDp: Int)

/**
 * Everything the chrome table computes for one window at one font scale. [canvasCollapsed] is the canvas after the rows
 * collapsed (in [ChromeBudget] order) until the minimum holds; [canvasWithBlocking] is the same with a Blocking banner that
 * never collapses; [minimumMet] is false only in a degraded window, where no minimum is claimed.
 */
data class ChromePlan(
    val layout: ShellLayout,
    val stripExtentDp: Int,
    val flat: Boolean,
    val stackDp: Int,
    val canvasWithEveryRowDp: Int,
    val collapsed: List<ChromeRow>,
    val canvasCollapsedDp: Int,
    val canvasWithBlockingDp: Int,
    val minimumDp: Int,
    val canvasWidthDp: Int,
    val toastCount: Int,
    val topBar: TopBarPlan,
    val overlay: OverlayPlan,
    val drawingWidthDp: Int,
    val edgeStrips: Int,
) {
    val minimumMet: Boolean get() = canvasCollapsedDp >= minimumDp
}

/**
 * The chrome budget (Story 3.1.4, UX-119, UX-126, UX-128): ONE pure policy for the layout, the strip's flat-or-grouped rule,
 * the transient rows and their collapse order, the minimum canvas, the toast lane, the top-bar tier and the overlay layout.
 * Every figure is a parameter of the chrome table in design/ux.md section 1; `ChromeBudgetPolicyTest` compares these constants with
 * the generated chrome fixture, so a figure here cannot drift from the table.
 */
object ChromeBudget {
    const val TOOL_TARGET_DP = TouchTargetFloor.TOUCH_DP
    const val SPLIT_TARGETS = 2
    const val DELETE_GAP_DP = TouchTargetFloor.DELETE_GAP_DP
    const val TOP_BAR_RESERVE_DP = 48
    const val RAIL_WIDTH_DP = 56
    const val SIDE_PANE_WIDTH_DP = 320
    const val COMPACT_WIDTH_DP = 600
    const val EXPANDED_WIDTH_DP = 840
    const val COMPACT_HEIGHT_DP = 480
    const val MIN_CANVAS_MAX_DP = 200
    const val MIN_CANVAS_PCT = 55
    const val MIN_CANVAS_WIDTH_DP = 240
    const val TOAST_ROW_DP = 56
    const val TOAST_ROW_LARGE_DP = 88
    const val TOAST_PRIMARY_DP = 104
    const val TOAST_GAP_DP = 8
    const val TOAST_MAX_PCT = 40
    const val BANNER_LINES = 2
    const val BANNER_CHAR_DP = 7
    const val GUTTER_DP = 16
    const val DYN_MAX = 12
    const val GESTURE_INSET_DP = 32
    const val CLUSTER_GAP_DP = 4
    const val VIEW_TARGETS = 7
    const val CHIP_MIN_DP = 96
    const val OVERLAY_MAX_PCT = 70
    const val HANDLE_FULL = 3
    const val HANDLE_CORNERS = 2
    const val MOUSE_FLOOR_DP = TouchTargetFloor.MOUSE_DP
    const val NAME_MIN_DP = 64
    const val BACK_DP = 48
    const val OVERFLOW_DP = 48
    const val SELECTION_MENU_DP = 48
    const val FINGER_FULL_DP = 88
    const val FINGER_SHORT_DP = 56
    const val SAVE_FULL_DP = 72
    const val SAVE_SHORT_DP = 44
    const val PINNED_DP = TouchTargetFloor.PRIMARY_DP
    const val PINNED_COUNT = TouchTargetFloor.PINNED_COUNT
    const val STRIP_CONTROLS = 2

    /** Chrome text and rows switch to their large forms from this font scale; the chrome never grows past [MAX_CHROME_SCALE]. */
    const val LARGE_SCALE = 1.5
    const val MAX_CHROME_SCALE = 2.0

    // ---- layout and the strip ---------------------------------------------------------------------------------------

    /** Layout from both axes: compact height uses the rail and a side sheet, Expanded width the rail and the pane, the rest the dock. */
    fun layout(widthDp: Int, heightDp: Int): ShellLayout = when {
        heightDp < COMPACT_HEIGHT_DP -> ShellLayout(StripPlacement.LeftRail, PanelHost.SideSheet)
        widthDp >= EXPANDED_WIDTH_DP -> ShellLayout(StripPlacement.LeftRail, PanelHost.PersistentPane)
        else -> ShellLayout(StripPlacement.BottomDock, PanelHost.BottomSheet)
    }

    /** The strip extent: the window width for the dock, the window height less the top-bar reserve for the rail. */
    fun stripExtentDp(placement: StripPlacement, widthDp: Int, heightDp: Int): Int =
        if (placement == StripPlacement.BottomDock) widthDp else heightDp - TOP_BAR_RESERVE_DP

    private fun pinnedExtentDp(): Int = PINNED_COUNT * PINNED_DP + DELETE_GAP_DP

    /** Extent of the strip with [toolCount] tools each a single target, plus the strip controls (Constrain, Tool lock). */
    fun flatExtentDp(toolCount: Int): Int = pinnedExtentDp() + (toolCount + STRIP_CONTROLS) * TOOL_TARGET_DP

    /** Extent with [singles] single buttons and [groups] split buttons of two targets each. */
    fun groupedExtentDp(singles: Int, groups: Int): Int =
        pinnedExtentDp() + (singles + groups * SPLIT_TARGETS + STRIP_CONTROLS) * TOOL_TARGET_DP

    /** ONE rule at every width class: flat whenever the flat extent fits the strip extent. */
    fun isFlat(toolCount: Int, stripExtentDp: Int): Boolean = flatExtentDp(toolCount) <= stripExtentDp

    // ---- rows, minimum canvas, toasts -------------------------------------------------------------------------------

    fun large(fontScale: Double): Boolean = fontScale >= LARGE_SCALE

    fun rowHeightDp(row: ChromeRow, fontScale: Double): Int = if (large(fontScale)) row.heightLargeDp else row.heightDp

    /** The minimum canvas height: `min(minCanvasMaxDp, minCanvasPct % of the window height)`. */
    fun minimumCanvasDp(heightDp: Int): Int = minOf(MIN_CANVAS_MAX_DP, heightDp * MIN_CANVAS_PCT / PERCENT)

    /** The rows that exist in [layout]; in the rail with a side sheet the hint row and the selection bar are permanently collapsed. */
    fun presentRows(layout: ShellLayout): List<ChromeRow> {
        val permanent = layout.placement == StripPlacement.LeftRail && layout.panels == PanelHost.SideSheet
        return ChromeRow.entries.filter { row ->
            when (row) {
                ChromeRow.Dock -> layout.placement == StripPlacement.BottomDock
                ChromeRow.HintStatus, ChromeRow.SelectionBar -> !permanent
                else -> true
            }
        }
    }

    /**
     * Rows to collapse, in collapse order, until a canvas of [windowHeightDp] less the rows still shown is at least
     * [minimumDp]; [keepBanner] keeps the banner slot (a Blocking banner never collapses).
     */
    fun collapseUntilMinimum(
        layout: ShellLayout,
        windowHeightDp: Int,
        fontScale: Double,
        minimumDp: Int,
        keepBanner: Boolean,
    ): List<ChromeRow> {
        val present = presentRows(layout)
        var canvas = windowHeightDp - present.sumOf { rowHeightDp(it, fontScale) }
        val collapsed = ArrayList<ChromeRow>()
        val candidates = present.filter { it.collapseOrder != null && !(keepBanner && it == ChromeRow.BannerSlot) }.sortedBy { it.collapseOrder }
        for (row in candidates) {
            if (canvas >= minimumDp) break
            collapsed += row
            canvas += rowHeightDp(row, fontScale)
        }
        return collapsed
    }

    /** How many toasts the lane shows (1 to 3): as many as fit in [toastMaxPct] % of the collapsed canvas, never fewer than one. */
    fun toastCount(canvasHeightDp: Int, fontScale: Double): Int {
        val row = if (large(fontScale)) TOAST_ROW_LARGE_DP else TOAST_ROW_DP
        val room = canvasHeightDp * TOAST_MAX_PCT / PERCENT
        return (MAX_TOASTS downTo 1).first { it == 1 || it * row + (it - 1) * TOAST_GAP_DP <= room }
    }

    // ---- width side: top bar, edge strips, overlays -----------------------------------------------------------------

    /** Active edge-pan strips assumed by the table: one below `compactWidth`, two from it. */
    fun edgeStrips(widthDp: Int): Int = if (widthDp < COMPACT_WIDTH_DP) 1 else 2

    fun canvasWidthDp(layout: ShellLayout, widthDp: Int): Int = widthDp -
        (if (layout.placement == StripPlacement.LeftRail) RAIL_WIDTH_DP else 0) -
        (if (layout.panels == PanelHost.PersistentPane) SIDE_PANE_WIDTH_DP else 0)

    /**
     * The top bar's tier: below [LARGE_SCALE] the full forms, else the short ones. The full forms fall back to the short ones
     * when the name would drop under [NAME_MIN_DP], and the Selection menu (rail with a side sheet only) then moves into Overflow.
     */
    fun topBar(layout: ShellLayout, widthDp: Int, fontScale: Double): TopBarPlan {
        val barWidth = canvasWidthDp(layout, widthDp)
        val hasSelection = layout.placement == StripPlacement.LeftRail && layout.panels == PanelHost.SideSheet
        val menuDp = if (hasSelection) SELECTION_MENU_DP else 0
        fun name(form: TopBarForm, menu: Int): Int {
            val finger = if (form == TopBarForm.Full) FINGER_FULL_DP else FINGER_SHORT_DP
            val save = if (form == TopBarForm.Full) SAVE_FULL_DP else SAVE_SHORT_DP
            return barWidth - (BACK_DP + finger + save + OVERFLOW_DP + menu)
        }
        val start = if (large(fontScale)) TopBarForm.Short else TopBarForm.Full
        val withMenu = if (hasSelection) SelectionMenuPlace.InBar else SelectionMenuPlace.None
        if (name(start, menuDp) >= NAME_MIN_DP) return TopBarPlan(start, withMenu, name(start, menuDp))
        if (start == TopBarForm.Full && name(TopBarForm.Short, menuDp) >= NAME_MIN_DP) {
            return TopBarPlan(TopBarForm.Short, withMenu, name(TopBarForm.Short, menuDp))
        }
        val moved = if (hasSelection) SelectionMenuPlace.InOverflow else SelectionMenuPlace.None
        return TopBarPlan(TopBarForm.Short, moved, name(TopBarForm.Short, 0))
    }

    /** Width of the view cluster: one row of [VIEW_TARGETS] targets, or two rows of the toggles (four targets). */
    fun clusterWidthDp(rows: Int): Int {
        val targets = if (rows == 1) VIEW_TARGETS else VIEW_TARGETS - ZOOM_TARGETS
        return targets * TOOL_TARGET_DP + (targets - 1) * CLUSTER_GAP_DP
    }

    fun clusterHeightDp(rows: Int): Int = rows * TOOL_TARGET_DP + (rows - 1) * CLUSTER_GAP_DP

    /** One cluster row where the mic button, [CHIP_MIN_DP] of chips and the cluster fit side by side, else two rows; chips above when still not. */
    fun overlay(layout: ShellLayout, widthDp: Int): OverlayPlan {
        val overlayWidth = canvasWidthDp(layout, widthDp) - (GESTURE_INSET_DP + TOOL_TARGET_DP) * edgeStrips(widthDp)
        fun fits(rows: Int) = clusterWidthDp(rows) + TOOL_TARGET_DP + CHIP_MIN_DP + 2 * CLUSTER_GAP_DP <= overlayWidth
        return when {
            fits(1) -> OverlayPlan(1, false, clusterHeightDp(1))
            fits(2) -> OverlayPlan(2, false, clusterHeightDp(2))
            else -> OverlayPlan(2, true, clusterHeightDp(2) + CLUSTER_GAP_DP + TOOL_TARGET_DP)
        }
    }

    // ---- the whole window -------------------------------------------------------------------------------------------

    /** The plan of one window; [toolCount] is the strip's tool count (14 on technical sheets, 12 on a diagram sheet). */
    fun plan(widthDp: Int, heightDp: Int, fontScale: Double, toolCount: Int = ALL_TOOLS): ChromePlan {
        val chromeScale = minOf(fontScale, MAX_CHROME_SCALE)
        val layout = layout(widthDp, heightDp)
        val present = presentRows(layout)
        val stack = present.sumOf { rowHeightDp(it, chromeScale) }
        val minimum = minimumCanvasDp(heightDp)
        val collapsed = collapseUntilMinimum(layout, heightDp, chromeScale, minimum, keepBanner = false)
        val freed = collapsed.sumOf { rowHeightDp(it, chromeScale) }
        val blockingCollapsed = collapseUntilMinimum(layout, heightDp, chromeScale, minimum, keepBanner = true)
        val strips = edgeStrips(widthDp)
        val canvasWidth = canvasWidthDp(layout, widthDp)
        val extent = stripExtentDp(layout.placement, widthDp, heightDp)
        val canvasCollapsed = heightDp - stack + freed
        return ChromePlan(
            layout = layout,
            stripExtentDp = extent,
            flat = isFlat(toolCount, extent),
            stackDp = stack,
            canvasWithEveryRowDp = heightDp - stack,
            collapsed = collapsed,
            canvasCollapsedDp = canvasCollapsed,
            canvasWithBlockingDp = heightDp - stack + blockingCollapsed.sumOf { rowHeightDp(it, chromeScale) },
            minimumDp = minimum,
            canvasWidthDp = canvasWidth,
            toastCount = toastCount(canvasCollapsed, chromeScale),
            topBar = topBar(layout, widthDp, chromeScale),
            overlay = overlay(layout, widthDp),
            drawingWidthDp = canvasWidth - TOOL_TARGET_DP * strips,
            edgeStrips = strips,
        )
    }

    private const val PERCENT = 100
    private const val MAX_TOASTS = 3
    private const val ZOOM_TARGETS = 3
    private const val ALL_TOOLS = 14
}

/** Banner and toast text fit (Story 3.1.4, UX-126): the word-wrap rules the rows are checked against. */
object ChromeText {
    fun toastRowDp(fontScale: Double): Int = if (ChromeBudget.large(fontScale)) ChromeBudget.TOAST_ROW_LARGE_DP else ChromeBudget.TOAST_ROW_DP

    /**
     * The form of a banner row at [widthDp] and [fontScale]: the summary when it word-wraps inside the row's lines, else the
     * short title; a row with no short title (the consent row) is replaced by a sheet instead, never by a short title.
     */
    fun bannerForm(summary: String, shortTitle: String?, widthDp: Int, fontScale: Double): BannerForm {
        val perLine = BannerTextBudget.charsPerLine(widthDp, minOf(fontScale, ChromeBudget.MAX_CHROME_SCALE))
        val lines = BannerTextBudget.wrapLines(summary, perLine)
        return when {
            lines != null && lines <= ChromeBudget.BANNER_LINES -> BannerForm.Summary
            shortTitle == null -> BannerForm.ConsentSheet
            else -> BannerForm.ShortTitle
        }
    }

    /** Characters per line of a toast's text: the width less two gutters, the action button, More and two gaps. */
    fun toastCharsPerLine(widthDp: Int, fontScale: Double): Int {
        val available = widthDp - 2 * ChromeBudget.GUTTER_DP - ChromeBudget.TOAST_PRIMARY_DP - BannerTextBudget.MORE_DP - 2 * ChromeBudget.TOAST_GAP_DP
        return (available / (ChromeBudget.BANNER_CHAR_DP * minOf(fontScale, ChromeBudget.MAX_CHROME_SCALE))).toInt()
    }

    /** A toast's summary with `{name}` cut to [ChromeBudget.DYN_MAX] characters and `{n:3}` to three, the worst case the row must hold. */
    fun expandPlaceholders(text: String): String = text
        .replace(SketchStrings.Chrome.PLACEHOLDER_NAME, SketchStrings.Chrome.PLACEHOLDER_NAME_FILL.repeat(ChromeBudget.DYN_MAX))
        .replace(SketchStrings.Chrome.PLACEHOLDER_COUNT, SketchStrings.Chrome.PLACEHOLDER_COUNT_FILL)

    /** The text a toast row shows: the summary when it word-wraps inside two lines, else the short title. */
    fun toastText(summary: String, shortTitle: String, widthDp: Int, fontScale: Double): String {
        val lines = BannerTextBudget.wrapLines(expandPlaceholders(summary), toastCharsPerLine(widthDp, fontScale))
        return if (lines != null && lines <= ChromeBudget.BANNER_LINES) summary else shortTitle
    }
}
