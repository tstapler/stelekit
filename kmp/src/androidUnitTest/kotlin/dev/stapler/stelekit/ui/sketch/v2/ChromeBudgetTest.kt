// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.stapler.stelekit.sketch.v2.ChromeTable
import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import dev.stapler.stelekit.sketch.v2.ChromeWindow
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTextBudget
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 3.1.4, Task 3.1.4b (UX-119, UX-126, UX-88): the adaptive host at every window of the chrome table, with every banner
 * candidate in the real notice queue. Every figure is read from the parsed table. Robolectric (1 px = 1 dp); Gradle-only like
 * the other Compose host tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1920dp-h1080dp-mdpi")
class ChromeBudgetTest {
    @get:Rule
    val rule = createComposeRule()

    private val table: ChromeTable = ChromeTableParser.parseFixture()
    private val strings = ChromeBannerStrings(table)

    private fun queueWithEveryBanner(): SketchNoticeQueue = SketchNoticeQueue().also { q ->
        for (row in BannerRowId.entries.filter { it != BannerRowId.CoachMark }) q.submitBanner(strings.candidate(row))
    }

    private class Scene(val window: ChromeWindow, val banner: BannerSlotState?, val bannerCandidate: BannerCandidate?, val width: Int, val scale: Float)

    private var scene by mutableStateOf<Scene?>(null)

    @Before
    fun host() {
        rule.setContent {
            val s = scene ?: return@setContent
            val w = s.window
            key(s) {
                CompositionLocalProvider(LocalDensity provides Density(1f, s.scale)) {
                    if (s.bannerCandidate != null) {
                        Box(Modifier.requiredSize(s.width.dp, 400.dp)) { BannerSlotContent(s.bannerCandidate, BannerActions()) }
                    } else {
                        Box(Modifier.requiredSize(w.widthDp.dp, w.heightDp.dp)) {
                            SketchScreenV2(
                                SketchEditorState(),
                                SketchShellConfig(ToolStripModel(technicalSheet = true), PointerClass.TouchOrPen, banner = s.banner),
                                window = WindowDp(w.widthDp, w.heightDp),
                            )
                        }
                    }
                }
            }
        }
    }

    private val none = ChromeWindow("none", 360, 640, 1.0, true)

    private fun show(window: ChromeWindow, banner: BannerSlotState) {
        scene = Scene(window, banner, null, window.widthDp, window.fontScale.toFloat())
        rule.waitForIdle()
    }

    private fun showBanner(candidate: BannerCandidate, width: Int, scale: Float) {
        scene = Scene(none, null, candidate, width, scale)
        rule.waitForIdle()
    }

    @Test
    fun layout_should_EqualTheBudgetsChoice_And_KeepTheMinimumCanvas_At_EveryGuaranteedWindow() {
        for (w in table.windows.filter { it.guaranteed }) {
            val banner = BannerSlotState(queueWithEveryBanner()).also { it.refresh() }
            show(w, banner)
            val plan = ChromeBudget.plan(w.widthDp, w.heightDp, w.fontScale)
            rule.onNodeWithTag(ShellTags.layout(plan.layout.placement)).assertExists()
            val canvas = rule.onNodeWithTag(ShellTags.CANVAS).getBoundsInRoot()
            val tall = canvas.height.value
            assertTrue(tall >= plan.minimumDp, "${w.name} ${w.widthDp}x${w.heightDp}@${w.fontScale}: canvas $tall < ${plan.minimumDp}")
            rule.onNodeWithTag(ShellTags.BANNER).assertExists()
            rule.onAllNodesWithTag(ShellTags.BANNER).assertCountEquals(1)
            rule.onAllNodesWithTag(ShellTags.TOP_BAR).assertCountEquals(1)
        }
    }

    @Test
    fun oneBannerSlot_should_ShowTheHighestRank_And_TheNextWhenItIsActedOn() {
        val queue = queueWithEveryBanner()
        val banner = BannerSlotState(queue).also { it.refresh() }
        show(table.windows.first(), banner)
        assertEquals(table.banners.minBy { it.rank }.id, banner.head?.row?.name)
        queue.resolveBanner(banner.head!!.row)
        rule.runOnIdle { banner.refresh() }
        rule.waitForIdle()
        assertEquals(table.banners.sortedBy { it.rank }[1].id, banner.head?.row?.name)
        rule.onAllNodesWithTag(ShellTags.BANNER).assertCountEquals(1)
    }

    @Test
    fun hintRow_should_BeAbsent_When_TheRailHasASideSheet_And_PresentInTheDock() {
        for (w in table.windows.filter { it.guaranteed && it.fontScale == 1.0 }) {
            show(w, BannerSlotState(SketchNoticeQueue()))
            val layout = ChromeBudget.layout(w.widthDp, w.heightDp)
            val collapsed = ChromeBudget.plan(w.widthDp, w.heightDp, w.fontScale).collapsed
            val expected = ChromeRow.HintStatus in ChromeBudget.presentRows(layout) && ChromeRow.HintStatus !in collapsed
            assertEquals(expected, rule.onAllNodesWithTag(ShellTags.HINT).fetchSemanticsNodes().isNotEmpty(), "${w.name} ${w.widthDp}x${w.heightDp}")
        }
    }

    @Test
    fun everyBannerRow_should_FitOneRowOfTwoLines_With_AFullAccessibleSummary_At_EveryWidthAndScale() {
        for (b in table.banners.filter { it.id != BannerRowId.CoachMark.name }) for (width in listOf(320, 360)) for (scale in listOf(1.0, 2.0)) {
            val candidate = strings.candidate(BannerRowId.valueOf(b.id))
            showBanner(candidate, width, scale.toFloat())
            val where = "${b.id} at $width dp, scale $scale"
            val form = ChromeText.bannerForm(candidate.summary, candidate.shortTitle, width, scale)
            if (form == BannerForm.ConsentSheet) {
                rule.onNodeWithTag(BannerTags.CONSENT_SHEET).assertExists()
            } else {
                val row = rule.onNodeWithTag(BannerTags.ROW).getBoundsInRoot()
                assertTrue(row.height.value <= ChromeRow.BannerSlot.heightDp.coerceAtLeast(ChromeRow.BannerSlot.heightLargeDp), "$where: row ${row.height}")
                val text = rule.onNodeWithTag(BannerTags.TEXT).fetchSemanticsNode().config
                assertEquals(candidate.summary, text[SemanticsProperties.ContentDescription].single(), "$where: the live region carries the full summary")
                assertEquals(if (form == BannerForm.Summary) candidate.summary else candidate.shortTitle, text[SemanticsProperties.Text].single().text)
            }
            val primary = rule.onNodeWithTag(BannerTags.PRIMARY).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
            assertTrue(primary.contains(candidate.primaryLabel, ignoreCase = true), "$where: name '$primary' contains label '${candidate.primaryLabel}'")
            assertEquals(candidate.moreItems.isNotEmpty(), rule.onAllNodesWithTag(BannerTags.MORE).fetchSemanticsNodes().isNotEmpty(), where)
        }
    }

    @Test
    fun accessibleNames_should_BeUnique_And_ContainTheVisibleLabel() {
        val rows = table.banners.filter { it.primaryLabel != null }
        assertEquals(rows.size, rows.mapNotNull { it.primaryAccessibleName }.toSet().size)
        for (r in rows) assertTrue(r.primaryAccessibleName!!.contains(r.primaryLabel!!, ignoreCase = true), r.id)
        assertFalse(BannerTextBudget.NARROWEST_WIDTH_DP <= 0)
    }
}
