package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/**
 * TC-08: Regression guard that asserts the debounce contract for file writes during
 * block insert operations.
 *
 * For each insert operation tested:
 *   1. After the insert (DB write committed via [Job.join]), [CountingFileSystem.writeFileCount] == 0.
 *      File writes must NOT fire synchronously — they are deferred by the debounce chain.
 *   2. After calling [BlockStateManager.flush] (which bypasses timers and forces all pending
 *      disk writes to execute immediately), exactly 1 [CountingFileSystem.writeFileCount] fires.
 *
 * Design note: This test does NOT use virtual-time coroutine scheduling. Instead it relies on the
 * natural sequencing of the debounce:
 *   - Step 1 check is valid because the DebounceManager uses delay() (not immediate dispatch),
 *     so after .join() the timer is still pending.
 *   - Step 2 check uses flush() which drains the BSM DebounceManager (executes the action
 *     immediately without waiting for the delay) and then flushes the GraphWriter's pending
 *     queueSave jobs synchronously. No virtual time needed.
 *
 * This design avoids the complexity of virtual-time scheduling with multiple CoroutineScopes
 * while still validating the core contract: inserts are debounced, not written immediately.
 *
 * Limitation: uses [InMemoryBlockRepository] (no real SQLite). DB latency is measured separately
 * in [BlockInsertBenchmarkTest].
 */
@OptIn(DirectRepositoryWrite::class)
class FileSystemCallCountTest {

    private val now = Clock.System.now()
    private val pageUuid = "fs-count-test-page"
    private val graphPath = "/tmp/fs-count-test-graph"

    private fun makePage(filePath: String? = "$graphPath/pages/fs-count-test-page.md") = Page(
        uuid = pageUuid,
        name = "FS Count Test Page",
        filePath = filePath,
        createdAt = now,
        updatedAt = now,
        isJournal = false,
    )

    private data class Harness(
        val bsm: BlockStateManager,
        val countingFs: CountingFileSystem,
        val scope: CoroutineScope,
    )

    /**
     * Creates a fully-wired test harness.
     *
     * - [CountingFileSystem] wrapping [FakeFileSystem] — all file operations no-op, but counted
     * - [InMemoryBlockRepository] / [InMemoryPageRepository] — instant saves
     * - [GraphWriter] with a real [CoroutineScope] — debounce uses real delay()
     * - [BlockStateManager] with the same [CoroutineScope]
     *
     * The key invariant: after [BlockStateManager.addBlockToPage].join(), the DebounceManager
     * timer is PENDING (has not fired). After [BlockStateManager.flush], all pending debounce
     * actions are executed immediately without waiting for the timer.
     */
    private fun createHarness(): Harness {
        val countingFs = CountingFileSystem(FakeFileSystem())
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()

        val page = makePage()
        runBlocking { pageRepo.savePage(page) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val graphWriter = GraphWriter(
            fileSystem = countingFs,
            graphPath = graphPath,
        )
        graphWriter.startAutoSave(scope)

        val bsm = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = GraphLoader(
                fileSystem = countingFs,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
            ),
            scope = scope,
            graphWriter = graphWriter,
            pageRepository = pageRepo,
            graphPathProvider = { graphPath },
        )

        return Harness(bsm, countingFs, scope)
    }

    /**
     * TC-08a: [BlockStateManager.addBlockToPage] — the primary "Enter key" insert path.
     *
     * Asserts:
     * - 0 writeFile calls immediately after the DB write commits (.join() returns)
     * - 1 writeFile call after flush() drains all pending debounce actions
     */
    @Test
    fun addBlockToPage_zeroWriteFileCallsDuringInsert_oneAfterFlush() = runBlocking {
        val harness = createHarness()
        harness.countingFs.reset()

        // Insert a block. DB write completes (InMemoryBlockRepository instant).
        // The debounce timer is STARTED but has NOT fired — it waits 300ms then 500ms.
        harness.bsm.addBlockToPage(pageUuid).join()

        // Assert: no file write has occurred yet (debounce is still pending)
        harness.countingFs.assertInsertBudget("addBlockToPage")

        // flush() drains the BSM DebounceManager (calls action immediately, no delay)
        // and then flush()es GraphWriter (calls savePageInternal immediately, no delay).
        harness.bsm.flush()

        // Assert: exactly 1 file write occurred
        harness.countingFs.assertDebounceFired("addBlockToPage")

        harness.scope.cancel()
    }

    /**
     * TC-08b: Multiple rapid inserts coalesce into a single file write.
     *
     * N rapid inserts within the debounce window all replace the same pending job,
     * so flush() triggers exactly 1 writeFile (not N).
     */
    @Test
    fun multipleRapidInserts_coalesceToSingleWriteFile() = runBlocking {
        val harness = createHarness()
        harness.countingFs.reset()

        // 5 rapid inserts — each one resets the debounce timer for the same page
        repeat(5) { harness.bsm.addBlockToPage(pageUuid).join() }

        // No file writes yet
        harness.countingFs.assertInsertBudget("5 rapid inserts — before flush")

        // Flush forces the write — should coalesce to exactly 1 writeFile
        harness.bsm.flush()

        assertEquals(
            1, harness.countingFs.writeFileCount.get(),
            "[5 rapid inserts] expected 1 coalesced writeFile after flush, " +
                "got ${harness.countingFs.writeFileCount.get()}"
        )

        harness.scope.cancel()
    }

    /**
     * TC-08c: No file writes occur at all when [GraphWriter] is not injected.
     *
     * Verifies the null-guard in [BlockStateManager.queueDiskSave].
     */
    @Test
    fun withoutGraphWriter_noFileWritesOccur() = runBlocking {
        val countingFs = CountingFileSystem(FakeFileSystem())
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val page = makePage()
        pageRepo.savePage(page)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // No graphWriter — queueDiskSave is a no-op
        val bsm = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = GraphLoader(
                fileSystem = countingFs,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
            ),
            scope = scope,
        )

        countingFs.reset()
        bsm.addBlockToPage(pageUuid).join()
        bsm.flush()

        assertEquals(0, countingFs.writeFileCount.get(), "No writeFile should fire without graphWriter")
        assertEquals(0, countingFs.readFileCount.get(), "No readFile should fire without graphWriter")

        scope.cancel()
    }
}
