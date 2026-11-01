package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.asset.AssetIndexProgress
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetIndexingController
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.wiring.FakeSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pre-mortem P1-2: two consecutive unclean exits turn the pipeline off before it can run a third time. */
class AssetPipelineCrashLoopBreakerTest {
    private val settings = FakeSettings()
    private val flag = AssetBuildFlags.PIPELINE_ENABLED_KEY
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    /** A launch that starts a run and then dies: it never calls [PipelineRunMarker.end]. */
    private fun crashedLaunch(token: String) {
        val marker = PipelineRunMarker(settings, token)
        marker.evaluateAtLaunch()
        marker.begin("index")
    }

    @Test
    fun oneUncleanExit_keepsTheFlagOn_andCountsOne() {
        settings.putBoolean(flag, true)
        crashedLaunch("p1")

        val next = PipelineRunMarker(settings, "p2")
        val tripped = next.evaluateAtLaunch()

        assertFalse(tripped)
        assertTrue(settings.getBoolean(flag, false))
        assertEquals(1, next.uncleanExits)
    }

    @Test
    fun twoConsecutiveUncleanExits_turnTheFlagOff_onTheThirdLaunch_andStartNothing() = runBlocking<Unit> {
        settings.putBoolean(flag, true)
        crashedLaunch("p1")
        crashedLaunch("p2")

        val repo = InMemoryAssetRepository()
        val fs = FakeFileSystem().apply { writeFileBytes("/graph/assets/a.png", png) }
        val controller = AssetIndexingController(
            settings, AssetIndexService(repo), toggleVisibleProvider = { true }, runMarker = PipelineRunMarker(settings, "p3"),
        )
        controller.onGraphReady(EffectiveNotesPath("/graph"), writeActor = null, fileSystem = fs)

        assertTrue(controller.breakerTripped.value)
        assertFalse(controller.enabled.value)
        assertFalse(settings.getBoolean(flag, true), "the flag was forced off")
        assertEquals(AssetIndexProgress.Idle, controller.progress.value, "nothing started")
        assertEquals(0L, repo.countAssets().getOrNull())
        controller.close()
    }

    @Test
    fun aCleanStop_resetsTheCounterToZero() {
        settings.putBoolean(flag, true)
        crashedLaunch("p1")
        val second = PipelineRunMarker(settings, "p2")
        second.evaluateAtLaunch()
        assertEquals(1, second.uncleanExits)

        second.begin("drain")
        second.end("drain")

        assertEquals(0, second.uncleanExits)
        assertEquals("", settings.getString(PipelineRunMarker.MARKER_KEY, "x"))
    }

    @Test
    fun theMarkerStaysSetUntilEveryOwnerHasStopped() {
        val marker = PipelineRunMarker(settings, "p1")
        marker.begin("index")
        marker.begin("drain")

        marker.end("index")
        assertEquals("p1", settings.getString(PipelineRunMarker.MARKER_KEY, ""), "the drain is still running")

        marker.end("drain")
        assertEquals("", settings.getString(PipelineRunMarker.MARKER_KEY, "x"))
    }

    @Test
    fun aMarkerFromTheSameProcess_isNotACrash() {
        val marker = PipelineRunMarker(settings, "p1")
        marker.begin("index")

        marker.evaluateAtLaunch()

        assertEquals(0, marker.uncleanExits, "a graph switch inside one process re-creates the controller, not the process")
    }

    @Test
    fun reEnabling_resetsTheCounterAndClearsTheTrippedState() {
        settings.putBoolean(flag, true)
        crashedLaunch("p1")
        crashedLaunch("p2")
        val controller = AssetIndexingController(
            settings, AssetIndexService(InMemoryAssetRepository()),
            toggleVisibleProvider = { true }, runMarker = PipelineRunMarker(settings, "p3"),
        )
        assertTrue(controller.breakerTripped.value)

        controller.setEnabled(true)

        assertFalse(controller.breakerTripped.value)
        assertTrue(settings.getBoolean(flag, false))
        assertEquals(0, PipelineRunMarker(settings, "p4").uncleanExits)
        controller.close()
    }

    @Test
    fun aFinishedIndexWalk_isACleanStop() = runBlocking<Unit> {
        settings.putBoolean(flag, true)
        val fs = FakeFileSystem().apply { writeFileBytes("/graph/assets/a.png", png) }
        val controller = AssetIndexingController(
            settings, AssetIndexService(InMemoryAssetRepository()),
            toggleVisibleProvider = { true }, runMarker = PipelineRunMarker(settings, "p1"),
        )

        controller.onGraphReady(EffectiveNotesPath("/graph"), writeActor = null, fileSystem = fs)
        withTimeout(10_000) { controller.progress.first { it is AssetIndexProgress.Finished } }
        withTimeout(10_000) {
            while (settings.getString(PipelineRunMarker.MARKER_KEY, "") != "") kotlinx.coroutines.yield()
        }

        assertEquals("", settings.getString(PipelineRunMarker.MARKER_KEY, "x"))
        controller.close()
    }
}
