// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.stapler.stelekit.model.MeasurementUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

/** X-68c / UX-68: the value editor at 360 dp, font scale 2.0 and RTL wraps, pins Cancel and Apply, and scrolls. */
@RunWith(RobolectricTestRunner::class)
class ValueEditorTextScaleTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun valueEditor_should_WrapAndPinCancelApply_At360dpFontScale2AndRtl() {
        val context = ValueEditorContext(
            initialMeters = 0.9, displayUnit = MeasurementUnit.CENTIMETERS, photoCalibrated = true,
            segmentPixelLength = 430.0, currentMetersPerPixel = 0.0021, otherMeasurementCount = 12,
        )
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                Box(Modifier.width(360.dp).height(480.dp)) {
                    MeasurementValueEditorContent(context, onApply = {}, onDismiss = {})
                }
            }
        }
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Apply").assertIsDisplayed()
        for (label in listOf("Use as known distance", "Change label only")) {
            val b = rule.onNode(hasText(label, substring = true) and isSelectable()).getUnclippedBoundsInRoot()
            assertTrue(b.height >= 48.dp, "$label row ${b.height} < 48dp")
            assertTrue(b.left >= 0.dp && b.right <= 360.dp + 0.5.dp, "$label row clipped horizontally: $b")
        }
        val scrolls = rule.onAllNodes(SemanticsMatcher("vertical scroll") { it.config.getOrNull(SemanticsActions.ScrollBy) != null })
            .fetchSemanticsNodes()
        assertTrue(scrolls.isNotEmpty(), "sheet content must scroll vertically")
        for (label in listOf("Cancel", "Apply")) {
            assertTrue(rule.onNodeWithText(label).getUnclippedBoundsInRoot().height >= 48.dp, "$label target < 48dp")
        }
    }
}
