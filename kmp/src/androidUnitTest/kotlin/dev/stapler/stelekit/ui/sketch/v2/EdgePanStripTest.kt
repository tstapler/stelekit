// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.EdgeExclusionRect
import dev.stapler.stelekit.platform.EdgeGestureExclusion
import dev.stapler.stelekit.sketch.v2.runtime.settings.EdgePanSide
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.1.2 (UX-102, UX-121, D-18; Task 3.1.2g): the strip composable with a fake exclusion port. The policy, the geometry, the
 * hit routing and the backstop are pure and run in `EdgePanPolicyTest`. Robolectric; Gradle-only like the other Compose host tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-mdpi")
class EdgePanStripTest {
    @get:Rule
    val rule = createComposeRule()

    private class FakeExclusion : EdgeGestureExclusion {
        override val canRequest = true
        val requests = mutableListOf<List<EdgeExclusionRect>>()
        var releases = 0
        override fun request(rects: List<EdgeExclusionRect>) {
            requests.add(rects)
        }

        override fun release() {
            releases += 1
        }
    }

    private val host = EdgePanGeometry.Host(widthDp = WIDTH_DP, heightDp = HEIGHT_DP, insetStartDp = INSET_DP, insetEndDp = INSET_DP)

    @Test
    fun ux102_strip_should_StartAtInset_RegisterExclusionRectForProtectedSpanOnly_And_ReleaseWhenHidden() {
        val exclusion = FakeExclusion()
        var side by mutableStateOf(EdgePanSide.Left)
        rule.setContent { EdgePanStrip(EdgePanGeometry.strips(side, host), WIDTH_DP, exclusion) }
        rule.waitForIdle()

        rule.onNodeWithTag(EdgePanTags.band(StripEdge.Left)).assertLeftPositionInRootIsEqualTo(INSET_DP.dp)
        val rect = exclusion.requests.last().single()
        assertEquals(EdgePanGeometry.EXPECTED_SPAN_DP, rect.height, "the span only, not the whole edge")
        assertTrue(rect.height < HEIGHT_DP.toInt())

        side = EdgePanSide.Off
        rule.waitForIdle()
        rule.onNodeWithTag(EdgePanTags.LAYER).assertDoesNotExist()
        assertEquals(1, exclusion.releases, "hiding the strip hands the edge back to the system")
    }

    @Test
    fun strip_should_Be24dpVisibleOverA200dpSpan() {
        rule.setContent { EdgePanStrip(EdgePanGeometry.strips(EdgePanSide.Left, host), WIDTH_DP, null) }
        rule.onNodeWithTag(EdgePanTags.band(StripEdge.Left)).assertWidthIsEqualTo(EdgePanGeometry.BAND_DP.dp)
        rule.onNodeWithTag(EdgePanTags.band(StripEdge.Left)).assertHeightIsEqualTo(EdgePanGeometry.EXPECTED_SPAN_DP.dp)
    }

    @Test
    fun stripAbsentFromSemantics_And_OtherPanPathsPass_When_Off() {
        rule.setContent { Box { EdgePanStrip(EdgePanGeometry.strips(EdgePanSide.Off, host), WIDTH_DP, FakeExclusion()) } }
        rule.onNodeWithTag(EdgePanTags.LAYER).assertDoesNotExist()
        val adapter = SketchPointerAdapter(fingerDrawing = true, handTool = true)
        assertTrue(adapter.edgeStrips.isEmpty(), "no strip rectangle reaches the adapter; the Hand tool still pans")
    }

    @Test
    fun visibleBand_should_Meet3to1Contrast_InLightDarkAndHighContrast() {
        val highContrast = lightColorScheme(background = Color.White, surface = Color.White, outline = Color.Black)
        for ((name, scheme) in listOf("light" to lightColorScheme(), "dark" to darkColorScheme(), "high contrast" to highContrast)) {
            assertTrue(contrast(EdgePanBand.color(scheme), scheme.surface) >= MIN_NON_TEXT_CONTRAST, "$name band against the surface")
            assertTrue(contrast(EdgePanBand.color(scheme), scheme.background) >= MIN_NON_TEXT_CONTRAST, "$name band against the background")
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + LUMA_OFFSET) / (lo + LUMA_OFFSET)
    }

    private companion object {
        const val WIDTH_DP = 360.0
        const val HEIGHT_DP = 640.0
        const val INSET_DP = 24.0
        const val MIN_NON_TEXT_CONTRAST = 3.0
        const val LUMA_OFFSET = 0.05
    }
}
