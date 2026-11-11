@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.wiring.FakeSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Release rule: the switch is hidden and the backfill never starts in a build that hides it. */
class AssetIndexingControllerTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
    private val notes = EffectiveNotesPath("/graph")
    private val key = AssetBuildFlags.PIPELINE_ENABLED_KEY

    private class SpyRepository(private val delegate: InMemoryAssetRepository) : AssetRepository by delegate {
        @Volatile var lookups = 0
        override suspend fun getIndexedFilePaths(filePaths: List<String>) =
            delegate.getIndexedFilePaths(filePaths).also { lookups++ }
    }

    private val repo = InMemoryAssetRepository()
    private val spy = SpyRepository(repo)
    private val fs = FakeFileSystem().apply { repeat(5) { writeFileBytes("/graph/assets/a$it.png", png) } }

    @AfterTest
    fun resetBuildFlag() {
        AssetBuildFlags.internalBuild = false
    }

    private fun controller(settings: Settings = FakeSettings(), visible: Boolean) =
        AssetIndexingController(settings, AssetIndexService(spy), toggleVisibleProvider = { visible })

    private suspend fun awaitFinished(c: AssetIndexingController) =
        withTimeout(10_000) { c.progress.first { it is AssetIndexProgress.Finished } }

    @Test
    fun releaseFlag_isOffByDefault_and_defaultBuildHidesTheSwitch() {
        assertFalse(AssetBuildFlags.RELEASE_TOGGLE_VISIBLE, "the flip PR (Task 5.2e) is the only change allowed to make this true")
        AssetBuildFlags.internalBuild = false
        assertFalse(AssetBuildFlags.PIPELINE_TOGGLE_VISIBLE)
        AssetBuildFlags.internalBuild = true
        assertTrue(AssetBuildFlags.PIPELINE_TOGGLE_VISIBLE, "debug/internal builds show it")
    }

    @Test
    fun enabledKey_defaultsToFalse() {
        assertFalse(controller(visible = true).enabled.value)
        assertFalse(controller(visible = false).enabled.value)
    }

    @Test
    fun releaseBuild_ignoresTheSwitch_but_stillIndexesTheFilesForTheLibrary() = runBlocking {
        val settings = FakeSettings()
        val c = controller(settings, visible = false)

        c.setEnabled(true)
        c.onGraphReady(notes, writeActor = null, fileSystem = fs)
        awaitFinished(c)

        assertFalse(settings.getBoolean(key, false), "a hidden switch cannot write the key")
        assertFalse(c.isActive, "the plugin stage stays off")
        assertEquals(5L, repo.countAssets().getOrNull(), "the Library's only source must be filled in release builds")
        c.close()
    }

    @Test
    fun releaseBuild_withTheKeyAlreadyTrue_indexesFilesAndAttachmentsButStaysInactive() = runBlocking {
        val settings = FakeSettings().apply { putBoolean(key, true) }
        val c = controller(settings, visible = false)

        c.onGraphReady(notes, writeActor = null, fileSystem = fs)
        awaitFinished(c)
        fs.writeFileBytes("/graph/assets/new.png", png + byteArrayOf(9))
        c.registerAttachment(AttachmentResult("../assets/new.png", "new.png"))

        assertTrue(c.enabled.value, "the stored value is reported as stored")
        assertFalse(c.isActive, "but a build that hides the switch never runs the plugin stage")
        assertEquals(6L, repo.countAssets().getOrNull(), "walked files plus the registered attachment")
        c.close()
    }

    @Test
    fun graphNotReady_neverWalks() = runBlocking {
        val c = controller(visible = true)

        c.onGraphNotReady()

        assertEquals(AssetIndexProgress.Idle, c.progress.value)
        assertEquals(0, spy.lookups)
        c.close()
    }

    @Test
    fun internalBuild_flippingTheSwitch_startsAndStopsWithoutRestart() = runBlocking {
        val settings = FakeSettings()
        val c = controller(settings, visible = true)
        c.onGraphReady(notes, writeActor = null, fileSystem = fs)

        c.setEnabled(true)
        awaitFinished(c)
        assertEquals(5L, repo.countAssets().getOrNull())
        assertTrue(settings.getBoolean(key, false))

        c.setEnabled(false)
        assertFalse(settings.getBoolean(key, true))
        assertFalse(c.isActive)
        c.close()
    }

    @Test
    fun graphNotReady_stopsBackfill_and_laterReadyRestartsIt() = runBlocking {
        val settings = FakeSettings().apply { putBoolean(key, true) }
        val c = controller(settings, visible = true)

        c.onGraphNotReady()
        assertEquals(AssetIndexProgress.Idle, c.progress.value, "a loading graph is never walked")
        assertEquals(0, spy.lookups)

        c.onGraphReady(notes, writeActor = null, fileSystem = fs)
        awaitFinished(c)
        assertEquals(5L, repo.countAssets().getOrNull())
        c.close()
    }

    @Test
    fun settingsReadFailure_countsAsOff() {
        val throwing = object : Settings by FakeSettings() {
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = error("store unavailable")
        }
        assertFalse(controller(throwing, visible = true).enabled.value)
    }

    @Test
    fun attachmentIsRegistered_whenActive_andIndexFailureNeverThrows() = runBlocking {
        val settings = FakeSettings().apply { putBoolean(key, true) }
        val c = controller(settings, visible = true)
        c.onGraphReady(notes, writeActor = null, fileSystem = fs)

        c.registerAttachment(AttachmentResult("../assets/a0.png", "a0.png"))
        c.registerAttachment(AttachmentResult("../assets/missing.png", "missing.png"))

        assertTrue((repo.countAssets().getOrNull() ?: 0L) >= 1L)
        c.close()
    }

    @Test
    fun memoryPressure_stopsARunningBackfill() = runBlocking {
        val settings = FakeSettings().apply { putBoolean(key, true) }
        val c = controller(settings, visible = true)
        c.onGraphReady(notes, writeActor = null, fileSystem = fs)
        assertTrue(c.progress.value !is AssetIndexProgress.Idle, "backfill started")

        c.onMemoryPressure()

        assertEquals(AssetIndexProgress.Idle, c.progress.value, "stopBackfill publishes Idle synchronously")
        c.close()
    }
}
