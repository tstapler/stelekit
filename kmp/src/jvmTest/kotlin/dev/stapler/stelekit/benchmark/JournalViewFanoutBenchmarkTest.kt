package dev.stapler.stelekit.benchmark

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/**
 * AC-6 enforcement test: journal-view fan-out is eliminated after Phase 1 + Phase 2.
 *
 * Guards against regressions where bulk block import during background indexing triggers
 * [BlockRepository.getBlocksForPage] re-queries for observed-but-not-imported pages.
 *
 * Architecture invariants tested:
 * - During [DatabaseWriteActor.saveBlocks] (bulk import), [blockInvalidations] emits only
 *   the imported page UUIDs (F-J). Observed pages A-E receive no invalidation → zero re-queries.
 * - During [DatabaseWriteActor.updateBlockContentOnly] (hot-path write, Phase 3), the actor
 *   emits [BlockUpdateEvent.BlockContentPatched] via [blocksPushed]. [BlockStateManager] applies
 *   the patch directly to [_blocks] with zero [getBlocksForPage] calls from either side.
 *
 * This test runs in CI via `./gradlew :kmp:jvmTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class JournalViewFanoutBenchmarkTest {

    // ── Stub FileSystem ───────────────────────────────────────────────────────

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

    // ── Counting repository decorator ─────────────────────────────────────────

    /**
     * Wraps [SqlDelightBlockRepository] and counts [getBlocksForPage] calls per [PageUuid].
     *
     * Created fresh per test to avoid cross-test contamination. Uses a [SqlDelightBlockRepository]
     * as inner so that actual DB data is returned — required for structural ops (delete, merge)
     * where the actor still calls [emitPushPayload] and reads from DB.
     *
     * Cannot import from businessTest (separate source set) — parallel implementation here is intentional.
     */
    private class CountingBlockRepository(
        val inner: SqlDelightBlockRepository,
    ) : BlockRepository by inner {

        private val counts = mutableMapOf<String, Int>()

        fun getCount(pageUuid: PageUuid): Int = counts[pageUuid.value] ?: 0

        fun resetCounts() = counts.clear()

        override fun getBlocksForPage(pageUuid: PageUuid): Flow<Either<DomainError, List<Block>>> {
            counts[pageUuid.value] = (counts[pageUuid.value] ?: 0) + 1
            return inner.getBlocksForPage(pageUuid)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun now() = Clock.System.now()

    private fun makePage(uuid: String) = Page(
        uuid = PageUuid(uuid),
        name = "Page $uuid",
        createdAt = now(),
        updatedAt = now(),
    )

    private fun makeBlock(uuid: String, pageUuid: String, content: String = "content $uuid") = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = content,
        position = "a0",
        createdAt = now(),
        updatedAt = now(),
    )

    /**
     * Builds a real in-memory SQLite repository set.
     * Returns Triple(countingRepo, pageRepo, actor).
     */
    private fun buildRealRepos(scope: CoroutineScope): Triple<CountingBlockRepository, SqlDelightPageRepository, DatabaseWriteActor> {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val innerBlock = SqlDelightBlockRepository(database, driver)
        val counting = CountingBlockRepository(innerBlock)
        val pageRepo = SqlDelightPageRepository(database)
        val actor = DatabaseWriteActor(counting, pageRepo, scope = scope)
        return Triple(counting, pageRepo, actor)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * AC-6 core assertion: during background import of pages F-J, observed pages A-E receive
     * zero [BlockRepository.getBlocksForPage] calls.
     *
     * Setup: 10 pages (A-E observed by BSM, F-J import targets). After initial pulls:
     *  1. Import 200 blocks to pages F-J via [DatabaseWriteActor.saveBlocks] (bulk import path).
     *  2. [blockInvalidations] emits only the F-J page UUIDs.
     *  3. BSM for A-E sees no matching signal → 0 re-queries for A-E.
     */
    @Test
    fun backgroundIndexing_nonImportedPages_receiveZeroGetBlocksCalls() = runTest {
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val (countingRepo, pageRepo, actor) = buildRealRepos(testScope)

        try {
            val observedUuids = ('A'..'E').map { PageUuid("page-$it") }
            val importUuids = ('F'..'J').map { PageUuid("page-$it") }
            val allUuids = observedUuids + importUuids

            // Create all 10 pages in DB
            allUuids.forEach { pageRepo.savePage(makePage(it.value)) }

            // Pre-seed one block per observed page so initial pull is non-empty
            observedUuids.forEachIndexed { i, uuid ->
                countingRepo.inner.saveBlock(makeBlock("seed-block-$i", uuid.value))
            }

            val graphLoader = GraphLoader(StubFileSystem(), pageRepo, countingRepo)
            val bsm = BlockStateManager(
                blockRepository = countingRepo,
                graphLoader = graphLoader,
                scope = testScope,
                writeActor = actor,
                invalidationSource = actor.blockInvalidations,
                pushSource = actor.blocksPushed,
            )

            // Observe all 5 pages A-E
            observedUuids.forEach { bsm.observePage(it) }
            advanceUntilIdle()

            // Reset counts after initial pulls — only post-reset calls are under test
            countingRepo.resetCounts()

            // Import 200 blocks to pages F-J (40 per page) via bulk SaveBlocks path
            val importBlocks = importUuids.flatMapIndexed { pageIdx, pageUuid ->
                (0 until 40).map { blockIdx ->
                    makeBlock("import-$pageIdx-$blockIdx", pageUuid.value, "imported content $blockIdx")
                }
            }
            actor.saveBlocks(importBlocks, DatabaseWriteActor.Priority.LOW)
            advanceUntilIdle()

            // Core AC-6 assertion: observed pages A-E must not be re-queried
            observedUuids.forEach { pageUuid ->
                assertEquals(
                    0,
                    countingRepo.getCount(pageUuid),
                    "Page ${pageUuid.value} must NOT be re-queried during background import of F-J. " +
                        "blockInvalidations only emits UUIDs for imported pages (F-J), so BSM for A-E " +
                        "never receives a matching signal and must not call getBlocksForPage.",
                )
            }

            bsm.close()
        } finally {
            testScope.cancel()
            actor.close()
        }
    }

    /**
     * AC-5 structural assertion: an in-app block edit via [DatabaseWriteActor.updateBlockContentOnly]
     * triggers zero [getBlocksForPage] calls (Phase 3 patch path).
     *
     * Phase 3 patch path:
     *  - Actor writes the block content, then emits [BlockUpdateEvent.BlockContentPatched] — no DB read.
     *  - BSM receives the patch on [pushSource], applies it directly to [_blocks] with dirty-set semantics.
     *  - [blockInvalidations] is NOT emitted for typed hot-path arms (Option A suppression).
     *  - BSM's [invalidationSource] collector never fires → 0 BSM-side re-queries.
     *
     * Expected total count = 0. If count > 0, either the actor is re-reading from DB (regression
     * to Phase 2) or BSM is falling through to the invalidation path (regression to Phase 1).
     */
    @Test
    fun hotPathEdit_updateBlockContentOnly_triggersZeroGetBlocksCalls() = runTest {
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val (countingRepo, pageRepo, actor) = buildRealRepos(testScope)

        try {
            val pageA = PageUuid("page-A-hotpath")
            pageRepo.savePage(makePage(pageA.value))

            // Insert a block on page A
            val block = makeBlock("block-a-1", pageA.value, "original content")
            countingRepo.inner.saveBlock(block)

            val graphLoader = GraphLoader(StubFileSystem(), pageRepo, countingRepo)
            val bsm = BlockStateManager(
                blockRepository = countingRepo,
                graphLoader = graphLoader,
                scope = testScope,
                writeActor = actor,
                invalidationSource = actor.blockInvalidations,
                pushSource = actor.blocksPushed,
            )

            bsm.observePage(pageA)
            advanceUntilIdle()

            // Wait for blocks to be populated from initial pull
            bsm.blocks.first { it.containsKey(pageA.value) }

            // Reset counts — only post-reset calls are under test
            countingRepo.resetCounts()

            // Hot-path write: typed WriteBlockContent arm emits BlockContentPatched (not blockInvalidations).
            // Neither the actor nor BSM should call getBlocksForPage.
            actor.updateBlockContentOnly(BlockUuid(block.uuid.value), "updated content", pageA)
            advanceUntilIdle()

            val actualCount = countingRepo.getCount(pageA)
            assertEquals(
                0,
                actualCount,
                "After updateBlockContentOnly, getBlocksForPage must not be called. " +
                    "Actor emits BlockContentPatched (Phase 3 patch); BSM applies it in-place. " +
                    "Count=$actualCount indicates the actor or BSM is making an extra DB read.",
            )

            // Verify the in-memory content was actually updated. Use first{} rather than .value
            // so the assertion waits for the push collector coroutine to apply the patch — the
            // test dispatcher may schedule it after advanceUntilIdle() if it resumed from real IO.
            val updatedBlockMap = bsm.blocks.first { blocks ->
                blocks[pageA.value]?.any { it.uuid == block.uuid && it.content == "updated content" } == true
            }
            val updatedBlock = updatedBlockMap[pageA.value]?.find { it.uuid == block.uuid }
            assertEquals(
                "updated content",
                updatedBlock?.content,
                "BSM _blocks must reflect the patched content without a DB round-trip.",
            )

            bsm.close()
        } finally {
            testScope.cancel()
            actor.close()
        }
    }
}
