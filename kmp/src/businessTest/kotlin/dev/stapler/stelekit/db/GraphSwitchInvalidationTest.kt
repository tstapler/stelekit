package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * AC-7: Graph-switch safety tests.
 *
 * Verifies that when a graph is switched:
 * - The old BSM's scope is cancelled on close(), stopping all observation jobs
 * - A new BSM subscribed to a different actor does not receive old actor invalidations
 * - Writes to a closed actor (in-flight during graph switch) do not crash
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphSwitchInvalidationTest {

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
     * TC-SWITCH-1: close() must cancel the scope so all observation jobs stop.
     *
     * The test creates BSM with an externally-supplied scope, calls close(),
     * then asserts the scope is no longer active. Since scope is private in BSM,
     * we inject it via the constructor parameter and hold a reference to check isActive.
     */
    @Test
    fun graphSwitch_oldBSM_observationJobsCancelledAfterClose() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(StubFileSystem(), pageRepo, blockRepo)
        val invalidationFlow = MutableSharedFlow<Set<PageUuid>>(replay = 0, extraBufferCapacity = 64)

        val bsmScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val bsm = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = bsmScope,
            invalidationSource = invalidationFlow,
        )

        val pageUuidA = PageUuid("page-A")
        pageRepo.savePage(makePage(pageUuidA.value))

        bsm.observePage(pageUuidA)
        advanceUntilIdle()

        // Simulate graph switch: close the old BSM
        bsm.close()

        assertFalse(bsmScope.isActive, "BSM close() must cancel the injected scope")
    }

    /**
     * TC-SWITCH-2: New BSM subscribed only to new actor does not see old actor's invalidations.
     *
     * Old actor emits; new BSM (subscribed to newActorFlow) must not trigger a re-query.
     */
    @Test
    fun graphSwitch_oldActorInvalidation_notDeliveredToNewBSM() = runTest {
        val oldActorFlow = MutableSharedFlow<Set<PageUuid>>(replay = 0, extraBufferCapacity = 64)
        val newActorFlow = MutableSharedFlow<Set<PageUuid>>(replay = 0, extraBufferCapacity = 64)

        val pageUuidA = PageUuid("page-A")

        // CountingBlockRepository tracks how many times getBlocksForPage is called per UUID
        var callCount = 0
        val delegate = InMemoryBlockRepository()
        val blockRepo = object : BlockRepository by delegate {
            override fun getBlocksForPage(pageUuid: PageUuid) =
                delegate.getBlocksForPage(pageUuid).also { callCount++ }
        }
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(StubFileSystem(), pageRepo, blockRepo)

        pageRepo.savePage(makePage(pageUuidA.value))

        // New BSM subscribes only to newActorFlow
        val newBsmScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val newBsm = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = newBsmScope,
            invalidationSource = newActorFlow,
        )
        newBsm.observePage(pageUuidA)
        advanceUntilIdle()

        // Reset count after initial pull
        callCount = 0

        // Old actor emits — new BSM must NOT re-query
        oldActorFlow.emit(setOf(pageUuidA))
        advanceUntilIdle()

        kotlin.test.assertEquals(0, callCount,
            "New BSM must not re-query when old actor emits a targeted invalidation")

        newBsm.close()
    }

    /**
     * TC-SWITCH-3: A write to the old actor after BSM is closed must not crash.
     *
     * SharedFlow with DROP_OLDEST handles zero-subscriber emission without throwing.
     * This test simply asserts no exception escapes.
     */
    @Test
    fun graphSwitch_inflight_write_on_old_actor_does_not_crash() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(StubFileSystem(), pageRepo, blockRepo)
        val invalidationFlow = MutableSharedFlow<Set<PageUuid>>(replay = 0, extraBufferCapacity = 64)

        val pageUuidA = PageUuid("page-A")
        pageRepo.savePage(makePage(pageUuidA.value))
        val blockA = makeBlock("block-A", pageUuidA.value)

        val actorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val oldActor = DatabaseWriteActor(
            blockRepository = blockRepo,
            pageRepository = pageRepo,
            scope = actorScope,
        )

        val bsmScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val bsm = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = bsmScope,
            invalidationSource = invalidationFlow,
        )

        bsm.observePage(pageUuidA)
        advanceUntilIdle()

        // Simulate graph switch: BSM closed first
        bsm.close()
        advanceUntilIdle()

        // Write to old actor after BSM closed — must not throw
        oldActor.saveBlocks(listOf(blockA))
        advanceUntilIdle()

        // If we reach here without exception, the test passes
        oldActor.close()
    }
}
