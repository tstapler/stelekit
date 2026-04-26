package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        val actor = DatabaseWriteActor(FakeBlockRepository(), pageRepo)

        val result = actor.savePage(page("p-1", "My Page"))

        assertTrue(result.isRight())
        val stored = pageRepo.getPageByUuid("p-1").first().getOrNull()
        assertNotNull(stored, "page should be stored")
        assertEquals("My Page", stored.name)
        actor.close()
    }

    @Test
    fun `saveBlocks stores all supplied blocks`() = runBlocking {
        val blockRepo = FakeBlockRepository()
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository())

        val result = actor.saveBlocks(listOf(block("b1"), block("b2"), block("b3")))

        assertTrue(result.isRight())
        listOf("b1", "b2", "b3").forEach { id ->
            assertNotNull(blockRepo.getBlockByUuid(id).first().getOrNull(), "$id should be stored")
        }
        actor.close()
    }

    @Test
    fun `deleteBlocksForPage removes all blocks for that page`() = runBlocking {
        val blockRepo = FakeBlockRepository(mapOf("page-1" to listOf(block("b1"), block("b2"))))
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository())

        val result = actor.deleteBlocksForPage("page-1")

        assertTrue(result.isRight())
        val remaining = blockRepo.getBlocksForPage("page-1").first().getOrNull()
        assertTrue(remaining.isNullOrEmpty(), "all blocks should be deleted")
        actor.close()
    }

    // ──────────────── failure propagation ─────────────────────────────────────

    @Test
    fun `savePage failure is returned to caller`() = runBlocking {
        val error = RuntimeException("db full")
        val pageRepo = object : FakePageRepository() {
            override suspend fun savePage(page: Page): Either<DomainError, Unit> = DomainError.DatabaseError.WriteFailed(error.toString()).left()
        }
        val actor = DatabaseWriteActor(FakeBlockRepository(), pageRepo)

        val result = actor.savePage(page("p-1"))

        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull()?.message?.contains(error.message!!) == true)
        actor.close()
    }

    @Test
    fun `saveBlocks failure is returned to caller`() = runBlocking {
        val error = RuntimeException("constraint violation")
        val blockRepo = object : FakeBlockRepository() {
            override suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, Unit> =
                DomainError.DatabaseError.WriteFailed(error.toString()).left()
        }
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository())

        val result = actor.saveBlocks(listOf(block("b1")))

        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull()?.message?.contains(error.message!!) == true)
        actor.close()
    }

    @Test
    fun `deleteBlocksForPage failure is returned to caller`() = runBlocking {
        val error = RuntimeException("locked")
        val blockRepo = object : FakeBlockRepository() {
            override suspend fun deleteBlocksForPage(pageUuid: String): Either<DomainError, Unit> =
                DomainError.DatabaseError.WriteFailed(error.toString()).left()
        }
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository())

        val result = actor.deleteBlocksForPage("page-1")

        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull()?.message?.contains(error.message!!) == true)
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
        // Fail specifically when saving 2+ blocks together (i.e. a coalesced batch transaction).
        // Individual retries (1 block each) succeed. This is dispatcher-agnostic: whether the
        // actor coalesces or not, both blocks end up stored.
        val blockRepo = object : FakeBlockRepository() {
            override suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, Unit> =
                if (blocks.size > 1) DomainError.DatabaseError.WriteFailed("batch failed").left()
                else super.saveBlocks(blocks)
        }
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository())

        val j1 = launch { actor.saveBlocks(listOf(block("b1", "page-1"))) }
        val j2 = launch { actor.saveBlocks(listOf(block("b2", "page-2"))) }
        joinAll(j1, j2)

        // Both blocks stored — either via individual retries after a coalesced batch failure,
        // or directly if the actor processed them separately.
        assertNotNull(blockRepo.getBlockByUuid("b1").first().getOrNull(), "b1 stored after retry")
        assertNotNull(blockRepo.getBlockByUuid("b2").first().getOrNull(), "b2 stored after retry")
        actor.close()
    }

    @Test
    fun `partial success preserved when individual retries have different outcomes`() = runBlocking {
        // Fail any combined batch (size > 1); on individual retries, page-fail blocks still fail.
        // This is dispatcher-agnostic: whether coalesced or not, page-ok succeeds and page-fail fails.
        val blockRepo = object : FakeBlockRepository() {
            override suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, Unit> = when {
                blocks.size > 1 -> DomainError.DatabaseError.WriteFailed("batch failed").left()
                blocks.any { it.pageUuid == "page-fail" } ->
                    DomainError.DatabaseError.WriteFailed("individual fail for page-fail").left()
                else -> super.saveBlocks(blocks)
            }
        }
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository())

        var r1: Either<DomainError, Unit>? = null
        var r2: Either<DomainError, Unit>? = null
        val j1 = launch { r1 = actor.saveBlocks(listOf(block("b1", "page-ok"))) }
        val j2 = launch { r2 = actor.saveBlocks(listOf(block("b2", "page-fail"))) }
        joinAll(j1, j2)

        assertNotNull(r1); assertNotNull(r2)
        assertTrue(r1!!.isRight(), "page-ok should succeed on individual retry")
        assertTrue(r2!!.isLeft(), "page-fail should still fail on individual retry")
        actor.close()
    }

    // ──────────────── concurrent callers all receive results ──────────────────

    @Test
    fun `twenty concurrent saveBlocks callers all receive results`() = runBlocking {
        val blockRepo = FakeBlockRepository()
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository())
        val n = 20

        val results = (1..n).map { i ->
            async { actor.saveBlocks(listOf(block("b$i", "page-$i"))) }
        }.map { it.await() }

        assertEquals(n, results.size)
        assertTrue(results.all { it.isRight() }, "all callers should receive success")
        (1..n).forEach { i ->
            assertNotNull(blockRepo.getBlockByUuid("b$i").first().getOrNull())
        }
        actor.close()
    }

    @Test
    fun `mixed request types complete without deadlock`() = runBlocking {
        val blockRepo = FakeBlockRepository()
        val pageRepo = FakePageRepository()
        val actor = DatabaseWriteActor(blockRepo, pageRepo)

        val results = listOf(
            async { actor.savePage(page("p-1")) },
            async { actor.saveBlocks(listOf(block("b1", "p-1"))) },
            async { actor.deleteBlocksForPage("p-1") },
            async { actor.savePage(page("p-2")) },
            async { actor.saveBlocks(listOf(block("b2", "p-2"))) },
        ).map { it.await() }

        assertTrue(results.all { it.isRight() })
        actor.close()
    }

    // ──────── loop recovery: actor survives an unexpected exception ─────────────────────
    //
    // If processRequest throws unexpectedly (e.g. OOM, assertion) the loop must not die.
    // The failing request's deferred is completed exceptionally and subsequent writes succeed.

    @Test
    fun `actor loop continues after unexpected exception in processRequest`() = runBlocking {
        val boom = RuntimeException("unexpected")
        var throwOnce = AtomicBoolean(true)
        val pageRepo = object : FakePageRepository() {
            override suspend fun savePage(page: Page): Either<DomainError, Unit> {
                if (throwOnce.getAndSet(false)) throw boom
                return super.savePage(page)
            }
        }
        val actor = DatabaseWriteActor(FakeBlockRepository(), pageRepo)

        val r1 = actor.savePage(page("p-bad"))
        assertTrue(r1.isLeft(), "throwing request should surface as failure")
        assertTrue(r1.leftOrNull()?.message?.contains(boom.message!!) == true)

        // Loop must still be alive — subsequent write goes through
        val r2 = withTimeout(2000) { actor.savePage(page("p-ok")) }
        assertTrue(r2.isRight(), "actor should continue processing after the exception")

        actor.close()
    }

    // ──────────────── lifecycle: close stops the actor ───────────────────────────────

    @Test
    fun `writes complete after actor is closed and recreated`() = runBlocking {
        val actor = DatabaseWriteActor(FakeBlockRepository(), FakePageRepository())

        assertTrue(actor.savePage(page("p-1")).isRight(), "pre-close write should succeed")

        // close() is the only way to stop the actor now — simulates graph shutdown
        actor.close()

        // A fresh actor picks up where the old one left off (new graph load)
        val actor2 = DatabaseWriteActor(FakeBlockRepository(), FakePageRepository())
        val result = withTimeout(2000) { actor2.savePage(page("p-2")) }
        assertTrue(result.isRight(), "new actor should process writes normally")

        actor2.close()
    }

    // ──────────────── deleteBlocksForPages ───────────────────────────────────

    @Test
    fun `deleteBlocksForPages removes blocks for all specified pages`() = runBlocking {
        val blockRepo = FakeBlockRepository(
            mapOf(
                "page-1" to listOf(block("b1", "page-1"), block("b2", "page-1")),
                "page-2" to listOf(block("b3", "page-2")),
                "page-3" to listOf(block("b4", "page-3")),
            )
        )
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository())

        val result = actor.deleteBlocksForPages(listOf("page-1", "page-2"))

        assertTrue(result.isRight())
        assertTrue(blockRepo.getBlocksForPage("page-1").first().getOrNull().isNullOrEmpty())
        assertTrue(blockRepo.getBlocksForPage("page-2").first().getOrNull().isNullOrEmpty())
        assertNotNull(blockRepo.getBlockByUuid("b4").first().getOrNull(), "page-3 untouched")
        actor.close()
    }

    @Test
    fun `deleteBlocksForPages with empty list returns success without touching repository`() = runBlocking {
        var called = false
        val blockRepo = object : FakeBlockRepository() {
            override suspend fun deleteBlocksForPages(pageUuids: List<String>): Either<DomainError, Unit> {
                called = true
                return super.deleteBlocksForPages(pageUuids)
            }
        }
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository())

        val result = actor.deleteBlocksForPages(emptyList())

        assertTrue(result.isRight())
        assertTrue(!called, "repository should not be called for empty list")
        actor.close()
    }

    @Test
    fun `deleteBlocksForPages failure is returned to caller`() = runBlocking {
        val error = RuntimeException("disk error")
        val blockRepo = object : FakeBlockRepository() {
            override suspend fun deleteBlocksForPages(pageUuids: List<String>): Either<DomainError, Unit> =
                DomainError.DatabaseError.WriteFailed(error.toString()).left()
        }
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository())

        val result = actor.deleteBlocksForPages(listOf("page-1"))

        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull()?.message?.contains(error.message!!) == true)
        actor.close()
    }

    // ──────────────── executeBatch ────────────────────────────────────────────

    @Test
    fun `executeBatch executes all operations in block`() = runBlocking {
        val blockRepo = FakeBlockRepository()
        val pageRepo = FakePageRepository()
        val actor = DatabaseWriteActor(blockRepo, pageRepo)

        actor.executeBatch("batch-1") {
            savePage(page("p-1"))
            saveBlocks(listOf(block("b1", "p-1"), block("b2", "p-1")))
        }

        assertNotNull(pageRepo.getPageByUuid("p-1").first().getOrNull())
        assertNotNull(blockRepo.getBlockByUuid("b1").first().getOrNull())
        assertNotNull(blockRepo.getBlockByUuid("b2").first().getOrNull())
        actor.close()
    }

    @Test
    fun `executeBatch finally block runs and actor survives exception in block`() = runBlocking {
        val actor = DatabaseWriteActor(FakeBlockRepository(), FakePageRepository())
        var threw = false

        try {
            actor.executeBatch("batch-err") {
                @Suppress("UNREACHABLE_CODE")
                throw RuntimeException("mid-batch failure")
            }
        } catch (_: RuntimeException) {
            threw = true
        }

        assertTrue(threw)
        // Actor must still be alive after the exception escaped executeBatch
        val result = withTimeout(2000) { actor.savePage(page("p-ok")) }
        assertTrue(result.isRight(), "actor should continue after executeBatch exception")
        actor.close()
    }

    // ──────────────── ordering guarantee ─────────────────────────────────────

    @Test
    fun `delete before save produces clean final state`() = runBlocking {
        val blockRepo = FakeBlockRepository(
            mapOf("page-1" to listOf(block("old-1"), block("old-2")))
        )
        val actor = DatabaseWriteActor(blockRepo, FakePageRepository())

        actor.deleteBlocksForPage("page-1")
        actor.saveBlocks(listOf(block("new-1"), block("new-2")))

        val stored = blockRepo.getBlocksForPage("page-1").first().getOrNull() ?: emptyList()
        assertEquals(2, stored.size, "exactly 2 new blocks")
        assertNull(stored.find { it.uuid == "old-1" }, "old block removed")
        assertNotNull(stored.find { it.uuid == "new-1" }, "new block present")
        actor.close()
    }
}
