package dev.stapler.stelekit.asset.pipeline

import arrow.atomic.AtomicInt
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.logging.LogSink
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Caps are counted in the pipeline, the daily count survives a restart, and every upload logs one INFO line. */
class EgressLedgerTest {
    @Test
    fun egressLedger_should_PauseWithSessionCap_And_SkipOver8Mb_When_21stAsset_And_9MbAsset() = runTest {
        val rig = CloudRig(sessionCap = 20)
        rig.gate.grant(CloudRig.GRAPH_A, 1L, CloudBackfillScope.NewOnly)
        val repo = InMemoryAssetRepository()
        val service = AssetPipelineService(
            PluginRegistry().also { it.register(rig.plugin) }, repo,
            DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()),
        )

        val nineMb = rig.putImage("big", sizeBytes = 9L * 1024 * 1024).also { repo.saveAsset(it) }
        assertEquals(AssetPipelineService.Outcome.Stayed, service.processAsset(nineMb))
        assertEquals(0, rig.http.requests.size, "a 9 MB asset is skipped, not uploaded")
        assertEquals(0, rig.ledger.sessionCount, "a skipped asset costs no cap")

        repeat(20) { n -> repo.saveAsset(rig.putImage("a$n")) }
        repeat(20) { n -> service.processAsset(rig.putImage("a$n")) }
        assertEquals(20, rig.http.requests.size)
        assertEquals(EgressLedger.Reservation.SessionCap, rig.ledger.tryReserve(10L))

        repo.saveAsset(rig.putImage("a21"))
        val capped = service.drainOnce()
        assertEquals(PipelineProgress.Paused(PauseReason.SessionCap), capped)
        assertEquals(20, rig.http.requests.size, "the 21st asset makes no request")
    }

    @Test
    fun egressLedger_should_KeepDailyCountAcrossRestart_And_ResetSessionCount_When_ProcessRestarts() {
        val settings = InspectableSettings()
        val first = EgressLedger(settings, sessionCap = 5, dailyCap = 3, session = AtomicInt(0), today = { "2026-11-01" })
        repeat(2) { assertEquals(EgressLedger.Reservation.Allowed, first.tryReserve(1L)) }

        val restarted = EgressLedger(settings, sessionCap = 5, dailyCap = 3, session = AtomicInt(0), today = { "2026-11-01" })
        assertEquals(0, restarted.sessionCount, "the launch counter starts over with the process")
        assertEquals(2, restarted.dailyCount, "the day counter does not")
        assertEquals(EgressLedger.Reservation.Allowed, restarted.tryReserve(1L))
        assertEquals(EgressLedger.Reservation.DailyCap, restarted.tryReserve(1L))
    }

    @Test
    fun caps_should_ResetLaunchCounterOnProcessStartNotGraphSwitch_And_DailyAtLocalMidnightSurvivingRestart_When_Evaluated() {
        // Two ledgers built with defaults model two graphs in one process: they share the launch counter.
        val graphA = EgressLedger(InspectableSettings(), sessionCap = Int.MAX_VALUE)
        val graphB = EgressLedger(InspectableSettings(), sessionCap = Int.MAX_VALUE)
        val before = graphB.sessionCount
        assertEquals(EgressLedger.Reservation.Allowed, graphA.tryReserve(1L))
        assertEquals(before + 1, graphB.sessionCount, "a graph switch does not reset the launch counter")

        var day = "2026-11-01"
        val settings = InspectableSettings()
        val ledger = EgressLedger(settings, sessionCap = 100, dailyCap = 1, session = AtomicInt(0), today = { day })
        assertEquals(EgressLedger.Reservation.Allowed, ledger.tryReserve(1L))
        assertEquals(EgressLedger.Reservation.DailyCap, ledger.tryReserve(1L))
        day = "2026-11-02"
        assertEquals(0, ledger.dailyCount, "local midnight resets the day counter")
        assertEquals(EgressLedger.Reservation.Allowed, ledger.tryReserve(1L))
    }

    @Test
    fun dailyCount_should_BeSharedByEveryGraph_When_LedgersShareTheAppSettings() {
        val appSettings = InspectableSettings()
        val graphA = EgressLedger(appSettings, dailyCap = 3, session = AtomicInt(0), today = { "2026-11-01" })
        val graphB = EgressLedger(appSettings, dailyCap = 3, session = AtomicInt(0), today = { "2026-11-01" })

        assertEquals(EgressLedger.Reservation.Allowed, graphA.tryReserve(1L))
        assertEquals(1, graphB.dailyCount, "a second graph sees the same day budget")
        assertEquals(EgressLedger.Reservation.Allowed, graphB.tryReserve(1L))
        assertEquals(2, graphA.dailyCount)
    }

    @Test
    fun network_should_BlockUploadShowWaitingTextAndOfferNoOverride_When_MeteredOrOffline() = runTest {
        assertEquals(CloudNetwork.Offline, cloudNetworkOf(isOnline = false, isMetered = false))
        assertEquals(CloudNetwork.Metered, cloudNetworkOf(isOnline = true, isMetered = true))
        assertEquals(CloudNetwork.Online, cloudNetworkOf(isOnline = true, isMetered = false))
        assertEquals(CloudNetwork.Online, cloudNetworkOf(isOnline = true, isMetered = null), "desktop cannot tell and proceeds")

        for (blocked in listOf(CloudNetwork.Metered, CloudNetwork.Offline)) {
            val rig = CloudRig()
            rig.network = blocked
            rig.gate.grant(CloudRig.GRAPH_A, 1L, CloudBackfillScope.NewOnly)
            val result = rig.plugin.processAsset(rig.putImage("a1"))
            assertEquals(AssetPipelineResult.Skipped(PauseReason.Network), result.getOrNull())
            assertEquals(0, rig.http.requests.size)
            assertEquals(0, rig.ledger.sessionCount, "waiting costs no cap")
        }
    }

    @Test
    fun egressLog_should_HaveAssetUuidBytesProviderAndNoContent_When_UploadHappens() = runTest {
        val lines = mutableListOf<String>()
        val sink = object : LogSink {
            override fun write(entry: LogEntry, formatted: String) { lines += formatted }
        }
        val handle = LogManager.addSink(sink)
        try {
            val rig = CloudRig()
            rig.gate.grant(CloudRig.GRAPH_A, 1L, CloudBackfillScope.NewOnly)
            rig.plugin.processAsset(rig.putImage("uuid-77"))
        } finally {
            handle.close()
        }

        val upload = lines.single { it.contains("cloud upload") }
        assertTrue(upload.contains("asset=uuid-77"), upload)
        assertTrue(upload.contains("bytes="), upload)
        assertTrue(upload.contains("provider=anthropic"), upload)
        assertTrue(lines.none { it.contains(TEST_KEY) }, "the key is never logged")
    }

    private suspend fun AssetPipelineService.drainOnce(): PipelineProgress {
        scheduleBackfill()
        return awaitSettled()
    }

    private suspend fun AssetPipelineService.awaitSettled(): PipelineProgress {
        var state = progress.value
        var tries = 0
        while ((state is PipelineProgress.Running || state is PipelineProgress.Idle) && tries < 200) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(10) }
            state = progress.value
            tries++
        }
        assertIs<PipelineProgress>(state)
        return state
    }
}
