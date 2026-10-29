// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.asset.AssetIndexProgress
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetIndexingController
import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.ui.components.settings.PluginsSettings
import dev.stapler.stelekit.ui.components.settings.assetIndexStatusLine
import dev.stapler.stelekit.wiring.FakeSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 4.1d: the interim Settings > Plugins "Asset indexing" row (UW-97) and its release-variant gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AssetIndexingSwitchTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val settings = FakeSettings()

    private fun controller(visible: Boolean) =
        AssetIndexingController(settings, AssetIndexService(InMemoryAssetRepository()), toggleVisibleProvider = { visible })

    @Test
    fun internalBuild_showsTheRow_offByDefault_andTheSwitchPersistsTheKey() {
        val c = controller(visible = true)
        composeRule.setContent { PluginsSettings(c) }

        composeRule.onNodeWithText("Asset indexing").assertExists()
        composeRule.onNodeWithText("Scans attached files so they appear in Assets. Off by default.").assertExists()
        composeRule.onNode(isToggleable()).assertIsOff()
        assertFalse(settings.getBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, false))

        composeRule.onNode(isToggleable()).performClick()
        composeRule.waitForIdle()

        composeRule.onNode(isToggleable()).assertIsOn()
        assertTrue(settings.getBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, false))
        c.close()
    }

    @Test
    fun releaseVariant_hidesTheRow_keepsThePlaceholder_andTheKeyStaysFalse() {
        val c = controller(visible = false)
        composeRule.setContent { PluginsSettings(c) }

        composeRule.onNodeWithText("No plugins installed").assertExists()
        assertEquals(0, composeRule.onAllNodes(hasText("Asset indexing")).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
        assertFalse(settings.getBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, false), "default-value test: key unset means false")
        c.close()
    }

    @Test
    fun noController_showsThePlaceholder() {
        composeRule.setContent { PluginsSettings() }
        composeRule.onNodeWithText("No plugins installed").assertExists()
    }

    @Test
    fun statusLine_followsTheIndexWalksOwnProgress() {
        assertNull(assetIndexStatusLine(enabled = false, progress = AssetIndexProgress.Indexing(5, 10)))
        assertNull(assetIndexStatusLine(enabled = true, progress = AssetIndexProgress.Idle))
        assertEquals("Indexing files...", assetIndexStatusLine(true, AssetIndexProgress.Counting))
        assertEquals("Indexing files...", assetIndexStatusLine(true, AssetIndexProgress.Indexing(0, 3000)))
        assertEquals("1200 of 3000 indexed", assetIndexStatusLine(true, AssetIndexProgress.Indexing(1200, 3000)))
        assertEquals("3000 of 3000 indexed", assetIndexStatusLine(true, AssetIndexProgress.Finished(3000)))
    }
}
