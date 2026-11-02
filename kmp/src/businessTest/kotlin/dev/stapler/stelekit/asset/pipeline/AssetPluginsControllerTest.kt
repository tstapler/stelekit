@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetIndexingController
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.Label
import dev.stapler.stelekit.platform.ml.NoOpPdfTextExtractor
import dev.stapler.stelekit.platform.ml.NoOpTextRecognizer
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.wiring.FakeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Settings > Plugins controller: switches, charger gate, retry, init failure, prompt dismissal. */
class AssetPluginsControllerTest {
    private val settings = FakeSettings().apply { putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true) }
    private val repo = InMemoryAssetRepository()
    private val fs = FakeFileSystem()
    private val labelerCalls = MutableStateFlow(0)
    private val closeables = mutableListOf<() -> Unit>()

    private class Power(initial: Boolean) : PowerSource {
        val state = MutableStateFlow(initial)
        override val isCharging: StateFlow<Boolean> = state
    }

    @AfterTest
    fun tearDown() = closeables.forEach { it() }

    private fun asset(id: String, failed: Boolean = false) = AssetEntry(
        uuid = AssetUuid(id), filePath = "/g/assets/$id.png", relativePath = "../assets/$id.png",
        mediaType = AssetMediaType.IMAGE, subfolder = "", tags = emptyList(), autoLabels = emptyList(),
        ocrText = null, cloudDescription = null, pageUuids = emptyList(), sizeBytes = 3, importedAtMs = 0,
        mlProcessed = false, mlAttemptedAt = null, mlFailed = failed, contentHash = null, isOrphan = false,
        mlTagsSource = "NONE",
    )

    private fun seed(id: String, failed: Boolean = false) = runBlocking {
        fs.writeFileBytes("/g/assets/$id.png", byteArrayOf(1, 2, 3))
        repo.saveAsset(asset(id, failed))
    }

    private fun labeler(throwing: Throwable? = null) = object : ImageLabeler {
        override suspend fun labelImage(imageBytes: ByteArray): Either<DomainError, List<Label>> {
            labelerCalls.update { it + 1 }
            throwing?.let { throw it }
            return listOf(Label("cat", 0.9f)).right()
        }
    }

    private fun controller(power: PowerSource = AlwaysPowered, imageLabeler: ImageLabeler = labeler(), store: Settings = settings): AssetPluginsController {
        val caps = PlatformCaps(imageLabeler, NoOpPdfTextExtractor(), NoOpTextRecognizer(), power)
        val registry = PluginRegistry(store, caps)
        val service = AssetPipelineService(
            registry, repo, DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()),
            pluginSource = { defaultAssetPlugins(caps, store, fs) },
        )
        val indexing = AssetIndexingController(store, AssetIndexService(repo), toggleVisibleProvider = { true })
        val c = AssetPluginsController(registry, service, indexing, store, power)
        closeables += { c.close(); service.shutdown(); indexing.close() }
        return c
    }

    private fun awaitLabeler(n: Int) {
        runBlocking { withTimeout(10_000) { labelerCalls.first { it >= n } } }
        assertEquals(n, labelerCalls.value)
    }

    @Test
    fun onBattery_should_ParkTheDrainAsWaitingForCharger_And_StartWhenPowerConnects() {
        seed("a")
        val power = Power(initial = false)
        val c = controller(power)

        assertTrue(c.setPluginEnabled(PluginEnableKeys.IMAGE_LABELS, true).isRight())
        assertTrue(c.waitingForCharger.value)
        assertEquals(0, labelerCalls.value, "nothing runs on battery")

        power.state.value = true
        awaitLabeler(1)
        assertFalse(c.waitingForCharger.value)
    }

    @Test
    fun processNow_should_RunOnBattery_And_CancelClearsTheWait() {
        seed("a")
        val c = controller(Power(initial = false))
        c.setPluginEnabled(PluginEnableKeys.IMAGE_LABELS, true)
        assertTrue(c.waitingForCharger.value)

        c.cancel()
        assertFalse(c.waitingForCharger.value)
        assertTrue(c.stoppedByUser.value)

        c.processNow()
        awaitLabeler(1)
        assertFalse(c.stoppedByUser.value)
    }

    @Test
    fun turningLastSwitchOff_should_StopTheDrainAndNeverWait() {
        seed("a")
        val c = controller(Power(initial = false))
        c.setPluginEnabled(PluginEnableKeys.IMAGE_LABELS, true)

        c.setPluginEnabled(PluginEnableKeys.IMAGE_LABELS, false)

        assertFalse(c.waitingForCharger.value)
        assertEquals(0, c.registry.enabledCount.value)
    }

    @Test
    fun retryFailed_should_ClearFailedFlagsAndDrainThemAgain() = runBlocking<Unit> {
        seed("bad", failed = true)
        val c = controller()
        c.setPluginEnabled(PluginEnableKeys.IMAGE_LABELS, true)
        assertEquals(0, labelerCalls.value, "a failed row is not retried by an ordinary drain")

        c.retryFailed()
        awaitLabeler(1)

        val processed = withTimeout(10_000) {
            repo.getAssetByUuid(AssetUuid("bad")).first { it.getOrNull()?.mlProcessed == true }.getOrNull()
        }
        assertTrue(processed?.mlProcessed == true && !processed.mlFailed)
    }

    @Test
    fun missingNativeLibrary_should_SwitchThePluginOffAndReportItByName() {
        seed("a")
        val c = controller(imageLabeler = labeler(throwing = UnsatisfiedLinkError("libmlkit.so")))

        c.setPluginEnabled(PluginEnableKeys.IMAGE_LABELS, true)
        runBlocking { withTimeout(10_000) { c.startFailure.first { it != null } } }

        assertEquals("image labels", c.startFailure.value)
        assertFalse(settings.getBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.IMAGE_LABELS), true))
        c.dismissStartFailure()
        assertNull(c.startFailure.value)
    }

    @Test
    fun switchWrite_should_FailWithLeftAndKeepStoredValue_When_SettingsThrow() {
        val failing = object : Settings by settings {
            override fun putBoolean(key: String, value: Boolean) {
                if (key.startsWith("asset_plugin_")) error("disk full") else settings.putBoolean(key, value)
            }
        }
        val c = controller(store = failing)

        assertTrue(c.setPluginEnabled(PluginEnableKeys.IMAGE_LABELS, true).isLeft())
        assertEquals(false, c.registry.enabledStates.value[PluginEnableKeys.IMAGE_LABELS])
    }

    @Test
    fun promptDismissal_should_PersistAcrossControllers_And_ClearOnRequest() {
        val first = controller()
        assertFalse(first.promptDismissed.value)
        first.dismissPrompt()
        assertTrue(controller().promptDismissed.value, "a new controller reads the stored dismissal")
        first.clearPromptDismissal()
        assertFalse(controller().promptDismissed.value)
    }

    @Test
    fun registryLoad_should_ReportLoadedOrFailedAndMapPluginIds() = runBlocking<Unit> {
        val c = controller()
        assertEquals(PluginSettingsLoad.Loading, c.registry.loadState.value)
        c.registry.load()
        assertEquals(PluginSettingsLoad.Loaded, c.registry.loadState.value)

        var failing = false
        val flaky = object : Settings by settings {
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
                if (failing && key.startsWith("asset_plugin_")) error("read failed")
                return settings.getBoolean(key, defaultValue)
            }
        }
        val registry = PluginRegistry(flaky, PlatformCaps.NONE)
        failing = true
        registry.load()
        assertEquals(PluginSettingsLoad.Failed, registry.loadState.value)
        assertEquals(0, registry.enabledCount.value)

        assertEquals(PluginEnableKeys.TEXT_RECOGNITION, PluginRegistry.settingsIdFor("ocr"))
        assertEquals(PluginEnableKeys.IMAGE_LABELS, PluginRegistry.settingsIdFor("on_device_labeling"))
        assertEquals(PluginEnableKeys.PDF_TEXT, PluginRegistry.settingsIdFor("pdf_text"))
        assertNull(PluginRegistry.settingsIdFor("cloud_claude"))
        assertEquals("PDF text", PluginRegistry.displayName(PluginEnableKeys.PDF_TEXT))
    }
}
