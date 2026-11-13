// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

// jvmTest-reason: n/a (androidUnitTest, Robolectric); Gradle-only, not registered in AllAndroidUnitTests.
package dev.stapler.stelekit.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.ui.annotate.DepthEstimationPanel
import dev.stapler.stelekit.ui.annotate.DepthModelUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UX-38: each model-download state change is announced through a polite live region. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DepthEstimationPanelLiveRegionTest {
    @get:Rule
    val rule = createComposeRule()

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun downloadStates_should_beInPoliteLiveRegion_When_StateChanges() {
        var current by mutableStateOf<DepthModelUiState>(DepthModelUiState.Downloading(10))
        rule.setContent { DepthEstimationPanel(current, false, null, onDownload = {}, onCancel = {}, onDownloadAnyway = {}) }
        val states = listOf(
            DepthModelUiState.Downloading(10),
            DepthModelUiState.Waiting("Waiting for an unmetered network", canDownloadAnyway = true),
            DepthModelUiState.Failed("Download failed after 5 tries."),
            DepthModelUiState.Cancelled,
        )
        for (state in states) {
            current = state
            rule.waitForIdle()
            rule.onNode(polite).assertExists("no polite live region for $state")
        }
    }

    @Test
    fun idleModel_should_notAnnounce_When_NoDownloadInFlight() {
        rule.setContent { DepthEstimationPanel(DepthModelUiState.Absent, false, null, onDownload = {}) }
        rule.onAllNodes(polite).assertCountEquals(0)
    }
}
