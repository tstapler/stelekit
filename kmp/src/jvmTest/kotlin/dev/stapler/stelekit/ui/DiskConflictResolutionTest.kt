package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Tests for the disk-conflict resolution flow (Fix 3):
 *
 * 1. DiskConflict is surfaced in AppState when an external change is detected
 * 2. keepLocalChanges() dismisses conflict and re-queues save
 * 3. acceptDiskVersion() dismisses conflict and triggers reload from disk
 * 4. saveAsNewBlock() creates a block with local content then reloads
 * 5. manualResolve() writes conflict markers into the editing block
 */
class DiskConflictResolutionTest {

    private val now = Clock.System.now()
    private val testPageUuid = "page-test-conflict"
    private val testBlockUuid = "block-test-editing"
    private val testFilePath = "/tmp/test-graph/pages/ConflictPage.md"

    private val testPage = Page(
        uuid = PageUuid(testPageUuid),
        name = "ConflictPage",
        filePath = testFilePath,
        createdAt = now,
        updatedAt = now
    )

    private val testBlock = Block(
        uuid = BlockUuid(testBlockUuid),
        pageUuid = PageUuid(testPageUuid),
        content = "Original content",
        level = 0,
        position = "a0",
        createdAt = now,
        updatedAt = now
    )

    private fun makeViewModel(
        pageRepo: FakePageRepository = FakePageRepository(listOf(testPage)),
        blockRepo: FakeBlockRepository = FakeBlockRepository(
            mapOf(testPageUuid to listOf(testBlock))
        ),
        graphLoader: GraphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
    ): StelekitViewModel {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val searchRepo = InMemorySearchRepository()
        @Suppress("DEPRECATION")
        val graphWriter = GraphWriter(PlatformFileSystem(), pageRepository = pageRepo)
        var viewModelRef: StelekitViewModel? = null
        val bsm = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = scope,
            graphWriter = graphWriter,
            pageRepository = pageRepo,
            graphPathProvider = { viewModelRef?.uiState?.value?.currentGraphPath ?: "" }
        )
        return StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = PlatformFileSystem(),
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = searchRepo,
                graphLoader = graphLoader,
                graphWriter = graphWriter,
                platformSettings = InMemorySettings(),
                scope = scope,
                blockStateManager = bsm,
            )
        ).also { viewModelRef = it }
    }

    @Test
    fun keepLocalChanges_dismisses_conflict() = runBlocking {
        val vm = makeViewModel()
        // Navigate to the test page so currentScreen is PageView
        vm.navigateTo(Screen.PageView(testPage))
        // Simulate editing state
        vm.requestEditBlock(testBlockUuid)

        // keepLocalChanges is a no-op when no conflict is set
        vm.keepLocalChanges()

        assertNull(vm.uiState.value.diskConflict, "No conflict should be set initially")
    }

    @Test
    fun manualResolve_writes_conflict_markers() = runBlocking {
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val vm = makeViewModel(blockRepo = blockRepo)

        vm.navigateTo(Screen.PageView(testPage))
        vm.requestEditBlock(testBlockUuid)

        // Verify the block has original content
        val blockBefore = blockRepo.getBlockByUuid(BlockUuid(testBlockUuid)).first().getOrNull()
        assertNotNull(blockBefore)
        assertEquals("Original content", blockBefore.content)
    }

    @Test
    fun diskConflict_model_has_all_fields() {
        val conflict = DiskConflict(
            pageUuid = "page-1",
            pageName = "My Page",
            filePath = "/path/to/page.md",
            editingBlockUuid = "block-1",
            localContent = "user typed this",
            diskContent = "- disk has this\n"
        )
        assertEquals("page-1", conflict.pageUuid)
        assertEquals("My Page", conflict.pageName)
        assertEquals("/path/to/page.md", conflict.filePath)
        assertEquals("block-1", conflict.editingBlockUuid)
        assertEquals("user typed this", conflict.localContent)
        assertEquals("- disk has this\n", conflict.diskContent)
    }

    @Test
    fun editingBlockId_is_tracked_through_requestEditBlock() = runBlocking {
        val vm = makeViewModel()

        // Initially no block is being edited
        assertNull(vm.uiState.value.editingBlockId)

        // Request editing
        vm.requestEditBlock(testBlockUuid, 5)
        assertEquals(testBlockUuid, vm.uiState.value.editingBlockId)
        assertEquals(5, vm.uiState.value.editingCursorIndex)

        // Clear editing
        vm.requestEditBlock(null)
        assertNull(vm.uiState.value.editingBlockId)
        assertNull(vm.uiState.value.editingCursorIndex)
    }

    @Test
    fun editingBlockContent_is_readable_via_blockStateManager() = runBlocking {
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val vm = makeViewModel(blockRepo = blockRepo)

        vm.navigateTo(Screen.PageView(testPage))
        vm.requestEditBlock(testBlockUuid)

        // Verify block is accessible in the repository
        val block = blockRepo.getBlockByUuid(BlockUuid(testBlockUuid)).first().getOrNull()
        assertNotNull(block)
        assertEquals("Original content", block.content)
    }

    // ─── Story 6.1.1: pendingConflicts lifecycle ────────────────────────────

    @Test
    fun pendingConflict_is_created_and_survives_navigation_to_the_conflicting_page(): Unit = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()

        // Default screen is Journals, not the conflicting page — this routes through the
        // "pending conflict" branch (off-page) instead of the immediate DiskConflict branch.
        graphLoader.emitExternalFileChange(testFilePath, "- disk content")

        assertNotNull(
            vm.uiState.value.pendingConflicts[testFilePath],
            "A pending conflict should be recorded while off-page"
        )

        vm.navigateTo(Screen.PageView(testPage))

        assertNotNull(vm.uiState.value.diskConflict, "Navigating to the page should surface the disk conflict")
        assertNotNull(
            vm.uiState.value.pendingConflicts[testFilePath],
            "pendingConflicts entry must survive navigation — it is only cleared by an explicit resolve action, " +
                "not by merely showing the dialog (this is the core regression covered by the lifecycle fix)"
        )
    }

    @Test
    fun keepLocalChanges_clears_the_pendingConflicts_entry() = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()

        graphLoader.emitExternalFileChange(testFilePath, "- disk content")
        vm.navigateTo(Screen.PageView(testPage))
        assertNotNull(vm.uiState.value.diskConflict)

        vm.keepLocalChanges()

        assertNull(vm.uiState.value.pendingConflicts[testFilePath])
    }

    @Test
    fun acceptDiskVersion_clears_the_pendingConflicts_entry(): Unit = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()

        graphLoader.emitExternalFileChange(testFilePath, "- disk content")
        vm.navigateTo(Screen.PageView(testPage))
        assertNotNull(vm.uiState.value.diskConflict)

        vm.acceptDiskVersion()

        // acceptDiskVersion() reloads via GraphLoader.parseAndSavePage(), which dispatches its
        // DB write through GraphLoader's internal DatabaseWriteActor (a real, separately-
        // scheduled coroutine) — unlike the Unconfined-dispatcher fixture scope, this write does
        // not complete synchronously, so the pendingConflicts clear must be awaited.
        withTimeout(2_000) {
            vm.uiState.first { it.pendingConflicts[testFilePath] == null }
        }
    }

    @Test
    fun saveAsNewBlock_clears_the_pendingConflicts_entry(): Unit = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()

        graphLoader.emitExternalFileChange(testFilePath, "- disk content")
        vm.navigateTo(Screen.PageView(testPage))
        assertNotNull(vm.uiState.value.diskConflict)

        vm.saveAsNewBlock()

        // saveAsNewBlock() reloads via GraphLoader.parseAndSavePage(), which dispatches its DB
        // write through GraphLoader's internal DatabaseWriteActor (a real, separately-scheduled
        // coroutine) — unlike the Unconfined-dispatcher fixture scope, this write does not
        // complete synchronously, so the pendingConflicts clear must be awaited.
        withTimeout(2_000) {
            vm.uiState.first { it.pendingConflicts[testFilePath] == null }
        }
    }

    @Test
    fun manualResolve_clears_pendingConflicts_entry_main_branch() = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()

        // Off-page first, so pendingConflicts[testFilePath] is genuinely populated before
        // manualResolve() runs. The on-page/immediate branch (observeExternalFileChanges) never
        // populates pendingConflicts by itself — only the off-page/deferred branch does — so
        // asserting the clear without this setup is vacuously true both before and after
        // manualResolve() runs and proves nothing about clearPendingConflict().
        graphLoader.emitExternalFileChange(testFilePath, "- disk content")
        assertNotNull(
            vm.uiState.value.pendingConflicts[testFilePath],
            "Off-page external change must populate pendingConflicts"
        )

        vm.navigateTo(Screen.PageView(testPage))

        // checkAndShowPendingConflict() promotes the pending conflict to an active diskConflict
        // but must NOT clear pendingConflicts merely by showing the dialog — only an explicit
        // resolve action does that (the lifecycle fix this whole suite covers).
        assertNotNull(
            vm.uiState.value.pendingConflicts[testFilePath],
            "pendingConflicts entry must survive navigation/promotion to an active diskConflict"
        )

        // The page has exactly one block, so checkAndShowPendingConflict() resolves
        // editingBlockUuid to that block's uuid (non-blank), forcing manualResolve()'s main
        // branch (writes conflict markers) rather than the early-return branch.
        val conflict = vm.uiState.value.diskConflict
        assertNotNull(conflict)
        assertTrue(
            conflict.editingBlockUuid.isNotBlank(),
            "Non-empty page must resolve to a non-blank editingBlockUuid, forcing manualResolve()'s main branch"
        )

        vm.manualResolve()

        assertNull(vm.uiState.value.diskConflict)
        assertNull(
            vm.uiState.value.pendingConflicts[testFilePath],
            "manualResolve()'s main branch must clear the pendingConflicts entry it inherited from the deferred/off-page path"
        )
    }

    @Test
    fun manualResolve_clears_pendingConflicts_entry_early_return_branch() = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        // Zero blocks on the page — checkAndShowPendingConflict() will resolve
        // editingBlockUuid to "" (no firstBlock to fall back to), forcing the early-return
        // branch of manualResolve().
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to emptyList()))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()

        graphLoader.emitExternalFileChange(testFilePath, "- disk content")
        vm.navigateTo(Screen.PageView(testPage))
        val conflict = vm.uiState.value.diskConflict
        assertNotNull(conflict)
        assertTrue(conflict.editingBlockUuid.isBlank(), "Empty page should resolve to a blank editingBlockUuid")

        vm.manualResolve()

        assertNull(vm.uiState.value.diskConflict, "The synchronous early-return clear must still fire")
        assertNull(vm.uiState.value.pendingConflicts[testFilePath])
    }

    // ─── Story 6.1.1b: coverage gaps ─────────────────────────────────────────

    @Test
    fun diskConflict_diskBlockContent_wiring_matches_local_block_position_on_disk() = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()
        vm.navigateTo(Screen.PageView(testPage))
        vm.requestEditBlock(testBlockUuid)

        // testBlock is the only (root-level, parentUuid == null) block on the page, so its
        // ordinal path is [0] — the single top-level bullet below is its disk counterpart.
        graphLoader.emitExternalFileChange(testFilePath, "- disk version content")

        val conflict = vm.uiState.value.diskConflict
        assertNotNull(conflict)
        assertEquals(
            "disk version content",
            conflict.diskBlockContent,
            "diskBlockContent must be wired through the live ViewModel, not just testable in isolation on the matcher"
        )
    }

    @Test
    fun pendingConflict_diskBlockContent_wiring_matches_first_block_position_via_checkAndShowPendingConflict() = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()

        // Off-page: default screen is Journals, not PageView(testPage) — this routes through
        // checkAndShowPendingConflict()'s deferred branch (using a fresh getBlocksForPage() read
        // and firstBlock), not observeExternalFileChanges()'s immediate/actively-edited-block
        // branch, which diskConflict_diskBlockContent_wiring_matches_local_block_position_on_disk
        // already covers.
        graphLoader.emitExternalFileChange(testFilePath, "- disk version content")
        assertNotNull(
            vm.uiState.value.pendingConflicts[testFilePath],
            "Off-page external change must populate pendingConflicts"
        )

        vm.navigateTo(Screen.PageView(testPage))

        // checkAndShowPendingConflict() computes diskBlockContent inside its own scope.launch
        // (a real getBlocksForPage() read), so await it rather than assume synchronous
        // completion.
        val conflict = withTimeout(2_000) {
            vm.uiState.first { it.diskConflict?.diskBlockContent != null }.diskConflict
        }
        assertNotNull(conflict)
        assertEquals(
            "disk version content",
            conflict.diskBlockContent,
            "diskBlockContent must be wired through checkAndShowPendingConflict()'s firstBlock-based " +
                "ordinal match, not just testable in isolation on the matcher"
        )
    }

    @Test
    fun showDiskConflictFullView_and_hideDiskConflictFullView_toggle_flag_without_clearing_diskConflict() = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()
        vm.navigateTo(Screen.PageView(testPage))
        vm.requestEditBlock(testBlockUuid)
        graphLoader.emitExternalFileChange(testFilePath, "- disk content")

        val conflictBefore = vm.uiState.value.diskConflict
        assertNotNull(conflictBefore)
        assertFalse(vm.uiState.value.diskConflictViewFullVisible)

        vm.showDiskConflictFullView()
        assertTrue(vm.uiState.value.diskConflictViewFullVisible)
        assertEquals(conflictBefore, vm.uiState.value.diskConflict)

        vm.hideDiskConflictFullView()
        assertFalse(vm.uiState.value.diskConflictViewFullVisible)
        assertEquals(conflictBefore, vm.uiState.value.diskConflict)
    }

    @Test
    fun manualResolve_main_branch_emits_snackbar_naming_the_page() = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()
        vm.navigateTo(Screen.PageView(testPage))
        vm.requestEditBlock(testBlockUuid)

        graphLoader.emitExternalFileChange(testFilePath, "- disk content")
        assertNotNull(vm.uiState.value.diskConflict)

        vm.manualResolve()

        val message = withTimeout(1_000) { vm.snackbarEvents.first() }
        assertEquals(
            "Conflict markers inserted — remove <<<<<<<, =======, >>>>>>> to let \"ConflictPage\" sync again",
            message
        )
    }

    @Test
    fun manualResolve_early_return_branch_emits_no_snackbar() = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to emptyList()))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()

        graphLoader.emitExternalFileChange(testFilePath, "- disk content")
        // Drain the "modified on disk" snackbar sent when the pending conflict was first
        // recorded — it is unrelated to manualResolve() and would otherwise be mistaken for
        // the conflict-markers snackbar this test is checking for.
        withTimeout(1_000) { vm.snackbarEvents.first() }

        vm.navigateTo(Screen.PageView(testPage))
        val conflict = vm.uiState.value.diskConflict
        assertNotNull(conflict)
        assertTrue(conflict.editingBlockUuid.isBlank())

        vm.manualResolve()

        assertNull(vm.uiState.value.diskConflict)
        val noSnackbar = withTimeoutOrNull(200) { vm.snackbarEvents.first() }
        assertNull(noSnackbar, "The early-return branch must not write conflict markers, so no snackbar should fire")
    }

    @Test
    fun manualResolve_persists_conflict_markers_containing_matched_diskBlockContent() = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()
        vm.navigateTo(Screen.PageView(testPage))
        vm.requestEditBlock(testBlockUuid)

        // testBlock is the only (root-level) block on the page, so its disk counterpart at the
        // same ordinal position successfully matches — manualResolve() should merge in the
        // matched diskBlockContent, not the raw-excerpt fallback.
        graphLoader.emitExternalFileChange(testFilePath, "- disk version content")
        val conflict = vm.uiState.value.diskConflict
        assertNotNull(conflict)
        assertEquals("disk version content", conflict.diskBlockContent)

        vm.manualResolve()

        val resolvedBlock = withTimeout(2_000) {
            blockRepo.getBlockByUuid(BlockUuid(testBlockUuid))
                .first { it.getOrNull()?.content?.contains("<<<<<<<") == true }
                .getOrNull()
        }
        assertNotNull(resolvedBlock)
        val content = resolvedBlock.content
        assertTrue(content.contains("<<<<<<< Your edit"), "must contain the local-edit marker")
        assertTrue(content.contains("Original content"), "must contain the local edit content")
        assertTrue(content.contains("======="), "must contain the separator marker")
        assertTrue(
            content.contains("disk version content"),
            "must contain the positionally-matched disk-block content, not a raw file excerpt"
        )
        assertFalse(
            content.contains("no matching section found"),
            "a successful positional match must not fall back to the file-excerpt heuristic"
        )
        assertTrue(content.contains(">>>>>>> Disk"), "must contain the disk-version marker")
    }

    @Test
    fun manualResolve_persists_fallback_excerpt_when_diskBlockContent_has_no_match() = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()
        vm.navigateTo(Screen.PageView(testPage))
        vm.requestEditBlock(testBlockUuid)

        // Empty disk content parses to zero blocks, so DiskConflictBlockMatcher's ordinal
        // lookup at testBlock's path ([0]) has nothing to index into — no positional match
        // exists, forcing manualResolve()'s fallback branch (raw file-excerpt text) instead of
        // the matched diskBlockContent.
        graphLoader.emitExternalFileChange(testFilePath, "")
        val conflict = vm.uiState.value.diskConflict
        assertNotNull(conflict)
        assertNull(
            conflict.diskBlockContent,
            "Empty disk content must fail to positionally match, forcing the fallback branch"
        )

        vm.manualResolve()

        val resolvedBlock = withTimeout(2_000) {
            blockRepo.getBlockByUuid(BlockUuid(testBlockUuid))
                .first { it.getOrNull()?.content?.contains("<<<<<<<") == true }
                .getOrNull()
        }
        assertNotNull(resolvedBlock)
        val content = resolvedBlock.content
        assertTrue(content.contains("<<<<<<< Your edit"), "must contain the local-edit marker")
        assertTrue(content.contains("======="), "must contain the separator marker")
        assertTrue(
            content.contains("(no matching section found — showing file excerpt)"),
            "must fall back to the file-excerpt text when no positional match exists"
        )
        assertTrue(content.contains(">>>>>>> Disk"), "must contain the disk-version marker")
    }

    @Test
    fun pendingConflictFilePaths_union_includes_deferred_and_currently_open_conflicts() = runBlocking {
        val otherPageUuid = "page-other-conflict"
        val otherFilePath = "/tmp/test-graph/pages/OtherPage.md"
        val otherPage = Page(
            uuid = PageUuid(otherPageUuid),
            name = "OtherPage",
            filePath = otherFilePath,
            createdAt = now,
            updatedAt = now
        )
        val otherBlock = Block(
            uuid = BlockUuid("block-other-conflict"),
            pageUuid = PageUuid(otherPageUuid),
            content = "Other content",
            level = 0,
            position = "a0",
            createdAt = now,
            updatedAt = now
        )
        val pageRepo = FakePageRepository(listOf(testPage, otherPage))
        val blockRepo = FakeBlockRepository(
            mapOf(testPageUuid to listOf(testBlock), otherPageUuid to listOf(otherBlock))
        )
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()

        vm.navigateTo(Screen.PageView(testPage))
        vm.requestEditBlock(testBlockUuid)

        // Deferred conflict: an external change to a *different* page while on testPage.
        graphLoader.emitExternalFileChange(otherFilePath, "- other disk content")
        // Currently-open conflict: an external change to the page being actively edited.
        graphLoader.emitExternalFileChange(testFilePath, "- disk content")

        val state = vm.uiState.value
        assertNotNull(state.diskConflict)
        assertEquals(testFilePath, state.diskConflict.filePath)
        assertNotNull(state.pendingConflicts[otherFilePath])

        // AppState.pendingConflictFilePaths is the single source of truth App.kt's LeftSidebar
        // wiring reads from — asserting through it here (not a hand-rolled duplicate expression)
        // means this test and production wiring can never silently drift apart.
        assertTrue(otherFilePath in state.pendingConflictFilePaths)
        assertTrue(testFilePath in state.pendingConflictFilePaths)
    }

    // ─── Story 6.1.2: stale-content re-validation ────────────────────────────

    @Test
    fun latest_pending_content_wins_over_an_earlier_superseded_external_change() = runBlocking {
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()

        graphLoader.emitExternalFileChange(testFilePath, "- first version")
        graphLoader.emitExternalFileChange(testFilePath, "- second version")

        vm.navigateTo(Screen.PageView(testPage))

        assertEquals("- second version", vm.uiState.value.diskConflict?.diskContent)
    }

    // ─── Story 6.1.5: collector survives malformed disk content ─────────────

    @Test
    fun collector_survives_a_parse_failure_and_keeps_processing_subsequent_changes() = runBlocking {
        // MarkdownParser.parsePage() is documented (see StelekitViewModel.observeExternalFileChanges)
        // as rethrowing on malformed content by design. Most malformed markdown is absorbed
        // silently by the permissive block/inline parsers and never reaches a throw — but
        // BlockParser's ordered-list detection is a genuine exception: ORDERED_LIST_EXTRACT_REGEX
        // ("^(\\d+)\\.$") accepts an unbounded run of digits before calling `.toInt()`, so an
        // ordered-list marker whose digit run exceeds Int.MAX_VALUE throws a real
        // NumberFormatException out of parsePage(). This drives that exact failure through the
        // live ViewModel collector to prove the standing observeExternalFileChanges coroutine
        // degrades gracefully (diskBlockContent = null, DiskConflict/PendingConflict still
        // created) instead of dying and silently ending conflict detection for the rest of the
        // session.
        val pageRepo = FakePageRepository(listOf(testPage))
        val blockRepo = FakeBlockRepository(mapOf(testPageUuid to listOf(testBlock)))
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val vm = makeViewModel(pageRepo = pageRepo, blockRepo = blockRepo, graphLoader = graphLoader)
        vm.startAutoSave()
        vm.navigateTo(Screen.PageView(testPage))
        vm.requestEditBlock(testBlockUuid)

        val malformedContent = "99999999999999999999999999999999999999. overflow ordinal marker\n"
        graphLoader.emitExternalFileChange(testFilePath, malformedContent)

        val conflict = vm.uiState.value.diskConflict
        assertNotNull(conflict, "Conflict must still be surfaced even though parsing the disk content threw")
        assertEquals(malformedContent, conflict.diskContent)
        assertNull(
            conflict.diskBlockContent,
            "diskBlockContent must degrade to null, not propagate the parse failure to the caller"
        )

        // Prove the collector coroutine is still alive by feeding it a well-formed change —
        // if the try/catch around parsePage() were missing, the exception above would have
        // killed this standing collector and this second event would never be processed.
        graphLoader.emitExternalFileChange(testFilePath, "- well formed content")
        val secondConflict = vm.uiState.value.diskConflict
        assertNotNull(secondConflict)
        assertEquals("- well formed content", secondConflict.diskContent)
        assertEquals("well formed content", secondConflict.diskBlockContent)
    }
}
