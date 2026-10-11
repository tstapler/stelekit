@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.screens.copy

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.merge.MergeBlock
import dev.stapler.stelekit.merge.MergeConverters
import dev.stapler.stelekit.merge.MergeOutcome
import dev.stapler.stelekit.merge.MergePage
import dev.stapler.stelekit.merge.MergePolicy
import dev.stapler.stelekit.merge.MergePropertyKeys
import dev.stapler.stelekit.merge.mergePage
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.PropertyRepository
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import dev.stapler.stelekit.repository.SqlDelightPropertyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * ConflictReviewViewModel against a real in-memory SQLite DB: bounded conflict reads, the two
 * actions, and the re-copy contract (remove -> flagged sibling returns once; resolve -> nothing added).
 *
 * Re-copy here is [mergePage] against the page read back from the DB. `ActiveTargetWriter` is not on this
 * branch; `PageMergeService` calls the same [mergePage] for a DB-backed target.
 */
class ConflictReviewViewModelTest {
    private val source = GraphId("aaaaaaaaaaaaaaaa")
    private val policy = MergePolicy(source, "Personal")
    private val epoch = Instant.fromEpochMilliseconds(0)

    private val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    private val database = SteleDatabase(driver)
    private val blockRepo = SqlDelightBlockRepository(database)
    private val pageRepo = SqlDelightPageRepository(database)
    private val propertyRepo = SqlDelightPropertyRepository(database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actor = DatabaseWriteActor(blockRepo, pageRepo, scope = scope)
    private val persisted = mutableListOf<PageUuid>()
    private val vms = mutableListOf<ConflictReviewViewModel>()

    @AfterTest
    fun tearDown() {
        vms.forEach { it.close() }
        actor.close()
        scope.cancel()
    }

    private fun uuid(n: Int) = "00000000-0000-0000-0000-%012x".format(n)

    private fun vm() = ConflictReviewViewModel(propertyRepo, blockRepo, actor, { persisted += it }).also { vms += it }

    private suspend fun awaitState(vm: ConflictReviewViewModel, what: String, cond: (ConflictReviewState) -> Boolean): ConflictReviewState =
        withTimeout(30_000) {
            while (true) {
                vm.state.value.takeIf(cond)?.let { return@withTimeout it }
                delay(10)
            }
            error("unreachable: $what")
        }

    private suspend fun savePage(name: String, pageUuid: String, merge: MergePage) {
        val page = Page(uuid = PageUuid(pageUuid), name = name, createdAt = epoch, updatedAt = epoch)
        pageRepo.savePage(page)
        blockRepo.saveBlocks(MergeConverters.toBlocks(merge, page.uuid, "pages/$name.md"))
    }

    private suspend fun readBack(pageUuid: String): MergePage {
        val page = pageRepo.getPageByUuid(PageUuid(pageUuid)).first().getOrNull()!!
        return MergeConverters.toMergePage(page, blockRepo.getBlocksForPage(page.uuid).first().getOrNull()!!)
    }

    private fun page(name: String, text: String, blockN: Int = 100) = MergePage(name, blocks = listOf(MergeBlock(uuid(blockN), text)))

    /** Target holds "Draft v1"; copying the source's "Draft v2" for the same block uuid leaves a flagged sibling. */
    private suspend fun seedConflict(name: String = "Roadmap", pageUuid: String = uuid(1), blockN: Int = 100): MergePage {
        savePage(name, pageUuid, page(name, "Draft v1", blockN))
        val merged = mergePage(readBack(pageUuid), page(name, "Draft v2", blockN), policy) as MergeOutcome.Merged
        blockRepo.saveBlocks(MergeConverters.toBlocks(merged.page, PageUuid(pageUuid), "pages/$name.md"))
        return merged.page
    }

    private suspend fun recopy(pageUuid: String, name: String = "Roadmap"): MergeOutcome {
        val outcome = mergePage(readBack(pageUuid), page(name, "Draft v2"), policy)
        if (outcome is MergeOutcome.Merged) {
            blockRepo.saveBlocks(MergeConverters.toBlocks(outcome.page, PageUuid(pageUuid), "pages/$name.md"))
        }
        return outcome
    }

    private fun conflictCount() = runBlocking { propertyRepo.getMergeConflicts(null, 100).getOrNull()!!.size }

    @Test
    fun `lists the flagged block with page name and neighboring original`() = runBlocking {
        seedConflict()
        val state = awaitState(vm(), "loaded") { !it.loading }

        val row = state.rows.single()
        assertEquals("Roadmap", row.pageName)
        assertEquals("Draft v1", row.originalText)
        assertEquals("Draft v2", row.copiedText)
        assertEquals("Personal", row.sourceGraph)
        assertTrue(!state.hasMore)
    }

    @Test
    fun `empty graph reports no rows`() = runBlocking {
        val state = awaitState(vm(), "loaded") { !it.loading }
        assertTrue(state.rows.isEmpty() && state.loadError == null)
    }

    @Test
    fun `reads are keyset pages of at most 100 rows`() = runBlocking {
        val names = (1..105).map { "Page%03d".format(it) }
        names.forEachIndexed { i, name -> seedConflict(name, uuid(1000 + i), blockN = 5000 + i) }
        val first = propertyRepo.getMergeConflicts(null, 500).getOrNull()!!
        assertEquals(PropertyRepository.MAX_CONFLICT_PAGE, first.size)
        val second = propertyRepo.getMergeConflicts(first.last().block.uuid.value, 500).getOrNull()!!
        assertTrue(second.size in 1..5, "second page had ${second.size}")
        assertTrue(first.map { it.block.uuid }.intersect(second.map { it.block.uuid }.toSet()).isEmpty())

        val state = awaitState(vm(), "loaded") { !it.loading }
        assertEquals(ConflictReviewViewModel.PAGE_SIZE, state.rows.size)
        assertTrue(state.hasMore)
    }

    @Test
    fun `mark resolved drops only merge-conflict and a re-copy adds nothing`() = runBlocking {
        seedConflict()
        val vm = vm()
        val row = awaitState(vm, "loaded") { !it.loading }.rows.single()

        vm.markResolved(row.key)
        val after = awaitState(vm, "resolved") { it.rows.isEmpty() }

        assertEquals(1, after.focusToken)
        assertEquals(listOf(PageUuid(uuid(1))), persisted)
        val props = blockRepo.getBlockByUuid(row.block.uuid).first().getOrNull()!!.properties
        assertNull(props[MergePropertyKeys.CONFLICT])
        assertNotNull(props[MergePropertyKeys.SRC_ID])
        assertEquals(0, conflictCount())

        assertTrue(recopy(uuid(1)) is MergeOutcome.Unchanged)
        assertEquals(0, conflictCount())
    }

    @Test
    fun `remove deletes only the flagged block and re-copy returns it flagged exactly once`() = runBlocking {
        seedConflict()
        val vm = vm()
        val row = awaitState(vm, "loaded") { !it.loading }.rows.single()

        vm.requestRemove(row.key)
        assertEquals(row.key, vm.state.value.confirmRemove?.key)
        vm.confirmRemove()
        val after = awaitState(vm, "removed") { it.rows.isEmpty() && it.undo != null }

        assertTrue(after.undo!!.message.contains("may return on the next copy from Personal"))
        assertNull(blockRepo.getBlockByUuid(row.block.uuid).first().getOrNull())
        assertEquals(1, readBack(uuid(1)).blocks.size, "original survives")
        assertEquals(0, conflictCount())

        val outcome = recopy(uuid(1)) as MergeOutcome.Merged
        assertEquals(1, outcome.conflicts.size)
        assertEquals(1, conflictCount())
        assertTrue(recopy(uuid(1)) is MergeOutcome.Unchanged)
        assertEquals(1, conflictCount())
    }

    @Test
    fun `undo restores the removed block still flagged`() = runBlocking {
        seedConflict()
        val vm = vm()
        val row = awaitState(vm, "loaded") { !it.loading }.rows.single()
        vm.requestRemove(row.key)
        vm.confirmRemove()
        awaitState(vm, "removed") { it.undo != null }

        vm.undoRemove()
        val state = awaitState(vm, "restored") { !it.loading && it.rows.size == 1 && it.undo == null }

        assertEquals(row.key, state.rows.single().key)
        assertEquals(1, conflictCount())
    }

    @Test
    fun `cancelling the confirmation deletes nothing`() = runBlocking {
        seedConflict()
        val vm = vm()
        val row = awaitState(vm, "loaded") { !it.loading }.rows.single()

        vm.requestRemove(row.key)
        vm.cancelRemove()

        assertNull(vm.state.value.confirmRemove)
        assertEquals(1, conflictCount())
    }

    @Test
    fun `confirmation text names the source graph`() {
        assertEquals(
            "Remove this block? It will come back, flagged again, if you copy this page from Personal again. " +
                "To keep it from coming back, choose Mark resolved instead.",
            ConflictReviewViewModel.removeConfirmation("Personal"),
        )
    }
}
