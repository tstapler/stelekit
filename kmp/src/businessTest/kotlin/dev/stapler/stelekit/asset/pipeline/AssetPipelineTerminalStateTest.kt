@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset.pipeline

import app.cash.sqldelight.db.SqlDriver
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 5.1: the drain reaches a terminal state for every asset on the real SQLDelight backend. */
class AssetPipelineTerminalStateTest {
    private val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())

    private class MaskedPlugin(private val failing: Set<String>) : AssetPipelinePlugin {
        override val id = "masked"
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        override fun canProcess(asset: AssetEntry) = true
        override suspend fun processAsset(asset: AssetEntry): Either<DomainError, AssetPipelineResult> {
            calls.getOrPut(asset.uuid.value) { AtomicInteger() }.incrementAndGet()
            return if (asset.uuid.value in failing) {
                DomainError.DatabaseError.WriteFailed("injected").left()
            } else {
                AssetPipelineResult.Labels(listOf("x")).right()
            }
        }
    }

    private fun asset(i: Int) = AssetEntry(
        uuid = AssetUuid("a$i"), filePath = "/g/assets/a$i.png", relativePath = "../assets/a$i.png",
        mediaType = AssetMediaType.IMAGE, subfolder = "assets", tags = emptyList(), autoLabels = emptyList(),
        ocrText = null, cloudDescription = null, pageUuids = emptyList(), sizeBytes = 1L,
        importedAtMs = i.toLong(), mlProcessed = false, mlAttemptedAt = null, mlFailed = false,
        contentHash = null, isOrphan = false, mlTagsSource = "NONE",
    )

    /** Runs a full drain over [count] assets whose ids in [failing] fail, then hands the result to [verify]. */
    private suspend fun drain(
        count: Int,
        failing: Set<String>,
        verify: suspend (MaskedPlugin, PipelineProgress, SqlDelightAssetRepository) -> Unit,
    ) {
        val driver: SqlDriver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        try {
            val repo = SqlDelightAssetRepository(SteleDatabase(driver))
            repeat(count) { repo.saveAsset(asset(it)) }
            val plugin = MaskedPlugin(failing)
            val service = AssetPipelineService(PluginRegistry().apply { register(plugin) }, repo, actor)
            service.scheduleBackfill()
            val end = withTimeout(30_000) { service.progress.first { it is PipelineProgress.Finished } }
            service.shutdown()
            verify(plugin, end, repo)
        } finally {
            driver.close()
        }
    }

    @Test
    fun twentyFiveAssets_sevenFailing_allEndProcessedOrFailed_noneTwice() = runBlocking<Unit> {
        val failing = setOf(0, 3, 6, 9, 12, 15, 18).map { "a$it" }.toSet()
        drain(25, failing) { plugin, end, repo ->
            assertEquals(0L, repo.countUnprocessedAssets().getOrNull(), "every asset reached a terminal state")
            assertEquals(PipelineProgress.Finished(done = 25, failed = 7), end)
            assertEquals(25, plugin.calls.size)
            assertTrue(plugin.calls.values.all { it.get() == 1 }, "none processed twice")
        }
    }

    @Test
    fun anyCountAndFailureMask_reachesTerminalState_withoutRepeats() = runBlocking<Unit> {
        checkAll(PropTestConfig(iterations = 25), Arb.int(0..60), Arb.long()) { n, seed ->
            val rnd = Random(seed)
            val failing = (0 until n).filter { rnd.nextBoolean() }.map { "a$it" }.toSet()

            drain(n, failing) { plugin, end, repo ->
                assertEquals(0L, repo.countUnprocessedAssets().getOrNull(), "n=$n failing=${failing.size}")
                assertEquals(PipelineProgress.Finished(done = n, failed = failing.size), end)
                assertTrue(plugin.calls.values.all { it.get() == 1 })
            }
        }
    }
}
