// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.platform.sensor.CameraLens
import dev.stapler.stelekit.platform.sensor.CameraLensCatalog
import dev.stapler.stelekit.platform.sensor.SensorModule
import dev.stapler.stelekit.platform.sensor.defaultBackLens
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The dialog under Robolectric has no camera HAL, so its bind fails: this drives composition, the catalog effect,
 * the all-failed screen and the close paths. A real bind and capture are covered by the owner device checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp", sdk = [29])
class CameraViewfinderDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val originalCatalog = SensorModule.cameraLensCatalog

    @After
    fun restore() {
        SensorModule.cameraLensCatalog = originalCatalog
    }

    @Test
    fun dialog_should_ShowTheUnavailableScreen_AndCloseFromIt_When_NoCameraCanBind() {
        SensorModule.cameraLensCatalog = object : CameraLensCatalog {
            override suspend fun lenses(): List<CameraLens> = listOf(defaultBackLens())
        }
        var dismissed = 0
        rule.setContent { CameraViewfinderDialog(onCapture = {}, onDismiss = { dismissed++ }, onError = {}) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithTextCount(CAMERA_UNAVAILABLE) > 0
        }
        rule.onNodeWithText(CAMERA_UNAVAILABLE).assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun dialog_should_Dismiss_When_CancelIsPressed_AndSurviveAThrowingCatalog() {
        SensorModule.cameraLensCatalog = object : CameraLensCatalog {
            override suspend fun lenses(): List<CameraLens> = error("catalog exploded")
        }
        var dismissed = 0
        rule.setContent { CameraViewfinderDialog(onCapture = {}, onDismiss = { dismissed++ }, onError = {}) }
        rule.onNodeWithContentDescription("Cancel").performClick()
        assertEquals(1, dismissed)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
