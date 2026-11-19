// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTextBudget
import dev.stapler.stelekit.ui.sketch.v2.BannerForm
import dev.stapler.stelekit.ui.sketch.v2.ChromeBudget
import dev.stapler.stelekit.ui.sketch.v2.ChromeText
import dev.stapler.stelekit.ui.sketch.v2.ChromeRow
import dev.stapler.stelekit.ui.sketch.v2.PanelHost
import dev.stapler.stelekit.ui.sketch.v2.SelectionMenuPlace
import dev.stapler.stelekit.ui.sketch.v2.StripPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.1.4, Task 3.1.4a: [ChromeBudget] against the generated chrome-table fixture. Every figure is read from the
 * parsed table; the expectations below are the table's rules written out independently of the policy under test.
 */
class ChromeBudgetPolicyTest {
    private val table = ChromeTableParser.parseFixture()
    private val scales = listOf(1.0, 2.0)
    private val bannerWidths = listOf(320, 360)

    /** The chrome table's `[params]` that [ChromeBudget] and [BannerTextBudget] mirror, by the table's own names. */
    private val mirroredParams: Map<String, Int> = mapOf(
        "toolTarget" to ChromeBudget.TOOL_TARGET_DP, "splitTargets" to ChromeBudget.SPLIT_TARGETS, "deleteGap" to ChromeBudget.DELETE_GAP_DP,
        "topBarReserve" to ChromeBudget.TOP_BAR_RESERVE_DP, "railWidth" to ChromeBudget.RAIL_WIDTH_DP, "sidePaneWidth" to ChromeBudget.SIDE_PANE_WIDTH_DP,
        "compactWidth" to ChromeBudget.COMPACT_WIDTH_DP, "expandedWidth" to ChromeBudget.EXPANDED_WIDTH_DP, "compactHeight" to ChromeBudget.COMPACT_HEIGHT_DP,
        "minCanvasMaxDp" to ChromeBudget.MIN_CANVAS_MAX_DP, "minCanvasPct" to ChromeBudget.MIN_CANVAS_PCT, "minCanvasWidth" to ChromeBudget.MIN_CANVAS_WIDTH_DP,
        "toastRow" to ChromeBudget.TOAST_ROW_DP, "toastRow2" to ChromeBudget.TOAST_ROW_LARGE_DP, "toastPrimary" to ChromeBudget.TOAST_PRIMARY_DP,
        "toastGap" to ChromeBudget.TOAST_GAP_DP, "toastMaxPct" to ChromeBudget.TOAST_MAX_PCT, "bannerLines" to ChromeBudget.BANNER_LINES,
        "bannerCharDp" to ChromeBudget.BANNER_CHAR_DP, "gutter" to ChromeBudget.GUTTER_DP, "dynMax" to ChromeBudget.DYN_MAX,
        "gestureInset" to ChromeBudget.GESTURE_INSET_DP, "clusterGap" to ChromeBudget.CLUSTER_GAP_DP, "viewTargets" to ChromeBudget.VIEW_TARGETS,
        "chipMin" to ChromeBudget.CHIP_MIN_DP, "overlayMaxPct" to ChromeBudget.OVERLAY_MAX_PCT, "handleFull" to ChromeBudget.HANDLE_FULL,
        "handleCorners" to ChromeBudget.HANDLE_CORNERS, "mouseFloor" to ChromeBudget.MOUSE_FLOOR_DP,
        "bannerPrimary" to BannerTextBudget.PRIMARY_DP, "bannerMore" to BannerTextBudget.MORE_DP, "bannerGap" to BannerTextBudget.GAP_DP,
        "bannerIcon" to BannerTextBudget.ICON_DP, "btnPad" to BannerTextBudget.BUTTON_PAD_DP,
    )

    private fun expectedFlatExtent(): Int =
        table.pinned.sumOf { it.sizeDp } + table.param("deleteGap") + (table.tools.size + table.strip.size) * table.param("toolTarget")

    @Test
    fun params_should_EqualTheTableParameters_When_ComparedByName() {
        for ((name, value) in mirroredParams) assertEquals(table.param(name), value, "param $name")
    }

