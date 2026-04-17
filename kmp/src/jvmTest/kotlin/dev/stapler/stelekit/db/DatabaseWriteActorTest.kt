package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Unit tests for [DatabaseWriteActor].
 *
 * Coverage:
 * - All three request types (SavePage, SaveBlocks, DeleteBlocksForPage) happy paths
 * - Failure propagation: repository errors surface as Result.failure to the caller
 * - Batch fallback: when a combined SaveBlocks transaction fails, the actor retries
 *   each original request individually so partial successes are preserved
 * - Ordering: delete-then-save sees a clean state
 * - Concurrent callers all receive their deferred result (no hangs or missed signals)
 */
class DatabaseWriteActorTest {

    private val now = Clock.System.now()

    private fun page(id: String, name: String = "Page $id") = Page(
        uuid = id, name = name, createdAt = now, updatedAt = now, isJournal = false
    )

    private fun block(id: String, pageId: String = "page-1") = Block(
        uuid = id, pageUuid = pageId, content = "content $id",
        position = 0, createdAt = now, updatedAt = now
    )

    // ──────────────────────────── happy paths ────────────────────────────────

    @Test
    fun `savePage routes to page repository and returns success`() = runBlocking {
        val pageRepo = FakePageRepository()
        val actor = DatabaseWriteActor(FakeBlockRepository(), pageRepo, this)

        val result = actor.savePage(page("p-1", "My Page"))

        assertTrue(result.isSuccess)
        val stored = pageRepo.getPageByUuid("p-1").first().getOrNull()
        assertNotNull(stored, "page should be stored")
        assertEquals("My Page", stored.name)
        actor.close()
    }

    @Test
    fun `saveBlocks stores all supplied blocks`() = runBlocking {
        val blockRepo = FakeBlockRepository()
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository(), this)

        val result = actor.saveBlocks(listOf(block("b1"), block("b2"), block("b3")))

