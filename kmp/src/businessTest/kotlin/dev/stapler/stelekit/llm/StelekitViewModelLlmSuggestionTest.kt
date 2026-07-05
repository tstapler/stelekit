package dev.stapler.stelekit.llm

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.ExternalFileChange
import dev.stapler.stelekit.db.GraphLoaderPort
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.db.WriteError
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.SectionId
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import dev.stapler.stelekit.vault.CryptoLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Story 7.3 — visibility/accept/reject wiring, race-guard and multi-graph guards.
 * Mirrors the existing JournalMergeReady test coverage shape.
 */
class StelekitViewModelLlmSuggestionTest {

    // ─── Local stubs (mirrors DeviceProfileTest.kt's pattern) ───────────────────

    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String): String =
            store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private open class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp/graph"
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

    private class StubGraphLoaderPort : GraphLoaderPort {
        override fun setActivePageUuids(uuids: StateFlow<Set<String>>?) {}
        override fun setUnsavedPageUuids(uuids: StateFlow<Set<String>>?) {}
        override val externalFileChanges: SharedFlow<ExternalFileChange> = MutableSharedFlow()
        override val writeErrors: SharedFlow<WriteError> = MutableSharedFlow()
        override fun setCryptoLayer(layer: CryptoLayer?) {}
        override fun closeAndClearCryptoLayer() {}
        override suspend fun loadGraphProgressive(
            graphPath: String,
            immediateJournalCount: Int,
            onProgress: (String) -> Unit,
            onPhase1Complete: () -> Unit,
            onFullyLoaded: () -> Unit,
        ) {}
        override suspend fun indexRemainingPages(onProgress: (String) -> Unit) {}
        override suspend fun loadPageByName(pageName: dev.stapler.stelekit.model.PageName): Page? = null
        override suspend fun loadFullPage(pageUuid: String, force: Boolean) {}
        override fun cancelBackgroundWork() {}
        override suspend fun parseAndSavePage(
            filePath: FilePath,
            content: String,
            mode: ParseMode,
            priority: dev.stapler.stelekit.db.DatabaseWriteActor.Priority,
        ) {}
    }

    private class RecordingGraphWriterPort(
        /** When set, [savePage] returns this failure instead of recording success — models C3's write-failure propagation. */
        private val failWith: DomainError? = null,
    ) : GraphWriterPort {
        var saveCallCount = 0
        override fun setCryptoLayer(layer: CryptoLayer?) {}
        override fun closeAndClearCryptoLayer() {}
        override fun startAutoSave(debounceMs: Long) {}
        override fun stopAutoSave() {}
        override suspend fun flush() {}
        override suspend fun renamePage(page: Page, newName: String, graphPath: String) = true
        override suspend fun savePage(page: Page, blocks: List<Block>, graphPath: String): Either<DomainError, Unit> {
            saveCallCount++
            return failWith?.left() ?: Unit.right()
        }
        override suspend fun deletePage(page: Page) = true
        override suspend fun movePageToSection(
            page: Page,
            newSectionId: SectionId,
            newPathPrefix: String,
        ): Either<DomainError, Page> = page.copy(sectionId = newSectionId).right()
    }

    private fun makeViewModel(
        graphWriter: GraphWriterPort = RecordingGraphWriterPort(),
        activeGraphId: String? = "graph-1",
        scope: CoroutineScope,
        inbox: LlmSuggestionInbox = LlmSuggestionInbox(),
        pageRepository: InMemoryPageRepository = InMemoryPageRepository(),
        blockRepository: InMemoryBlockRepository = InMemoryBlockRepository(),
    ): StelekitViewModel =
        StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = StubFileSystem(),
                pageRepository = pageRepository,
                blockRepository = blockRepository,
                searchRepository = InMemorySearchRepository(),
                graphLoader = StubGraphLoaderPort(),
                graphWriter = graphWriter,
                platformSettings = StubSettings(),
                scope = scope,
                activeGraphIdProvider = { activeGraphId },
                llmSuggestionInbox = inbox,
            )
        )

    private fun suggestion(id: String = "sugg-1", graphId: String = "graph-1") =
        PendingLlmSuggestion.BlockEdit(
            id = id, graphId = graphId, sourceProviderId = "anthropic",
            proposedAtEpochMs = 1L, rationale = null,
            pageUuid = "page-1", blockUuid = "block-1",
            currentContentSnapshot = "original", proposedContent = "updated",
        )

    private val now = Clock.System.now()

    private fun testPage(uuid: String = "page-1") = Page(
        uuid = PageUuid(uuid),
        name = "Test Page",
        createdAt = now,
        updatedAt = now,
    )

    private fun testBlock(uuid: String = "block-1", pageUuid: String = "page-1", content: String = "original") = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = content,
        position = "a0",
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun reviewVisible_should_BecomeTrue_When_PendingForCurrentGraphBecomesNonEmpty() = runTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val inbox = LlmSuggestionInbox()
        val vm = makeViewModel(activeGraphId = "graph-1", scope = scope, inbox = inbox)

        assertFalse(vm.uiState.value.llmSuggestionReviewVisible)

        inbox.propose(suggestion(graphId = "graph-1"))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.llmSuggestionReviewVisible)
        scope.cancel()
    }

    @Test
    fun rejectLlmSuggestion_should_RemoveFromInbox_NoConfirmation() = runTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val inbox = LlmSuggestionInbox()
        inbox.propose(suggestion(id = "sugg-1", graphId = "graph-1"))
        val vm = makeViewModel(activeGraphId = "graph-1", scope = scope, inbox = inbox)

        vm.rejectLlmSuggestion("sugg-1")
        advanceUntilIdle()

        assertTrue(inbox.pending.value.isEmpty())
        scope.cancel()
    }

    @Test
    fun acceptLlmSuggestion_should_NoOp_When_SuggestionAlreadyResolved() = runTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val writer = RecordingGraphWriterPort()
        val inbox = LlmSuggestionInbox()
        val vm = makeViewModel(graphWriter = writer, activeGraphId = "graph-1", scope = scope, inbox = inbox)

        // Never proposed -> already resolved/expired from the ViewModel's perspective
        vm.acceptLlmSuggestion("never-proposed")
        advanceUntilIdle()

        assertEquals(0, writer.saveCallCount)
        scope.cancel()
    }

    @Test
    fun acceptLlmSuggestion_should_SurfaceError_NotRemove_When_GraphIdMismatchesCurrentGraph() = runTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val writer = RecordingGraphWriterPort()
        val inbox = LlmSuggestionInbox()
        inbox.propose(suggestion(id = "sugg-1", graphId = "graph-OTHER"))
        val vm = makeViewModel(graphWriter = writer, activeGraphId = "graph-1", scope = scope, inbox = inbox)

        vm.acceptLlmSuggestion("sugg-1")
        advanceUntilIdle()

        assertEquals(0, writer.saveCallCount)
        // Still present — not removed, so the user can switch back and review it.
        assertTrue(inbox.pending.value.containsKey("sugg-1"))
        scope.cancel()
    }

    // ─── MA9: C3 write-failure propagation coverage ─────────────────────────

    @Test
    fun acceptLlmSuggestion_should_RemoveFromInbox_NoErrorSnackbar_When_WriteSucceeds() = runTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val writer = RecordingGraphWriterPort()
        val inbox = LlmSuggestionInbox()
        val pageRepository = InMemoryPageRepository()
        val blockRepository = InMemoryBlockRepository()
        pageRepository.savePage(testPage())
        blockRepository.saveBlock(testBlock())
        inbox.propose(suggestion(id = "sugg-1", graphId = "graph-1"))
        val vm = makeViewModel(
            graphWriter = writer,
            activeGraphId = "graph-1",
            scope = scope,
            inbox = inbox,
            pageRepository = pageRepository,
            blockRepository = blockRepository,
        )

        vm.acceptLlmSuggestion("sugg-1")
        advanceUntilIdle()

        assertEquals(1, writer.saveCallCount, "a successful accept must write exactly once")
        assertFalse(inbox.pending.value.containsKey("sugg-1"), "suggestion must be removed from the inbox on success")

        // No error snackbar should have been queued — assert nothing is buffered.
        val snackbar = withTimeoutOrNull(200) { vm.snackbarEvents.first() }
        assertEquals(null, snackbar, "a successful accept must not surface an error snackbar")
        scope.cancel()
    }

    @Test
    fun acceptLlmSuggestion_should_SurfaceErrorSnackbar_When_WriteFails() = runTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val writer = RecordingGraphWriterPort(failWith = DomainError.DatabaseError.WriteFailed("disk full"))
        val inbox = LlmSuggestionInbox()
        val pageRepository = InMemoryPageRepository()
        val blockRepository = InMemoryBlockRepository()
        pageRepository.savePage(testPage())
        blockRepository.saveBlock(testBlock())
        inbox.propose(suggestion(id = "sugg-1", graphId = "graph-1"))
        val vm = makeViewModel(
            graphWriter = writer,
            activeGraphId = "graph-1",
            scope = scope,
            inbox = inbox,
            pageRepository = pageRepository,
            blockRepository = blockRepository,
        )

        vm.acceptLlmSuggestion("sugg-1")
        advanceUntilIdle()

        assertEquals(1, writer.saveCallCount)
        // ADR-012's optimistic-removal ordering is an intentional, accepted design tradeoff
        // (mirrors the git-merge-review pattern) — the suggestion is removed from the inbox
        // BEFORE the write completes, even on failure. This test locks in that ordering; it
        // does not change it.
        assertFalse(inbox.pending.value.containsKey("sugg-1"), "optimistic removal happens regardless of write outcome")

        val snackbar = withTimeout(1_000) { vm.snackbarEvents.first() }
        assertEquals("disk full", snackbar, "a failed accept must surface the write failure via a snackbar")
        scope.cancel()
    }
}