    @Test
    fun rows_should_MatchTheTableHeightsAndCollapseOrder_When_ParsedFromRowsSection() {
        for (cells in table.raw("rows")) {
            val row = ChromeRow.valueOf(cells[0])
            assertEquals(cells[1].toInt(), row.heightDp, "${row.name} height")
            assertEquals(cells[2].toInt(), row.heightLargeDp, "${row.name} large height")
            assertEquals(cells[3].toIntOrNull(), row.collapseOrder, "${row.name} collapse order")
        }
        assertEquals(table.raw("rows").size, ChromeRow.entries.size)
    }

    @Test
    fun layout_should_FollowBothAxes_When_EveryWindowOfTheTableIsChosen() {
        for (w in table.windows) {
            val layout = ChromeBudget.layout(w.widthDp, w.heightDp)
            val expected = when {
                w.heightDp < table.param("compactHeight") -> StripPlacement.LeftRail to PanelHost.SideSheet
                w.widthDp >= table.param("expandedWidth") -> StripPlacement.LeftRail to PanelHost.PersistentPane
                else -> StripPlacement.BottomDock to PanelHost.BottomSheet
            }
            assertEquals(expected, layout.placement to layout.panels, "${w.name} ${w.widthDp}x${w.heightDp}")
        }
    }

    @Test
    fun flatExtent_should_EqualPinnedPlusToolsPlusStripControls_When_FourteenTools() {
        assertEquals(expectedFlatExtent(), ChromeBudget.flatExtentDp(table.tools.size))
        val singles = table.tools.count { it.group == null }
        val groups = table.tools.mapNotNull { it.group }.distinct().size
        val grouped = table.pinned.sumOf { it.sizeDp } + table.param("deleteGap") +
            (singles + groups * table.param("splitTargets") + table.strip.size) * table.param("toolTarget")
        assertEquals(grouped, ChromeBudget.groupedExtentDp(singles, groups))
        assertTrue(ChromeBudget.groupedExtentDp(singles, groups) < expectedFlatExtent())
    }

    @Test
    fun isFlat_should_BeOneRuleAtEveryWidthClass_When_ExtentCrossesTheFlatExtent() {
        val flat = expectedFlatExtent()
        assertTrue(ChromeBudget.isFlat(table.tools.size, flat))
        assertTrue(!ChromeBudget.isFlat(table.tools.size, flat - 1))
        for (w in table.windows) {
            val plan = ChromeBudget.plan(w.widthDp, w.heightDp, w.fontScale, table.tools.size)
            assertEquals(flat <= plan.stripExtentDp, plan.flat, "${w.name} ${w.widthDp}x${w.heightDp}")
        }
    }

    @Test
    fun minimumCanvas_should_Hold_When_AnyGuaranteedWindowHasABlockingBanner() {
        for (w in table.windows.filter { it.guaranteed }) {
            val plan = ChromeBudget.plan(w.widthDp, w.heightDp, w.fontScale, table.tools.size)
            val minimum = minOf(table.param("minCanvasMaxDp"), w.heightDp * table.param("minCanvasPct") / 100)
            assertEquals(minimum, plan.minimumDp, "${w.name} minimum")
            assertTrue(plan.canvasCollapsedDp >= minimum, "${w.name} ${w.widthDp}x${w.heightDp}@${w.fontScale}: ${plan.canvasCollapsedDp} < $minimum")
            assertTrue(plan.canvasWithBlockingDp >= minimum, "${w.name} with a Blocking banner: ${plan.canvasWithBlockingDp} < $minimum")
            assertTrue(plan.drawingWidthDp >= table.param("minCanvasWidth"), "${w.name} drawing width ${plan.drawingWidthDp}")
        }
    }

    @Test
    fun collapse_should_FollowTheTableOrder_When_RowsMustGiveWayToTheMinimum() {
        for (w in table.windows) {
            val plan = ChromeBudget.plan(w.widthDp, w.heightDp, w.fontScale, table.tools.size)
            val order = plan.collapsed.mapNotNull { it.collapseOrder }
            assertEquals(order.sorted(), order, "${w.name} collapse order")
            assertTrue(ChromeRow.TopBar !in plan.collapsed && ChromeRow.Dock !in plan.collapsed, "${w.name}: top bar and dock never collapse")
        }
    }

