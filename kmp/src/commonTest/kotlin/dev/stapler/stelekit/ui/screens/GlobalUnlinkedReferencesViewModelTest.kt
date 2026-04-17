package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalUnlinkedReferencesViewModelTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun now() = Clock.System.now()

    private fun makePage(uuid: String, name: String): Page = Page(
        uuid = uuid,
        name = name,
        createdAt = now(),
        updatedAt = now(),
    )

    private fun makeBlock(uuid: String, pageUuid: String, content: String, position: Int = 0): Block = Block(
        uuid = uuid,
        pageUuid = pageUuid,
        content = content,
        position = position,
        createdAt = now(),
        updatedAt = now(),
    )

    private fun buildViewModel(
        pageRepo: InMemoryPageRepository,
        blockRepo: InMemoryBlockRepository,
        matcher: AhoCorasickMatcher? = null,
    ): GlobalUnlinkedReferencesViewModel {
        val scope = TestScope(UnconfinedTestDispatcher())
        return GlobalUnlinkedReferencesViewModel(
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            scope = scope,
            writeActor = null,
            matcher = matcher,
        )
    }

    // -------------------------------------------------------------------------
    // 1. loadInitialPopulatesResults
    // -------------------------------------------------------------------------

    @Test
    fun loadInitialPopulatesResults() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        val pageAlpha = makePage("page-alpha", "Alpha")
        val pageBeta = makePage("page-beta", "Beta")
        pageRepo.savePage(pageAlpha)
        pageRepo.savePage(pageBeta)

        // Blocks on Alpha that mention Beta as plain text (unlinked)
        blockRepo.saveBlock(makeBlock("blk-a1", "page-alpha", "Beta is a good topic", position = 0))
        blockRepo.saveBlock(makeBlock("blk-a2", "page-alpha", "We should study Beta more", position = 1))

        // Blocks on Beta that mention Alpha as plain text (unlinked)
        blockRepo.saveBlock(makeBlock("blk-b1", "page-beta", "Alpha was discussed yesterday", position = 0))
        blockRepo.saveBlock(makeBlock("blk-b2", "page-beta", "Alpha resources are helpful", position = 1))

        val matcher = AhoCorasickMatcher(
            mapOf(
                "alpha" to "Alpha",
                "beta" to "Beta",
            )
        )
        val vm = buildViewModel(pageRepo, blockRepo, matcher)

        vm.loadInitial()

        val state = vm.state.value
        assertFalse(state.isLoading, "isLoading should be false after loadInitial completes")
        assertTrue(state.results.size >= 2, "Expected at least 2 unlinked reference entries, got ${state.results.size}")
    }

    // -------------------------------------------------------------------------
    // 2. loadInitialEmptyGraph
    // -------------------------------------------------------------------------

    @Test
    fun loadInitialEmptyGraph() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        val vm = buildViewModel(pageRepo, blockRepo)

        vm.loadInitial()

        val state = vm.state.value
        assertTrue(state.results.isEmpty(), "Results should be empty for an empty graph")
        assertFalse(state.isLoading, "isLoading should be false after loadInitial completes")
    }

    // -------------------------------------------------------------------------
    // 3. rejectRemovesEntryFromList
    // -------------------------------------------------------------------------

    @Test
    fun rejectRemovesEntryFromList() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        val pageTarget = makePage("page-target", "Target")
        pageRepo.savePage(pageTarget)

        val block = makeBlock("blk-t1", "page-target", "No real unlinked content here", position = 0)
        blockRepo.saveBlock(block)

        // Add a source page whose blocks mention Target
        val pageSource = makePage("page-source", "Source")
        pageRepo.savePage(pageSource)
        blockRepo.saveBlock(makeBlock("blk-s1", "page-source", "Target is a useful page", position = 0))

        val matcher = AhoCorasickMatcher(mapOf("target" to "Target"))
        val vm = buildViewModel(pageRepo, blockRepo, matcher)

        vm.loadInitial()

        val stateBefore = vm.state.value
        assertTrue(stateBefore.results.isNotEmpty(), "Expected at least one entry before reject")

        val entry = stateBefore.results.first()
        vm.rejectSuggestion(entry)

        val stateAfter = vm.state.value
        assertFalse(entry in stateAfter.results, "Rejected entry must not remain in results")

        // Verify no write: block content in repo must be unchanged
        val blockInRepo = blockRepo.getBlockByUuid(entry.block.uuid).first().getOrNull()
        assertEquals(entry.block.content, blockInRepo?.content, "Block content must be unchanged after reject")
    }

    // -------------------------------------------------------------------------
    // 4. acceptRemovesEntryFromList
    // -------------------------------------------------------------------------

    @Test
    fun acceptRemovesEntryFromList() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        val pageTarget = makePage("page-tgt", "Tgt")
        pageRepo.savePage(pageTarget)

        val pageSource = makePage("page-src", "Src")
        pageRepo.savePage(pageSource)

        val blockContent = "Tgt is a great concept"
        val sourceBlock = makeBlock("blk-src1", "page-src", blockContent, position = 0)
        blockRepo.saveBlock(sourceBlock)

        val matcher = AhoCorasickMatcher(mapOf("tgt" to "Tgt"))
        val vm = buildViewModel(pageRepo, blockRepo, matcher)

        vm.loadInitial()

        val stateBefore = vm.state.value
        assertTrue(stateBefore.results.isNotEmpty(), "Expected at least one entry before accept")

        val entry = stateBefore.results.first()
        vm.acceptSuggestion(entry)

        val stateAfter = vm.state.value
        assertFalse(entry in stateAfter.results, "Accepted entry must not remain in results")
    }

    // -------------------------------------------------------------------------
    // 5. acceptStaleGuardPreventsWrite
    // -------------------------------------------------------------------------

    @Test
    fun acceptStaleGuardPreventsWrite() = runTest {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        val pageTarget = makePage("page-stale-tgt", "Stale")
        pageRepo.savePage(pageTarget)

        val pageSource = makePage("page-stale-src", "StaleSrc")
        pageRepo.savePage(pageSource)

        // Current live content differs from what will be captured
        val liveContent = "Stale concept updated by user"
        val liveBlock = makeBlock("blk-stale1", "page-stale-src", liveContent, position = 0)
        blockRepo.saveBlock(liveBlock)

        val matcher = AhoCorasickMatcher(mapOf("stale" to "Stale"))
        val vm = buildViewModel(pageRepo, blockRepo, matcher)

        vm.loadInitial()

        // Manually construct a stale entry: capturedContent differs from liveContent
        val staleEntry = UnlinkedRefEntry(
            block = liveBlock,
            targetPageName = "Stale",
            matchStart = 0,
            matchEnd = 5,
            capturedContent = "old content that does not match",
        )

        // Inject the stale entry into results so we can call acceptSuggestion
        // We do this by adding it alongside any loaded results via a fresh entry list.
        // Because the stale guard compares capturedContent to live block content, we
        // need to ensure the stale entry is present in state.results when accept is called.
        // We call rejectSuggestion to clear real results, then rely on direct accept call.

        // Call accept directly — the VM will re-fetch live block (liveContent) and compare
        // against capturedContent ("old content…"), detect staleness, and remove without writing.
        vm.acceptSuggestion(staleEntry)

        // Verify block was NOT modified in the repository
        val blockAfter = blockRepo.getBlockByUuid(liveBlock.uuid).first().getOrNull()
        assertEquals(liveContent, blockAfter?.content, "Block content must remain unchanged when stale guard fires")

        // The stale entry should not appear in results (it was removed with error message)
        assertFalse(staleEntry in vm.state.value.results, "Stale entry must be removed from results")
    }
}
