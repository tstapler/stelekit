// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.sketch.v2.tools.Box as SceneBox
import dev.stapler.stelekit.sketch.v2.tools.Handle
import dev.stapler.stelekit.sketch.v2.tools.HandleLayout
import dev.stapler.stelekit.sketch.v2.tools.HandleTier
import dev.stapler.stelekit.sketch.v2.tools.SelectionFrame
import dev.stapler.stelekit.sketch.v2.tools.TouchFloor
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 3.2.2d (UX-127, UX-46): handles are reduced by the selection's size on screen so no two hit areas overlap, over
 * generated sizes at both touch-target floors; and the overlay shows exactly those handles, with "Zoom in to use
 * handles" and the Transform menu as the way out when none fit.
 */
@RunWith(RobolectricTestRunner::class)
class HandleLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val floors = listOf(TouchFloor.dp(touchPresent = false), TouchFloor.dp(touchPresent = true))
    private val sizes = (4..60).map { it * SIZE_STEP }
    private val zooms = listOf(0.5, 1.0, 3.0)
    private val angles = listOf(0.0, 0.7, 1.9)

    private fun overlaps(a: dev.stapler.stelekit.sketch.v2.tools.PlacedHandle, b: dev.stapler.stelekit.sketch.v2.tools.PlacedHandle): Boolean =
        abs(a.centre.x.value - b.centre.x.value) < a.halfHit + b.halfHit - EPS && abs(a.centre.y.value - b.centre.y.value) < a.halfHit + b.halfHit - EPS

    @Test
    fun noTwoShownHitAreasOverlap_ForGeneratedSizesAtBothFloors() {
        var checked = 0
        val heights = sizes.filterIndexed { i, _ -> i % SIZE_STRIDE == 0 }
        for (floor in floors) for (zoom in zooms) for (w in sizes) for (h in heights) for (angle in angles) {
            val layout = HandleLayout.compute(0.0, 0.0, w.toDouble(), h.toDouble(), 0.0, zoom, floor)
            for (i in layout.handles.indices) for (j in i + 1 until layout.handles.size) {
                assertTrue(!overlaps(layout.handles[i], layout.handles[j]), "overlap at ${w}x$h zoom $zoom floor $floor: ${layout.handles[i].handle} / ${layout.handles[j].handle}")
            }
            // Rotation does not change which handles fit, and the hit squares are measured axis-aligned only unrotated, so check the tier too.
            val rotated = HandleLayout.compute(0.0, 0.0, w.toDouble(), h.toDouble(), angle, zoom, floor)
            assertEquals(layout.tier, rotated.tier)
            checked++
        }
        assertTrue(checked > MIN_CASES)
    }

    @Test
    fun tiers_should_FollowTheShorterSideOnScreen() {
        val floor = TouchFloor.TOUCH_DP
        fun tier(shorterPx: Double) = HandleLayout.compute(0.0, 0.0, shorterPx * 2, shorterPx, 0.0, 1.0, floor).tier
        assertEquals(HandleTier.Full, tier(floor * HandleLayout.FULL_FLOORS))
        assertEquals(HandleTier.Corners, tier(floor * HandleLayout.FULL_FLOORS - 1.0))
        assertEquals(HandleTier.Corners, tier(floor * HandleLayout.CORNER_FLOORS))
        assertEquals(HandleTier.None, tier(floor * HandleLayout.CORNER_FLOORS - 1.0))
        val full = HandleLayout.compute(0.0, 0.0, 400.0, 300.0, 0.0, 1.0, floor)
        assertEquals(9, full.handles.size)
        val corners = HandleLayout.compute(0.0, 0.0, 120.0, 100.0, 0.0, 1.0, floor)
        assertEquals(setOf(Handle.NW, Handle.NE, Handle.SE, Handle.SW, Handle.Rotate), corners.handles.map { it.handle }.toSet())
        assertTrue(HandleLayout.compute(0.0, 0.0, 10.0, 10.0, 0.0, 1.0, floor).needsTransformMenu)
    }

    @Test
    fun hit_should_PreferRotationThenCorners() {
        val layout = HandleLayout.compute(0.0, 0.0, 200.0, 120.0, 0.0, 1.0, TouchFloor.TOUCH_DP)
        val corner = layout.handles.single { it.handle == Handle.NE }.centre
        assertEquals(Handle.NE, layout.hit(corner.x.value, corner.y.value))
        assertEquals(null, layout.hit(corner.x.value + 500.0, corner.y.value))
    }

    private fun show(frame: SelectionFrame, zoom: Double = 1.0) {
        val viewport = SketchViewport.of(zoom = zoom, panX = PAN, panY = PAN)
        val layout = HandleLayout.compute(frame.box.cx, frame.box.cy, frame.box.width, frame.box.height, frame.angle, zoom, TouchFloor.TOUCH_DP, frame.rotatable)
        rule.setContent { Box(Modifier.fillMaxSize()) { SelectionOverlay(frame, layout, viewport) } }
        rule.waitForIdle()
    }

    private fun handleCount() = Handle.entries.count { rule.onAllNodesWithTagCount("handle-${it.name}") > 0 }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun overlay_should_ShowAllNineHandles_When_TheSelectionIsLarge() {
        show(SelectionFrame(SceneBox(0.0, 0.0, 300.0, 200.0), 0.0, rotatable = true))
        assertEquals(Handle.entries.size, handleCount())
        rule.onNodeWithTag("selection-overlay").assertIsDisplayed()
    }

    @Test
    fun overlay_should_ShowFourCornersAndRotation_When_TheSelectionIsMedium() {
        show(SelectionFrame(SceneBox(0.0, 0.0, 100.0, 100.0), 0.0, rotatable = true))
        assertEquals(CORNERS_AND_ROTATE, handleCount())
    }

    @Test
    fun overlay_should_ShowNoHandlesButTheHint_When_TheSelectionIsTiny() {
        show(SelectionFrame(SceneBox(0.0, 0.0, 20.0, 20.0), 0.0, rotatable = true))
        assertEquals(0, handleCount())
        rule.onNodeWithTag("handles-hint").assertIsDisplayed()
    }

    @Test
    fun overlay_should_DropTheRotationHandle_When_TheSelectionCannotRotate() {
        show(SelectionFrame(SceneBox(0.0, 0.0, 300.0, 200.0), 0.0, rotatable = false))
        assertEquals(Handle.entries.size - 1, handleCount())
        assertEquals(0, rule.onAllNodesWithTagCount("handle-Rotate"))
    }

    @Test
    fun overlay_should_ShowNothing_When_NothingIsSelected() {
        rule.setContent { SelectionOverlay(null, null, SketchViewport.of()) }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTagCount("selection-overlay"))
    }

    @Test
    fun selectionBar_should_OfferTransformActions_WhenNoHandleFits() {
        val chosen = mutableListOf<TransformAction>()
        var duplicated = 0
        rule.setContent { SelectionBar(count = 2, onDuplicate = { duplicated++ }, onDelete = {}, onTransform = { chosen += it }) }
        rule.onNodeWithTag("selection-count").assertIsDisplayed()
        rule.onNodeWithTag("selection-duplicate").performClick()
        assertEquals(1, duplicated)
        rule.onNodeWithTag("selection-transform").performClick()
        rule.onNodeWithTag("transform-Rotate").performClick()
        rule.onNodeWithTag("selection-transform").performClick()
        rule.onNodeWithTag("transform-Resize").performClick()
        assertEquals(listOf(TransformAction.Rotate, TransformAction.Resize), chosen)
    }

    private companion object {
        const val SIZE_STEP = 8
        const val SIZE_STRIDE = 3
        const val EPS = 1e-6
        const val MIN_CASES = 1_000
        const val PAN = 50.0
        const val CORNERS_AND_ROTATE = 5
    }
}
