// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.1.4, Task 3.1.4c (UX-126, UX-128): the action chip row and the toast lane. Toast strings come from the parsed chrome
 * table. Robolectric; Gradle-only like the other Compose host tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1920dp-h1080dp-mdpi")
class ActionChipRowTest {
    @get:Rule
    val rule = createComposeRule()

    private val table = ChromeTableParser.parseFixture()
    private var now = 0L
    private val primaries = mutableListOf<String>()
    private val moreItems = mutableListOf<String>()

    private fun notice(id: String, tier: BannerTier = BannerTier.Info): SketchNotice {
        val t = table.toasts.single { it.id == id }
        return SketchNotice(id, tier, t.summary, t.primaryLabel, shortTitle = t.shortTitle, details = "More", secondaryActions = t.moreItems.filter { it != "Details" && it != "Dismiss" })
    }

    private fun host(queue: SketchNoticeQueue) = ToastLaneHost(queue, { now }, ToastActions({ primaries += it.key }, { n, item -> moreItems += "${n.key}:$item" }))

    private class Lane(val host: ToastLaneHost, val width: Int, val height: Int, val scale: Float)

    private var lane by mutableStateOf<Lane?>(null)

    private fun showLane(host: ToastLaneHost, width: Int = 360, height: Int = 640, scale: Float = 1f) {
        if (!hosted) {
            hosted = true
            rule.setContent {
                val l = lane ?: return@setContent
                CompositionLocalProvider(LocalDensity provides Density(1f, l.scale)) {
                    Box(Modifier.requiredSize(l.width.dp, l.height.dp)) { ToastLaneOverlay(l.host, l.height.toFloat()) }
                }
            }
        }
        lane = Lane(host, width, height, scale)
        rule.waitForIdle()
    }

    private var hosted = false

    @Test
    fun touchOnly_should_FinishALineAndToggleAModifier_FromChipsAtTheFloor() {
        var done = 0
        var square = 0
        rule.setContent {
            ActionChipRow(listOf(ActionChip("chip.done", "Done") { done++ }, ActionChip("chip.square", "Square") { square++ }))
        }
        rule.onNodeWithTag("chip.done").performClick()
        rule.onNodeWithTag("chip.square").performClick()
        assertEquals(1, done)
        assertEquals(1, square)
        for (tag in listOf("chip.done", "chip.square")) {
            val bounds = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue(bounds.height.value >= ChromeBudget.TOOL_TARGET_DP && bounds.width.value >= ChromeBudget.TOOL_TARGET_DP, "$tag at the touch floor: $bounds")
        }
    }

    @Test
    fun emptyChipRow_should_ShowNothing() {
        rule.setContent { ActionChipRow(emptyList()) }
        rule.onAllNodesWithTag(ShellTags.CHIP_ROW).assertCountEquals(0)
    }

    @Test
    fun toastLane_should_NeverShowMoreToastsThanTheBudgetAllows_And_CountTheRest() {
        val queue = SketchNoticeQueue()
        table.toasts.filter { it.primaryLabel != null }.take(5).forEach { queue.post(notice(it.id), now) }
        val host = host(queue)
        val lane = ChromeBudget.toastCount(640 - 100, 1.0)
        showLane(host, height = 540)
        val shown = table.toasts.filter { it.primaryLabel != null }.take(5).count { rule.onAllNodesWithTag(ToastTags.row(it.id)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(shown <= 3 && shown >= 1, "shown $shown")
        assertTrue(lane in 1..3)
    }

    @Test
    fun toast_should_ShowOneActionAndMore_WithDismissLast_And_RunTheAction() {
        val queue = SketchNoticeQueue()
        queue.post(notice("LayerLocked", BannerTier.Warning), now)
        showLane(host(queue))
        rule.onNodeWithTag(ToastTags.row("LayerLocked")).assertIsDisplayed()
        rule.onNodeWithTag(ToastTags.primary("LayerLocked")).performClick()
        assertEquals(listOf("LayerLocked"), primaries)
        assertEquals(listOf("Switch layer", "Details", "Dismiss"), ToastMenu.items(notice("LayerLocked")))
        rule.onNodeWithTag(ToastTags.more("LayerLocked")).performClick()
        rule.onNodeWithTag(ToastTags.moreItem("LayerLocked", "Dismiss")).performClick()
        assertEquals(listOf("LayerLocked:Dismiss"), moreItems)
    }

    @Test
    fun everyToast_should_EndItsMoreMenuWithDismiss() {
        for (t in table.toasts) {
            assertEquals("Dismiss", ToastMenu.items(notice(t.id)).last(), t.id)
            assertTrue(t.moreItems.last() == "Dismiss", "${t.id}: the table's last More item is Dismiss")
        }
    }

    @Test
    fun laneOfOne_should_MoveTheDisplayedToastToTheDiagnosticsList_When_ANewerOneArrives() {
        val queue = SketchNoticeQueue()
        queue.post(notice("AddedConstraint"), now)
        queue.post(notice("ArrowUnbound"), now + 1)
        queue.fitLane(1, now + 1)
        assertEquals(listOf("ArrowUnbound"), queue.visible(now + 1).map { it.notice.key })
        assertTrue(queue.diagnosticNotices.any { it.key == "AddedConstraint" })
    }

    @Test
    fun toasts_should_RenderOneRowWithinTheTableHeight_At_EveryWidthAndScale() {
        for (t in table.toasts) for (width in listOf(320, 360)) for (scale in listOf(1f, 2f)) {
            val queue = SketchNoticeQueue()
            queue.post(notice(t.id), now)
            showLane(host(queue), width = width, height = 640, scale = scale)
            val bounds = rule.onNodeWithTag(ToastTags.row(t.id)).getBoundsInRoot()
            val max = if (scale >= ChromeBudget.LARGE_SCALE.toFloat()) ChromeBudget.TOAST_ROW_LARGE_DP else ChromeBudget.TOAST_ROW_DP
            assertTrue(bounds.height.value <= max, "${t.id} at $width dp, scale $scale: ${bounds.height}")
        }
    }
}