        assertTrue(result.isSuccess)
        listOf("b1", "b2", "b3").forEach { id ->
            assertNotNull(blockRepo.getBlockByUuid(id).first().getOrNull(), "$id should be stored")
        }
        actor.close()
    }

    @Test
    fun `deleteBlocksForPage removes all blocks for that page`() = runBlocking {
        val blockRepo = FakeBlockRepository(mapOf("page-1" to listOf(block("b1"), block("b2"))))
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository(), this)

        val result = actor.deleteBlocksForPage("page-1")

        assertTrue(result.isSuccess)
        val remaining = blockRepo.getBlocksForPage("page-1").first().getOrNull()
        assertTrue(remaining.isNullOrEmpty(), "all blocks should be deleted")
        actor.close()
    }

    // ──────────────── failure propagation ─────────────────────────────────────

    @Test
    fun `savePage failure is returned to caller`() = runBlocking {
        val error = RuntimeException("db full")
        val pageRepo = object : FakePageRepository() {
            override suspend fun savePage(page: Page): Result<Unit> = Result.failure(error)
        }
        val actor = DatabaseWriteActor(FakeBlockRepository(), pageRepo, this)

        val result = actor.savePage(page("p-1"))

        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
        actor.close()
    }

    @Test
    fun `saveBlocks failure is returned to caller`() = runBlocking {
        val error = RuntimeException("constraint violation")
        val blockRepo = object : FakeBlockRepository() {
            override suspend fun saveBlocks(blocks: List<Block>): Result<Unit> =
                Result.failure(error)
        }
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository(), this)

        val result = actor.saveBlocks(listOf(block("b1")))

        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
        actor.close()
    }

    @Test
    fun `deleteBlocksForPage failure is returned to caller`() = runBlocking {
        val error = RuntimeException("locked")
        val blockRepo = object : FakeBlockRepository() {
            override suspend fun deleteBlocksForPage(pageUuid: String): Result<Unit> =
                Result.failure(error)
        }
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository(), this)

        val result = actor.deleteBlocksForPage("page-1")

        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
        actor.close()
    }

    // ────────────── batch fallback: combined then individual retry ─────────────
    //
    // The actor coalesces consecutive SaveBlocks from the channel into one call.
    // If that combined call fails, it retries each original request individually.
    // We use a gate Mutex to hold the actor briefly so the second request can
    // land in the channel before the first is processed, enabling coalescing.

    @Test
    fun `when combined batch fails both requests are retried individually`() = runBlocking {
        val gate = kotlinx.coroutines.sync.Mutex(locked = true)
        var callCount = 0
        val blockRepo = object : FakeBlockRepository() {
            override suspend fun saveBlocks(blocks: List<Block>): Result<Unit> {
                // On first call (combined batch), wait for gate then fail.
                // Subsequent individual retries succeed.
                if (callCount == 0) { gate.lock(); gate.unlock() }
                callCount++
                return if (callCount == 1) Result.failure(RuntimeException("batch failed"))
                else super.saveBlocks(blocks)
            }
        }
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository(), this)

        val j1 = launch { actor.saveBlocks(listOf(block("b1", "page-1"))) }
        val j2 = launch { actor.saveBlocks(listOf(block("b2", "page-2"))) }
        gate.unlock() // release after both requests are queued in the channel
        joinAll(j1, j2)

        // Both blocks stored via individual retries
        assertNotNull(blockRepo.getBlockByUuid("b1").first().getOrNull(), "b1 stored after retry")
        assertNotNull(blockRepo.getBlockByUuid("b2").first().getOrNull(), "b2 stored after retry")
        actor.close()
    }

    @Test
    fun `partial success preserved when individual retries have different outcomes`() = runBlocking {
        val gate = kotlinx.coroutines.sync.Mutex(locked = true)
        var callCount = 0
        val blockRepo = object : FakeBlockRepository() {
            override suspend fun saveBlocks(blocks: List<Block>): Result<Unit> {
                if (callCount == 0) { gate.lock(); gate.unlock() }
                callCount++
                return when {
                    callCount == 1 -> Result.failure(RuntimeException("batch failed"))
                    blocks.any { it.pageUuid == "page-fail" } ->
                        Result.failure(RuntimeException("individual fail for page-fail"))
                    else -> super.saveBlocks(blocks)
                }
            }
        }
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository(), this)

        var r1: Result<Unit>? = null
        var r2: Result<Unit>? = null
        val j1 = launch { r1 = actor.saveBlocks(listOf(block("b1", "page-ok"))) }
        val j2 = launch { r2 = actor.saveBlocks(listOf(block("b2", "page-fail"))) }
        gate.unlock()
        joinAll(j1, j2)

        assertNotNull(r1); assertNotNull(r2)
        assertTrue(r1!!.isSuccess, "page-ok should succeed on individual retry")
        assertTrue(r2!!.isFailure, "page-fail should still fail on individual retry")
        actor.close()
    }

    // ──────────────── concurrent callers all receive results ──────────────────

    @Test
    fun `twenty concurrent saveBlocks callers all receive results`() = runBlocking {
        val blockRepo = FakeBlockRepository()
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository(), this)
        val n = 20

        val results = (1..n).map { i ->
            async { actor.saveBlocks(listOf(block("b$i", "page-$i"))) }
        }.map { it.await() }

        assertEquals(n, results.size)
        assertTrue(results.all { it.isSuccess }, "all callers should receive success")
        (1..n).forEach { i ->
            assertNotNull(blockRepo.getBlockByUuid("b$i").first().getOrNull())
        }
        actor.close()
    }

    @Test
    fun `mixed request types complete without deadlock`() = runBlocking {
        val blockRepo = FakeBlockRepository()
        val pageRepo = FakePageRepository()
        val actor = DatabaseWriteActor(blockRepo, pageRepo, this)

        val results = listOf(
            async { actor.savePage(page("p-1")) },
            async { actor.saveBlocks(listOf(block("b1", "p-1"))) },
            async { actor.deleteBlocksForPage("p-1") },
            async { actor.savePage(page("p-2")) },
            async { actor.saveBlocks(listOf(block("b2", "p-2"))) },
        ).map { it.await() }

        assertTrue(results.all { it.isSuccess })
        actor.close()
    }

    // ──────────────── ordering guarantee ─────────────────────────────────────

    @Test
    fun `delete before save produces clean final state`() = runBlocking {
        val blockRepo = FakeBlockRepository(
            mapOf("page-1" to listOf(block("old-1"), block("old-2")))
        )
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository(), this)

        actor.deleteBlocksForPage("page-1")
        actor.saveBlocks(listOf(block("new-1"), block("new-2")))

        val stored = blockRepo.getBlocksForPage("page-1").first().getOrNull() ?: emptyList()
        assertEquals(2, stored.size, "exactly 2 new blocks")
        assertNull(stored.find { it.uuid == "old-1" }, "old block removed")
        assertNotNull(stored.find { it.uuid == "new-1" }, "new block present")
        actor.close()
    }
}
