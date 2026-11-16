// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** I-E4-01, I-E4-02 and X-39: chips for real lenses, zoom stops only for one lens, nothing for none. */
@RunWith(RobolectricTestRunner::class)
class CameraLensChipsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun back(id: String, focal: Double, w: Double, h: Double) = CameraLensInfo(id, LensFacing.BACK, focal, w, h)

    private val three = LensClassifier.classify(
        listOf(back("2", 2.2, 4.8, 3.6), back("0", 5.6, 6.4, 4.8), back("3", 9.0, 5.6, 4.2)),
    )

    @Test
    fun dialog_should_ShowThreeChipsWith1xSelected_When_CatalogReturnsThreeLenses() {
        var picked: String? = null
        rule.setContent {
            CameraLensChips(three, selectedId = "0", disabledReasons = emptyMap(), bindingId = null, enabled = true, onSelect = { picked = it.id })
        }
        val main = rule.onNode(hasContentDescription("1x", substring = true) and androidx.compose.ui.test.hasClickAction())
        main.assertIsSelected()
        three.forEach { lens ->
            val node = rule.onNode(hasContentDescription(lens.label, substring = true) and androidx.compose.ui.test.hasClickAction())
            node.assertHasClickAction()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.width >= 48.dp && bounds.height >= 48.dp, "chip ${lens.label} is ${bounds.width} x ${bounds.height}")
        }
        rule.onNode(hasContentDescription("ultra wide", substring = true)).performClick()
        assertEquals("2", picked)
    }

    @Test
    fun dialog_should_ShowOnlyZoomStops_When_OneLens() {
        val stops = LensClassifier.classify(listOf(back("0", 5.6, 6.4, 4.8).copy(minZoomRatio = 1.0, maxZoomRatio = 8.0)))
        rule.setContent { CameraLensChips(stops, "0@1", emptyMap(), null, true, {}) }
        rule.onNodeWithContentDescription("2x, digital zoom", substring = true).assertExists()
        assertEquals(listOf("1x", "2x", "5x"), stops.map { it.label })
    }

    @Test
    fun dialog_should_ShowNoChips_When_NoZoomRange() {
        rule.setContent { CameraLensChips(emptyList(), null, emptyMap(), null, true, {}, Modifier) }
        rule.onNodeWithContentDescription("1x", substring = true).assertDoesNotExist()
    }

    @Test
    fun chips_should_ShowOnlyExposedLensesAndLabelDigitalStops_AndDisableAFailedLensWithItsReason() {
        rule.setContent {
            CameraLensChips(three, "0", mapOf("3" to "Not available right now"), null, true, {})
        }
        rule.onNodeWithContentDescription("unavailable: Not available right now", substring = true).assertIsNotEnabled()
    }
}