    @Test
    fun permanentRows_should_BeAbsent_When_TheRailHasASideSheet() {
        val permanent = table.raw("permanent").single { it[0] == "rail+side sheet" }[1].split(' ').map { ChromeRow.valueOf(it) }.toSet()
        for (w in table.windows) {
            val layout = ChromeBudget.layout(w.widthDp, w.heightDp)
            val present = ChromeBudget.presentRows(layout).toSet()
            val railWithSide = layout.placement == StripPlacement.LeftRail && layout.panels == PanelHost.SideSheet
            assertEquals(railWithSide, permanent.none { it in present }, "${w.name} ${w.widthDp}x${w.heightDp}")
        }
    }

    @Test
    fun bannerForm_should_FitTwoLines_When_EveryRowIsRenderedAtEveryWidthAndScale() {
        for (b in table.banners) for (width in bannerWidths) for (scale in scales) {
            val perLine = BannerTextBudget.charsPerLine(width, scale)
            val form = ChromeText.bannerForm(b.summary, b.shortTitle, width, scale)
            val shown = when (form) {
                BannerForm.Summary -> b.summary
                BannerForm.ShortTitle -> b.shortTitle ?: error("${b.id}: short title expected")
                BannerForm.ConsentSheet -> null
            }
            if (shown != null) {
                val lines = BannerTextBudget.wrapLines(shown, perLine)
                assertTrue(lines != null && lines <= table.param("bannerLines"), "${b.id} at $width dp, scale $scale: '$shown'")
            } else {
                assertEquals(null, b.shortTitle, "${b.id}: only a consent row (no short title) becomes a sheet")
            }
            if (scale == 1.0 && width == BannerTextBudget.NARROWEST_WIDTH_DP) {
                assertEquals(BannerForm.Summary, form, "${b.id} summary fits at 320 dp, scale 1.0")
            }
        }
    }

    @Test
    fun toastText_should_FitTwoLines_When_EveryRowIsRenderedWithExpandedPlaceholders() {
        for (t in table.toasts) for (width in bannerWidths) for (scale in scales) {
            val text = ChromeText.toastText(t.summary, t.shortTitle, width, scale)
            val lines = BannerTextBudget.wrapLines(ChromeText.expandPlaceholders(text), ChromeText.toastCharsPerLine(width, scale))
            assertTrue(lines != null && lines <= table.param("bannerLines"), "${t.id} at $width dp, scale $scale: '$text'")
        }
    }

    @Test
    fun toastCount_should_StayInOneToThree_When_TheCanvasShrinksOrGrows() {
        for (scale in scales) for (canvas in 0..1200 step 20) {
            val n = ChromeBudget.toastCount(canvas, scale)
            val row = ChromeText.toastRowDp(scale)
            assertTrue(n in 1..3, "count $n at canvas $canvas")
            if (n > 1) assertTrue(n * row + (n - 1) * table.param("toastGap") <= canvas * table.param("toastMaxPct") / 100)
        }
    }

    @Test
    fun topBar_should_KeepTheNameItsMinimum_When_AnyGuaranteedWindowIsPlanned() {
        for (w in table.windows.filter { it.guaranteed }) {
            val bar = ChromeBudget.plan(w.widthDp, w.heightDp, w.fontScale, table.tools.size).topBar
            assertTrue(bar.nameDp >= nameMin(), "${w.name} ${w.widthDp}@${w.fontScale}: ${bar.nameDp}")
            val railWithSide = ChromeBudget.layout(w.widthDp, w.heightDp).let { it.placement == StripPlacement.LeftRail && it.panels == PanelHost.SideSheet }
            assertEquals(railWithSide, bar.selection != SelectionMenuPlace.None, "${w.name}: the Selection menu exists only in the rail with a side sheet")
        }
    }

    @Test
    fun plan_should_ObeyTheTableRules_When_AGridOfWindowsIsPlanned() {
        for (width in 320..1920 step 40) for (height in table.param("minWindowHeight")..1080 step 40) for (scale in scales) {
            val plan = ChromeBudget.plan(width, height, scale, table.tools.size)
            assertEquals(ChromeBudget.layout(width, height), plan.layout)
            assertTrue(plan.canvasCollapsedDp >= plan.minimumDp, "${width}x$height@$scale canvas ${plan.canvasCollapsedDp} < ${plan.minimumDp}")
            assertTrue(plan.canvasWithBlockingDp >= plan.minimumDp, "${width}x$height@$scale with a Blocking banner")
            assertTrue(plan.toastCount in 1..3)
        }
    }

    private fun nameMin(): Int = table.raw("topbar").single { it[0] == "NameMin" }[1].toInt()
}
