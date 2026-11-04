// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.components.settings.LFS_METERED_LABEL
import dev.stapler.stelekit.ui.components.settings.LfsTransferSection
import dev.stapler.stelekit.ui.components.settings.LfsTransferSettingsState
import dev.stapler.stelekit.ui.components.settings.lfsSizeLabel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class LfsTransferSettingsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val chosenSizes = mutableListOf<Int>()
    private val chosenDefer = mutableListOf<Boolean>()

    private fun state(maxMb: Int = 256, defer: Boolean = false, metered: Boolean = true) = LfsTransferSettingsState(
        maxObjectMb = maxMb,
        deferOnMetered = defer,
        meteredDetectionSupported = metered,
        onMaxObjectMbChange = { chosenSizes += it },
        onDeferOnMeteredChange = { chosenDefer += it },
    )

    private fun show(state: LfsTransferSettingsState) =
        composeTestRule.setContent { MaterialTheme { LfsTransferSection(state) } }

    @Test
    fun section_should_MarkTheCurrentCapSelected() {
        show(state(maxMb = 256))

        composeTestRule.onNodeWithText("256 MB").assertIsSelected()
        composeTestRule.onNodeWithText("Largest image to download automatically").assertIsDisplayed()
    }

    @Test
    fun choosingASize_should_ReportThatSize() {
        show(state(maxMb = 256))

        composeTestRule.onNodeWithText("1 GB").performClick()

        assertEquals(listOf(1024), chosenSizes)
    }

    @Test
    fun aStoredSizeOutsideTheList_should_StillBeShownAndSelected() {
        show(state(maxMb = 300))

        composeTestRule.onNodeWithText("300 MB").assertIsSelected()
    }

    @Test
    fun meteredSwitch_should_ReportTheNewValue_When_Toggled() {
        show(state(defer = false))

        composeTestRule.onNodeWithText(LFS_METERED_LABEL).performClick()

        assertEquals(listOf(true), chosenDefer)
    }

    @Test
    fun meteredSwitch_should_BeHidden_When_PlatformCannotDetectMeteredNetworks() {
        show(state(metered = false))

        composeTestRule.onNodeWithText(LFS_METERED_LABEL).assertDoesNotExist()
    }

    @Test
    fun sizeLabel_should_UseGigabytesForWholeGigabytes() {
        assertEquals("1 GB", lfsSizeLabel(1024))
        assertEquals("2 GB", lfsSizeLabel(2048))
        assertEquals("512 MB", lfsSizeLabel(512))
        assertEquals("1500 MB", lfsSizeLabel(1500))
    }
}
