@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset.pipeline

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 5.1: progress state, pause/resume/cancel, one-in-flight, Default dispatcher, circuit breaker, lazy plugin source. */
class AssetPipelineProgressTest {
    private val repo = InMemoryAssetRepository()
    private val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())

    private fun asset(i: Int) = AssetEntry(
        uuid = AssetUuid("a$i"), filePath = "/g/assets/a$i.png", relativePath = "../assets/a$i.png",
        mediaType = AssetMediaType.IMAGE, subfolder = "assets", tags = emptyList(), autoLabels = emptyList(),
        ocrText = null, cloudDescription = null, pageUuids = emptyList(), sizeBytes = 1L,
        importedAtMs = i.toLong(), mlProcessed = false, mlAttemptedAt = null, mlFailed = false,
        contentHash = null, isOrphan = false, mlTagsSource = "NONE",
    )

    private suspend fun seed(n: Int) = repeat(n) { repo.saveAsset(asset(it)) }

    private fun service(plugin: AssetPipelinePlugin, marker: PipelineRunMarker? = null) =
        AssetPipelineService(PluginRegistry().apply { register(plugin) }, repo, actor, runMarker = marker)

    private class ConcurrencyProbe : AssetPipelinePlugin {
        override val id = "probe"
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val threadNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        override fun canProcess(asset: AssetEntry) = true
        override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
            threadNames += Thread.currentThread().name
            maxActive.updateAndGet { maxOf(it, active.incrementAndGet()) }
            yield()
            active.decrementAndGet()
            return AssetPipelineResult.Labels(emptyList()).right()
        }
    }

    @Test
    fun drain_runsOneAssetAtATime_onTheDefaultDispatcher_andReportsTerminalProgress() = runBlocking<Unit> {
        seed(30)
        val probe = ConcurrencyProbe()
        val service = service(probe)

        service.scheduleBackfill()
        val end = withTimeout(30_000) { service.progress.first { it is PipelineProgress.Finished } }

        assertEquals(PipelineProgress.Finished(done = 30, failed = 0), end)
        assertEquals(1, probe.maxActive.get(), "at most one processAsset is active")
        assertTrue(probe.threadNames.all { it.startsWith("DefaultDispatcher-worker") }, "plugin work runs off the DB dispatcher: ${probe.threadNames}")
        service.shutdown()
    }

    @Test
    fun runningProgress_reportsDoneTotalAndFailed() = runBlocking<Unit> {
        seed(4)
        val gate = CompletableDeferred<Unit>()
        val plugin = object : AssetPipelinePlugin {
            override val id = "gated"
            override fun canProcess(asset: AssetEntry) = true
            override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
                if (asset.uuid.value == "a1") return DomainError.DatabaseError.WriteFailed("x").left()
                if (asset.uuid.value == "a2") gate.await()
                return AssetPipelineResult.Labels(emptyList()).right()
            }
        }
        val service = service(plugin)

        service.scheduleBackfill()
        val running = withTimeout(30_000) {
            service.progress.first { it is PipelineProgress.Running && it.done == 2 && it.failed == 1 }
        }

        assertEquals(PipelineProgress.Running(done = 2, total = 4, failed = 1), running)
        gate.complete(Unit)
        withTimeout(30_000) { service.progress.first { it is PipelineProgress.Finished } }
        service.shutdown()
    }

    @Test
    fun memoryPause_stopsTheDrain_thenResumeFinishesIt_andCancelReturnsToIdle() = runBlocking<Unit> {
        seed(3)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val plugin = object : AssetPipelinePlugin {
            override val id = "blocking"
            override fun canProcess(asset: AssetEntry) = true
            override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
                started.complete(Unit)
                release.await()
                return AssetPipelineResult.Labels(emptyList()).right()
            }
        }
        val service = service(plugin)
        service.scheduleBackfill()
        withTimeout(30_000) { started.await() }

        service.pause(PauseReason.Memory)

        assertEquals(PipelineProgress.Paused(PauseReason.Memory), service.progress.value)
        assertEquals(3L, repo.countUnprocessedAssets().getOrNull(), "the in-flight plugin was cancelled before it wrote")

        release.complete(Unit)
        service.resume()
        withTimeout(30_000) { service.progress.first { it is PipelineProgress.Finished } }
        assertEquals(0L, repo.countUnprocessedAssets().getOrNull())

        service.cancel()
        assertEquals(PipelineProgress.Idle, service.progress.value)
        service.shutdown()
    }

    @Test
    fun resume_whenNotPaused_doesNothing() = runBlocking<Unit> {
        seed(1)
        val service = service(ConcurrencyProbe())
        service.resume()
        assertEquals(PipelineProgress.Idle, service.progress.value)
        service.shutdown()
    }

    @Test
    fun aPluginThatThrowsFiveTimesInARow_isSkippedForTheRestOfTheRun() = runBlocking<Unit> {
        seed(12)
        val calls = AtomicInteger()
        val failing = object : AssetPipelinePlugin {
            override val id = "always-throws"
            override fun canProcess(asset: AssetEntry) = true
            override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
                calls.incrementAndGet()
                error("systemic fault")
            }
        }
        val service = service(failing)

        service.scheduleBackfill()
        val end = withTimeout(30_000) { service.progress.first { it is PipelineProgress.Finished } }

        assertEquals(5, calls.get(), "the breaker opens after 5 consecutive throws")
        assertEquals(PipelineProgress.Finished(done = 5, failed = 5), end)
        assertEquals(7L, repo.countUnprocessedAssets().getOrNull(), "the rest stay unprocessed, not marked failed")
        service.shutdown()
    }

    @Test
    fun pluginSource_isNotCalledUntilADrainStarts() = runBlocking<Unit> {
        var derived = 0
        val service = AssetPipelineService(PluginRegistry(), repo, actor, pluginSource = { derived++; emptyList() })
        assertEquals(0, derived, "constructing the service builds no plugin")

        service.scheduleBackfill()

        assertEquals(1, derived)
        assertEquals(PipelineProgress.Idle, service.progress.value, "no plugin means no drain")
        assertFalse(service.progress.value is PipelineProgress.Running)
        service.shutdown()
    }
}
