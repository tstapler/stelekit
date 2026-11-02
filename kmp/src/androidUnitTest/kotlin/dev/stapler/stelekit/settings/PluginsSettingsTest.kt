// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetIndexingController
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.asset.pipeline.AssetPipelineService
import dev.stapler.stelekit.asset.pipeline.AssetPluginsController
import dev.stapler.stelekit.asset.pipeline.PipelineRunMarker
import dev.stapler.stelekit.asset.pipeline.PlatformCaps
import dev.stapler.stelekit.asset.pipeline.PluginEnableKeys
import dev.stapler.stelekit.asset.pipeline.PluginRegistry
import dev.stapler.stelekit.asset.pipeline.defaultAssetPlugins
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.Label
import dev.stapler.stelekit.platform.ml.PdfTextExtractor
import dev.stapler.stelekit.platform.ml.TextRecognizer
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.ui.components.settings.PluginsSettings
import dev.stapler.stelekit.wiring.FakeSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 5.5: Settings > Plugins with per-plugin switches bound to the `asset_plugin_<id>_enabled` keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PluginsSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val settings = FakeSettings()
    private val labelerCalls = AtomicInteger()
    private val repo = InMemoryAssetRepository()
    private val fs = FakeFileSystem()
    private val closeables = mutableListOf<() -> Unit>()

    private val fullCaps = PlatformCaps(
        object : ImageLabeler {
            override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> {
                labelerCalls.incrementAndGet()
                return listOf(Label("cat", 0.9f)).right()
            }
        },
        object : PdfTextExtractor {
            override suspend fun extractText(absoluteFilePath: String): Either<DomainError, String> = "text".right()
        },
        object : TextRecognizer {
            override suspend fun recognize(imageBytes: ByteArray): Either<DomainError, String> = "text".right()
        },
    )

    @After
    fun tearDown() {
        closeables.forEach { it() }
        AssetBuildFlags.internalBuild = false
    }

    private fun indexing(store: Settings = settings) = AssetIndexingController(
        store, AssetIndexService(repo), toggleVisibleProvider = { true }, runMarker = PipelineRunMarker(store),
    ).also { closeables += { it.close() } }

    private fun controller(
        caps: PlatformCaps = fullCaps,
        store: Settings = settings,
        withPipeline: Boolean = false,
    ): AssetPluginsController {
        val registry = PluginRegistry(store, caps)
        val pipeline = if (withPipeline) {
            AssetPipelineService(
                registry, repo, DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()),
                pluginSource = { defaultAssetPlugins(caps, store, fs) },
            ).also { closeables += { it.shutdown() } }
        } else {
            null
        }
        return AssetPluginsController(registry, pipeline, indexing(store), store)
    }

    private fun mount(c: AssetPluginsController, onViewLog: () -> Unit = {}) =
        composeRule.setContent { PluginsSettings(c.indexing, c, onViewLog) }


    @Test
    fun plugins_should_ListThreeSwitchesDefaultOffAndNotNoPluginsInstalled_When_Opened() {
        mount(controller())

        listOf("Text recognition", "Image labels", "PDF text").forEach { title ->
            composeRule.onNodeWithText(title).assertExists()
        }
        // Master "Asset indexing" switch plus three plugin switches, all off.
        val toggles = composeRule.onAllNodes(isToggleable())
        assertEquals(4, toggles.fetchSemanticsNodes().size)
        (0 until 4).forEach { toggles[it].assertIsOff() }
        assertEquals(0, composeRule.onAllNodes(hasText("No plugins installed")).fetchSemanticsNodes().size)
    }

    @Test
    fun pluginRow_should_BeDisabledWithTextReason_When_UnsupportedOrModelMissing() {
        mount(controller(caps = PlatformCaps.NONE))

        composeRule.onNodeWithText("model not installed").assertExists()
        assertEquals(
            2,
            composeRule.onAllNodes(hasText("not available on desktop")).fetchSemanticsNodes().size,
            "text recognition and PDF text are both unavailable, shown as rows with a reason, never hidden",
        )
        composeRule.onAllNodes(isToggleable())[1].assertIsNotEnabled()
        composeRule.onAllNodes(isToggleable())[0].assertIsEnabled()
    }

    @Test
    fun pluginRows_should_ShowModelNotInstalledAndNotAvailableOnDesktop_When_NoOpCapabilities() {
        val c = controller(caps = PlatformCaps.NONE)
        mount(c)

        composeRule.onNodeWithText("model not installed").assertExists()
        composeRule.onNodeWithText("Image labels").assertExists()
        assertEquals(PluginRegistry.MODEL_NOT_INSTALLED, c.registry.unavailableReason(PluginEnableKeys.IMAGE_LABELS))
        assertEquals(PluginRegistry.NOT_AVAILABLE_ON_DESKTOP, c.registry.unavailableReason(PluginEnableKeys.TEXT_RECOGNITION))
    }

    @Test
    fun pluginSwitch_should_PersistKeyAndStartDrainWithoutRestart_When_TurnedOn() {
        AssetBuildFlags.internalBuild = true
        settings.putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true)
        seedImage()
        val c = controller(withPipeline = true)
        mount(c)
        c.indexing.onGraphReady(
            dev.stapler.stelekit.model.EffectiveNotesPath("/g"), null, fs,
        )
        assertFalse(settings.getBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.IMAGE_LABELS), false))
        assertEquals(0, labelerCalls.get(), "master on and no plugin key set runs no plugin")

        composeRule.onAllNodes(isToggleable())[2].performClick()
        composeRule.waitForIdle()

        assertTrue(settings.getBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.IMAGE_LABELS), false), "the key is persisted")
        awaitLabelerCall()
        assertEquals(1, c.registry.enabledCount.value)
    }

    @Test
    fun pluginSwitch_should_SnapBackWithCouldNotSaveMessage_When_SettingsWriteFails() {
        val failing = object : Settings by settings {
            override fun putBoolean(key: String, value: Boolean) {
                if (key.startsWith("asset_plugin_")) error("disk full") else settings.putBoolean(key, value)
            }
        }
        val c = controller(store = failing)
        mount(c)

        composeRule.onAllNodes(isToggleable())[1].performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Couldn't save this setting").assertExists()
        composeRule.onAllNodes(isToggleable())[1].assertIsOff()
        assertFalse(settings.getBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.TEXT_RECOGNITION), false))
    }

    @Test
    fun notNow_should_BeClearableFromPluginsRow_And_CardReturn_When_Cleared() {
        val c = controller()
        mount(c)
        assertEquals(0, composeRule.onAllNodes(hasText("Show the searchable-images suggestion again")).fetchSemanticsNodes().size, "hidden while the card is not dismissed")

        c.dismissPrompt()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Show the searchable-images suggestion again").performClick()
        composeRule.waitForIdle()

        assertFalse(c.promptDismissed.value, "the card may show again")
        assertFalse(settings.getBoolean(AssetPluginsController.PROMPT_DISMISSED_KEY, true))
        assertEquals(0, composeRule.onAllNodes(hasText("Show the searchable-images suggestion again")).fetchSemanticsNodes().size)
    }

    @Test
    fun viewLog_should_OpenLogsPrefilteredToPipelineLines_When_DeveloperModeOff() {
        val failing = object : Settings by settings {
            override fun putBoolean(key: String, value: Boolean) {
                if (key.startsWith("asset_plugin_")) error("disk full") else settings.putBoolean(key, value)
            }
        }
        var opened = 0
        mount(controller(store = failing), onViewLog = { opened++ })

        composeRule.onAllNodes(isToggleable())[1].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("View log").performClick()

        assertEquals(1, opened, "View log is on the row itself; no Developer setting is involved")
    }

    @OptIn(DirectRepositoryWrite::class)
    private fun seedImage() {
        fs.writeFileBytes("/g/assets/img.png", byteArrayOf(1, 2, 3))
        runBlocking {
            repo.saveAsset(
                AssetEntry(
                    uuid = AssetUuid("img"), filePath = "/g/assets/img.png", relativePath = "../assets/img.png",
                    mediaType = AssetMediaType.IMAGE, subfolder = "", tags = emptyList(), autoLabels = emptyList(),
                    ocrText = null, cloudDescription = null, pageUuids = emptyList(), sizeBytes = 3, importedAtMs = 0,
                    mlProcessed = false, mlAttemptedAt = null, mlFailed = false, contentHash = null, isOrphan = false,
                    mlTagsSource = "NONE",
                ),
            )
        }
    }

    private fun awaitLabelerCall() {
        composeRule.waitUntil(10_000) { labelerCalls.get() > 0 }
        assertEquals(1, labelerCalls.get(), "the drain started without a restart and ran the enabled plugin")
    }
}
