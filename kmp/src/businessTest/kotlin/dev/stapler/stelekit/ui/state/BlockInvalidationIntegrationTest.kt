package dev.stapler.stelekit.ui.state

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * AC-1: N-page fan-out integration tests.
 *
 * Verifies that when [BlockStateManager] is observing multiple pages and an invalidation
 * signal arrives, only the targeted pages re-query the repository — not all observed pages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockInvalidationIntegrationTest {

    private val now = Instant.fromEpochMilliseconds(0)

    private open class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    /**
     * Wraps [InMemoryBlockRepository] and counts [getBlocksForPage] calls per [PageUuid].
     * Each call to [getBlocksForPage] increments the count for that page UUID.
     *
     * Uses the same [inner] instance for both delegation and reads, so blocks saved to [inner]
     * are visible via [getBlocksForPage].
     */
    @OptIn(DirectRepositoryWrite::class)
    private class PerPageCountingBlockRepository(
        val inner: InMemoryBlockRepository = InMemoryBlockRepository(),
    ) : BlockRepository by inner {

        private val counts = mutableMapOf<String, Int>()

        fun getCount(pageUuid: PageUuid): Int = counts[pageUuid.value] ?: 0

        fun resetCounts() = counts.clear()

        override fun getBlocksForPage(pageUuid: PageUuid): Flow<Either<DomainError, List<Block>>> {
            counts[pageUuid.value] = (counts[pageUuid.value] ?: 0) + 1
            return inner.getBlocksForPage(pageUuid)
        }
    }

    private fun makePage(uuid: String) = Page(
        uuid = PageUuid(uuid),
        name = "Page $uuid",
        createdAt = now,
        updatedAt = now,
    )

    private fun makeBlock(uuid: String, pageUuid: String, position: String = "a0") = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = "content",
        position = position,
        createdAt = now,
        updatedAt = now,
    )

    /**
     * Set up a BSM observing 5 pages (A–E) with a shared invalidation flow.
     * Returns (bsm, pageUuids, blockRepo, invalidationFlow) tuple.
     *
     * After calling, [PerPageCountingBlockRepository.resetCounts] should be called
     * before asserting counts so that initial-pull counts are excluded.
     */
    private suspend fun setupFivePageBsm(
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): TestSetup {
        val invalidationFlow = MutableSharedFlow<Set<PageUuid>>(replay = 0, extraBufferCapacity = 64)
        val blockRepo = PerPageCountingBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(StubFileSystem(), pageRepo, blockRepo)

        val pageUuids = ('A'..'E').map { PageUuid("page-$it") }

        // Save all 5 pages to the repo
        pageUuids.forEach { pageRepo.savePage(makePage(it.value)) }

        // Save one block per page so getBlocksForPage returns non-empty results
        pageUuids.forEachIndexed { idx, pageUuid ->
            blockRepo.inner.saveBlock(makeBlock("block-$idx", pageUuid.value, position = "a0"))
        }

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val bsm = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = scope,
            invalidationSource = invalidationFlow,
        )

        // Observe all 5 pages
        pageUuids.forEach { bsm.observePage(it) }

        return TestSetup(bsm, pageUuids, blockRepo, invalidationFlow, scope)
    }

    private data class TestSetup(
        val bsm: BlockStateManager,
        val pageUuids: List<PageUuid>,
        val blockRepo: PerPageCountingBlockRepository,
        val invalidationFlow: MutableSharedFlow<Set<PageUuid>>,
        val scope: CoroutineScope,
    )

    @Test
    fun fanOut_singlePageWrite_triggersOneReadForWrittenPageZeroForOthers() = runTest {
        val setup = setupFivePageBsm(testScheduler)
        advanceUntilIdle()

        // Reset counts after initial pulls
        setup.blockRepo.resetCounts()

        // Emit invalidation for only page A
        setup.invalidationFlow.emit(setOf(setup.pageUuids[0]))
        advanceUntilIdle()

        // Page A should get exactly 1 re-query
        assertEquals(1, setup.blockRepo.getCount(setup.pageUuids[0]),
            "Page A must be re-queried exactly once on targeted invalidation")

        // Pages B–E must not be re-queried
        setup.pageUuids.drop(1).forEach { pageUuid ->
            assertEquals(0, setup.blockRepo.getCount(pageUuid),
                "$pageUuid must NOT be re-queried when only page A was invalidated")
        }

        setup.bsm.close()
    }

    @Test
    fun fanOut_wildcardInvalidation_triggersOneReadPerObservedPage() = runTest {
        val setup = setupFivePageBsm(testScheduler)
        advanceUntilIdle()

        // Reset counts after initial pulls
        setup.blockRepo.resetCounts()

        // Emit wildcard invalidation
        setup.invalidationFlow.emit(setOf(DatabaseWriteActor.WILDCARD_PAGE_UUID))
        advanceUntilIdle()

        // All 5 pages must be re-queried exactly once
        setup.pageUuids.forEach { pageUuid ->
            assertEquals(1, setup.blockRepo.getCount(pageUuid),
                "$pageUuid must be re-queried exactly once on wildcard invalidation")
        }

        setup.bsm.close()
    }

    @Test
    fun fanOut_multiPageSetInvalidation_triggersReadsOnlyForNamedPages() = runTest {
        val setup = setupFivePageBsm(testScheduler)
        advanceUntilIdle()

        // Reset counts after initial pulls
        setup.blockRepo.resetCounts()

        // Emit invalidation for pages A and B only
        setup.invalidationFlow.emit(setOf(setup.pageUuids[0], setup.pageUuids[1]))
        advanceUntilIdle()

        // Pages A and B get 1 re-query each
        assertEquals(1, setup.blockRepo.getCount(setup.pageUuids[0]),
            "Page A must be re-queried exactly once")
        assertEquals(1, setup.blockRepo.getCount(setup.pageUuids[1]),
            "Page B must be re-queried exactly once")

        // Pages C, D, E must not be re-queried
        setup.pageUuids.drop(2).forEach { pageUuid ->
            assertEquals(0, setup.blockRepo.getCount(pageUuid),
                "$pageUuid must NOT be re-queried when not in the invalidation set")
        }

        setup.bsm.close()
    }
}
