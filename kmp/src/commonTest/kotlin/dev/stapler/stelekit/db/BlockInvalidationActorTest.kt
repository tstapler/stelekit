package dev.stapler.stelekit.db

import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BlockInvalidationActorTest {

    private val now = Instant.fromEpochMilliseconds(0)

    private fun makeActor(scope: CoroutineScope) = DatabaseWriteActor(
        blockRepository = InMemoryBlockRepository(),
        pageRepository = InMemoryPageRepository(),
        scope = scope,
    )

    private fun makePage(uuid: String) = Page(
        uuid = PageUuid(uuid),
        name = "Page $uuid",
        createdAt = now,
        updatedAt = now,
    )

    private fun makeBlock(uuid: String, pageUuid: String, position: Int = 0) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = "content",
        position = position,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun actor_saveBlocks_should_emit_correct_page_uuids() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val actor = makeActor(scope)
        val pageUuidA = PageUuid("page-A")
        val pageUuidB = PageUuid("page-B")
        val blockA = makeBlock("block-A", pageUuidA.value)
        val blockB = makeBlock("block-B", pageUuidB.value)

        var emitted: Set<PageUuid>? = null
        val collectJob = launch { emitted = actor.blockInvalidations.first() }
        advanceUntilIdle()

        actor.saveBlocks(listOf(blockA, blockB))
        advanceUntilIdle()

        assertEquals(setOf(pageUuidA, pageUuidB), emitted)
        collectJob.cancel()
        actor.close()
    }

    @Test
    fun actor_saveBlocksDiff_should_emit_correct_page_uuids() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val actor = makeActor(scope)
        val pageUuidA = PageUuid("page-A")
        val pageUuidB = PageUuid("page-B")
        val blockA = makeBlock("block-A", pageUuidA.value)
        val blockB = makeBlock("block-B", pageUuidB.value)

        var emitted: Set<PageUuid>? = null
        val collectJob = launch { emitted = actor.blockInvalidations.first() }
        advanceUntilIdle()

        actor.saveBlocksDiff(toInsert = listOf(blockA), toUpdate = listOf(blockB))
        advanceUntilIdle()

        assertEquals(setOf(pageUuidA, pageUuidB), emitted)
        collectJob.cancel()
        actor.close()
    }

    @Test
    fun actor_deleteBlocksForPage_should_emit_single_page_uuid() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val actor = makeActor(scope)
        val pageUuidA = PageUuid("page-A")

        var emitted: Set<PageUuid>? = null
        val collectJob = launch { emitted = actor.blockInvalidations.first() }
        advanceUntilIdle()

        actor.deleteBlocksForPage(pageUuidA)
        advanceUntilIdle()

        assertEquals(setOf(pageUuidA), emitted)
        collectJob.cancel()
        actor.close()
    }

    @Test
    fun actor_deleteBlocksForPages_should_emit_all_page_uuids() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val actor = makeActor(scope)
        val pageUuidA = PageUuid("page-A")
        val pageUuidB = PageUuid("page-B")

        var emitted: Set<PageUuid>? = null
        val collectJob = launch { emitted = actor.blockInvalidations.first() }
        advanceUntilIdle()

        actor.deleteBlocksForPages(listOf(pageUuidA, pageUuidB))
        advanceUntilIdle()

        val result = emitted
        assertEquals(true, result?.contains(pageUuidA), "emitted set must contain pageUuidA")
        assertEquals(true, result?.contains(pageUuidB), "emitted set must contain pageUuidB")
        collectJob.cancel()
        actor.close()
    }

    @Test
    fun actor_execute_should_emit_wildcard() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val actor = makeActor(scope)

        var emitted: Set<PageUuid>? = null
        val collectJob = launch { emitted = actor.blockInvalidations.first() }
        advanceUntilIdle()

        actor.execute { Unit.right() }
        advanceUntilIdle()

        assertEquals(setOf(DatabaseWriteActor.WILDCARD_PAGE_UUID), emitted)
        collectJob.cancel()
        actor.close()
    }

    @Test
    fun actor_execute_should_emit_wildcard_even_on_failure() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val actor = makeActor(scope)

        var emitted: Set<PageUuid>? = null
        val collectJob = launch { emitted = actor.blockInvalidations.first() }
        advanceUntilIdle()

        actor.execute { DomainError.DatabaseError.WriteFailed("err").left() }
        advanceUntilIdle()

        assertEquals(setOf(DatabaseWriteActor.WILDCARD_PAGE_UUID), emitted,
            "execute must emit wildcard even when the operation returns a failure")
        collectJob.cancel()
        actor.close()
    }

    @Test
    fun actor_savePage_should_not_emit_block_invalidation() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val actor = makeActor(scope)
        val page = makePage("page-A")

        var emitted: Set<PageUuid>? = null
        val collectJob = launch { emitted = actor.blockInvalidations.first() }
        advanceUntilIdle()

        actor.savePage(page)
        advanceUntilIdle()

        assertNull(emitted, "savePage must not emit block invalidation")
        collectJob.cancel()
        actor.close()
    }
}
