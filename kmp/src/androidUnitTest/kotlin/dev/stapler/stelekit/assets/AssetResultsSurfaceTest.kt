// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.assets

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetIndexingController
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.asset.pipeline.AssetPluginsController
import dev.stapler.stelekit.asset.pipeline.PauseReason
import dev.stapler.stelekit.asset.pipeline.PipelineProgress
import dev.stapler.stelekit.asset.pipeline.PipelineRunMarker
import dev.stapler.stelekit.asset.pipeline.PluginEnableKeys
import dev.stapler.stelekit.asset.pipeline.PluginRegistry
import dev.stapler.stelekit.asset.pipeline.PlatformCaps
import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.Label
import dev.stapler.stelekit.platform.ml.NoOpPdfTextExtractor
import dev.stapler.stelekit.platform.ml.NoOpTextRecognizer
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.ui.assets.AssetBrowserStatusHeader
import dev.stapler.stelekit.ui.assets.assetBrowserProcessingLine
import dev.stapler.stelekit.ui.assets.shouldShowSearchablePrompt
import dev.stapler.stelekit.wiring.FakeSettings
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 5.6 surfaces implemented in U10: the prompt card with persisted dismissal and the inline processing line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AssetResultsSurfaceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val settings = FakeSettings()
    private val closeables = mutableListOf<() -> Unit>()

    @After
    fun tearDown() = closeables.forEach { it() }

    private val labeler = object : ImageLabeler {
        override suspend fun labelImage(imageBytes: ByteArray) =
            arrow.core.Either.Right(emptyList<Label>())
    }

    private fun controller(): AssetPluginsController {
        val caps = PlatformCaps(labeler, NoOpPdfTextExtractor(), NoOpTextRecognizer())
        val indexing = AssetIndexingController(
            settings, AssetIndexService(InMemoryAssetRepository()), toggleVisibleProvider = { true },
            runMarker = PipelineRunMarker(settings),
        ).also { closeables += { it.close() } }
        return AssetPluginsController(PluginRegistry(settings, caps), null, indexing, settings)
    }

    private fun asset(type: AssetMediaType) = AssetEntry(
        uuid = AssetUuid("a-$type"), filePath = "/g/assets/a", relativePath = "../assets/a", mediaType = type,
        subfolder = "", tags = emptyList(), autoLabels = emptyList(), ocrText = null, cloudDescription = null,
        pageUuids = emptyList(), sizeBytes = 1, importedAtMs = 0, mlProcessed = false, mlAttemptedAt = null,
        mlFailed = false, contentHash = null, isOrphan = false, mlTagsSource = "NONE",
    )

    @Test
    fun emptyState_should_ShowMakeImagesSearchableCardAndPersistNotNow_When_ImagesAndNoPlugin() {
        val c = controller()
        var opened = 0
        composeRule.setContent { AssetBrowserStatusHeader(listOf(asset(AssetMediaType.IMAGE)), c, onOpenPlugins = { opened++ }) }

        composeRule.onNodeWithText("Make images searchable - turn on text recognition").assertExists()
        composeRule.onNodeWithText("Turn on").performClick()
        assertEquals(1, opened, "Turn on opens Settings > Plugins and never flips a switch itself")
        assertFalse(settings.getBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.IMAGE_LABELS), false))

        composeRule.onNodeWithText("Not now").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodes(hasText("Make images searchable", substring = true)).fetchSemanticsNodes().size)
        assertTrue(settings.getBoolean(AssetPluginsController.PROMPT_DISMISSED_KEY, false), "dismissal is persisted")
        assertTrue(controller().promptDismissed.value, "a fresh controller (next launch) reads the stored dismissal")
    }

    @Test
    fun notNow_should_BeClearableFromPluginsRow_And_CardReturn_When_Cleared() {
        val c = controller()
        c.dismissPrompt()
        composeRule.setContent { AssetBrowserStatusHeader(listOf(asset(AssetMediaType.IMAGE)), c, onOpenPlugins = {}) }
        assertEquals(0, composeRule.onAllNodes(hasText("Make images searchable", substring = true)).fetchSemanticsNodes().size)

        c.clearPromptDismissal()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Make images searchable - turn on text recognition").assertExists()
    }

    @Test
    fun promptRule_should_RequireAnImageNoEnabledPluginAndNoDismissal() {
        assertTrue(shouldShowSearchablePrompt(hasImage = true, enabledPlugins = 0, dismissed = false))
        assertFalse(shouldShowSearchablePrompt(hasImage = false, enabledPlugins = 0, dismissed = false))
        assertFalse(shouldShowSearchablePrompt(hasImage = true, enabledPlugins = 1, dismissed = false))
        assertFalse(shouldShowSearchablePrompt(hasImage = true, enabledPlugins = 0, dismissed = true))
    }

    @Test
    fun assetsBrowser_should_ShowProcessingLineLinkingToProgressRowAndHideWhenIdle_When_PipelineRunsOrIdle() {
        assertEquals("Processing 340 of 2,100", assetBrowserProcessingLine(PipelineProgress.Running(340, 2100, 0)))
        assertEquals("Waiting for charger", assetBrowserProcessingLine(PipelineProgress.Paused(PauseReason.Charger)))
        assertNull(assetBrowserProcessingLine(PipelineProgress.Idle))
        assertNull(assetBrowserProcessingLine(PipelineProgress.Finished(5, 0)))

        val c = controller()
        composeRule.setContent { AssetBrowserStatusHeader(emptyList(), c, onOpenPlugins = {}) }
        assertEquals(0, composeRule.onAllNodes(hasText("details")).fetchSemanticsNodes().size, "hidden while idle")
    }
}
