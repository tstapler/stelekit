// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.stapler.stelekit.platform.sensor.CameraLensInfo
import dev.stapler.stelekit.platform.sensor.LensClassifier
import dev.stapler.stelekit.platform.sensor.LensFacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** I-E4-03 and X-68e: failure and starting states always offer a way out, and chips keep 48 dp targets at font scale 2. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class CameraDialogStatesTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun dialog_should_ShowAllLensesFailedScreenWithTryAgainAndClose() {
        var retried = 0
        var closed = 0
        rule.setContent { CameraUnavailableScreen(onRetry = { retried++ }, onClose = { closed++ }) }
        rule.onNodeWithText(CAMERA_UNAVAILABLE).assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, retried)
        assertEquals(1, closed)
    }

    @Test
    fun dialog_should_ShowStartingStateWhileNoFrameHasArrived() {
        rule.setContent { CameraStartingOverlay() }
        rule.onNodeWithText(CAMERA_STARTING).assertIsDisplayed()
    }

    @Test
    fun statusLine_should_ShowItsText() {
        rule.setContent { CameraStatusLine("Lens unavailable") }
        rule.onNodeWithText("Lens unavailable").assertIsDisplayed()
    }

    @Test
    fun chips_should_ShowProgressOnTheBindingChip_AndDisableOthers() {
        val lenses = LensClassifier.classify(
            listOf(
                CameraLensInfo("0", LensFacing.BACK, 5.6, 6.4, 4.8, minZoomRatio = 1.0, maxZoomRatio = 8.0),
            ),
        )
        var picked: String? = null
        rule.setContent {
            CameraLensChips(lenses, selectedId = lenses.first().id, disabledReasons = emptyMap(), bindingId = lenses.last().id,
                enabled = false, onSelect = { picked = it.id })
        }
        rule.onNodeWithText(lenses.first().label, substring = true).assertIsDisplayed()
        assertEquals(null, picked)
    }

    @Test
    fun hint_should_BeDismissible() {
        var dismissed = false
        rule.setContent { WideAngleHint(onDismiss = { dismissed = true }) }
        rule.onNodeWithText("Dismiss").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun chipsAndTopBar_should_WrapAndKeepTargets_At360dpFontScale2AndRtl() {
        val lenses = LensClassifier.classify(
            listOf(
                CameraLensInfo("2", LensFacing.BACK, 2.2, 4.8, 3.6),
                CameraLensInfo("0", LensFacing.BACK, 5.6, 6.4, 4.8),
                CameraLensInfo("3", LensFacing.BACK, 9.0, 5.6, 4.2),
            ),
        )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                CameraLensChips(lenses, "0", emptyMap(), null, true, {})
            }
        }
        lenses.forEach { lens ->
            val node = rule.onNode(androidx.compose.ui.test.hasContentDescription(lens.label, substring = true) and androidx.compose.ui.test.hasClickAction())
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.height >= 48.dp, "chip ${lens.label} height ${bounds.height}")
            assertTrue(bounds.right <= 360.dp, "chip ${lens.label} overflows the 360 dp width")
        }
    }
}
