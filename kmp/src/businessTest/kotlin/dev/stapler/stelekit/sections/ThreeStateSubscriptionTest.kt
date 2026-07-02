package dev.stapler.stelekit.sections

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.ExternalFileChange
import dev.stapler.stelekit.db.GraphLoaderPort
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.db.WriteError
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import dev.stapler.stelekit.vault.CryptoLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * Story 6.7 — Three-state section subscription tests (FR-14).
 *
 * The three states are:
 * - ACTIVE  — pages on disk, visible in UI
 * - HIDDEN  — pages on disk, hidden from UI (excluded from journal view)
 * - REMOVED — pages removed from this device (git sparse-checkout, not yet implemented)
 *
 * These tests verify state transitions through [StelekitViewModel.setSectionState] and
 * confirm that [JournalService.getJournalPagesBySections] respects active-section filtering.
 *
 * Note on unimplemented operations:
 * - File/directory deletion for REMOVED state (Story 2.6) is not yet implemented.
 * - Git sparse-checkout mutations (Stories 2.6–2.7) are not yet implemented.
 * - These tests cover only the state-persistence and journal-filter aspects that are
 *   implemented today. TODOs are marked where the missing behaviour lives.
 */
class ThreeStateSubscriptionTest {

    // ─── Local stubs ──────────────────────────────────────────────────────────

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
        override suspend fun loadPageByName(pageName: PageName): Page? = null
        override suspend fun loadFullPage(pageUuid: String, force: Boolean) {}
        override fun cancelBackgroundWork() {}
        override suspend fun parseAndSavePage(
            filePath: FilePath,
            content: String,
            mode: ParseMode,
            priority: DatabaseWriteActor.Priority,
        ) {}
    }

    private class StubGraphWriterPort : GraphWriterPort {
        override fun setCryptoLayer(layer: CryptoLayer?) {}
        override fun closeAndClearCryptoLayer() {}
        override fun startAutoSave(debounceMs: Long) {}
        override fun stopAutoSave() {}
        override suspend fun flush() {}
        override suspend fun renamePage(page: Page, newName: String, graphPath: String) = true
        override suspend fun savePage(page: Page, blocks: List<Block>, graphPath: String): Either<DomainError, Unit> = Unit.right()
        override suspend fun deletePage(page: Page) = true
        override suspend fun movePageToSection(
            page: Page,
            newSectionId: String,
            newPathPrefix: String,
        ): Either<DomainError, Page> = page.copy(sectionId = newSectionId).right()
    }

    private fun makeDate(dateStr: String): LocalDate = LocalDate.parse(dateStr)

    private fun journalPage(sectionId: String, date: LocalDate = makeDate("2026-01-15")): Page {
        val now = date.atStartOfDayIn(TimeZone.UTC)
        return Page(
            uuid = PageUuid(dev.stapler.stelekit.util.UuidGenerator.generateV7()),
            name = date.toString(),
            createdAt = now,
            updatedAt = now,
            isJournal = true,
            journalDate = date,
            sectionId = sectionId,
        )
    }

    private fun makeViewModel(
        settings: Settings = StubSettings(),
        pageRepo: InMemoryPageRepository = InMemoryPageRepository(),
        blockRepo: InMemoryBlockRepository = InMemoryBlockRepository(),
    ): StelekitViewModel = StelekitViewModel(
        StelekitViewModelDependencies(
            fileSystem = StubFileSystem(),
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            searchRepository = InMemorySearchRepository(),
            graphLoader = StubGraphLoaderPort(),
            graphWriter = StubGraphWriterPort(),
            platformSettings = settings,
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        )
    )

    // ─── ACTIVE → HIDDEN ─────────────────────────────────────────────────────

    @Test
    fun `setSectionState HIDDEN updates uiState`() {
        val vm = makeViewModel()
        vm.setSectionState("acme-work", SectionState.ACTIVE)

        vm.setSectionState("acme-work", SectionState.HIDDEN)

        assertEquals(SectionState.HIDDEN, vm.uiState.value.currentSectionStates["acme-work"])
    }

    @Test
    fun `setSectionState HIDDEN persists to settings`() {
        val settings = StubSettings()
        val vm = makeViewModel(settings)

        vm.setSectionState("acme-work", SectionState.HIDDEN)

        val reloaded = settings.getSectionStates()
        assertEquals(SectionState.HIDDEN, reloaded["acme-work"])
    }

    @Test
    fun `HIDDEN section pages absent from journal filter results`() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        // Two journal pages: one global (""), one in acme-work section
        @OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)
        pageRepo.savePage(journalPage(""))
        @OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)
        pageRepo.savePage(journalPage("acme-work", makeDate("2026-01-14")))

        val journalService = JournalService(pageRepo, blockRepo)

        // Only "personal" is active; acme-work is implicitly hidden (not in activeSectionIds)
        val result = journalService
            .getJournalPagesBySections(activeSectionIds = listOf("personal"), limit = 10, offset = 0)
            .first()
            .getOrNull() ?: error("Expected Right")

        // Global section ("") must be included; acme-work must be excluded
        assertTrue(result.any { it.sectionId == "" },
            "Global journal page must be included")
        assertTrue(result.none { it.sectionId == "acme-work" },
            "HIDDEN section (acme-work) must be excluded from journal results")
    }

    @Test
    fun `ACTIVE section pages present in journal filter results`() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        @OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)
        pageRepo.savePage(journalPage("acme-work"))

        val journalService = JournalService(pageRepo, blockRepo)

        val result = journalService
            .getJournalPagesBySections(activeSectionIds = listOf("acme-work"), limit = 10, offset = 0)
            .first()
            .getOrNull() ?: error("Expected Right")

        assertTrue(result.any { it.sectionId == "acme-work" },
            "ACTIVE section pages must appear in journal results")
    }

    // ─── ACTIVE → REMOVED ────────────────────────────────────────────────────

    @Test
    fun `setSectionState REMOVED updates uiState`() {
        val vm = makeViewModel()
        vm.setSectionState("acme-work", SectionState.ACTIVE)

        vm.setSectionState("acme-work", SectionState.REMOVED)

        assertEquals(SectionState.REMOVED, vm.uiState.value.currentSectionStates["acme-work"])
    }

    @Test
    fun `setSectionState REMOVED persists to settings`() {
        val settings = StubSettings()
        val vm = makeViewModel(settings)

        vm.setSectionState("acme-work", SectionState.REMOVED)

        assertEquals(SectionState.REMOVED, settings.getSectionStates()["acme-work"])
    }

    // TODO(Story 2.6): When REMOVED, the section's directory should be deleted from disk and
    //   git sparse-checkout updated. Neither is implemented yet (FileSystem has no deleteDirectory,
    //   GitSyncService has no removeSparseCheckoutCone). Tests will be added when those land.

    // ─── REMOVED → ACTIVE ────────────────────────────────────────────────────

    @Test
    fun `setSectionState ACTIVE after REMOVED updates uiState`() {
        val vm = makeViewModel()
        vm.setSectionState("acme-work", SectionState.REMOVED)

        vm.setSectionState("acme-work", SectionState.ACTIVE)

        assertEquals(SectionState.ACTIVE, vm.uiState.value.currentSectionStates["acme-work"])
    }

    @Test
    fun `setSectionState ACTIVE after REMOVED persists to settings`() {
        val settings = StubSettings()
        val vm = makeViewModel(settings)
        vm.setSectionState("acme-work", SectionState.REMOVED)

        vm.setSectionState("acme-work", SectionState.ACTIVE)

        assertEquals(SectionState.ACTIVE, settings.getSectionStates()["acme-work"])
    }

    @Test
    fun `reactivated section pages appear in journal filter`() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        // Page was from the acme-work section and is now re-activated
        @OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)
        pageRepo.savePage(journalPage("acme-work"))

        val journalService = JournalService(pageRepo, blockRepo)

        // Simulate re-activation by including "acme-work" in activeSectionIds
        val result = journalService
            .getJournalPagesBySections(activeSectionIds = listOf("acme-work"), limit = 10, offset = 0)
            .first()
            .getOrNull() ?: error("Expected Right")

        assertTrue(result.any { it.sectionId == "acme-work" },
            "Re-activated section pages must appear in journal results")
    }

    // TODO(Story 2.7): When REMOVED → ACTIVE, the section cone should be added back to git
    //   sparse-checkout so the directory syncs. Tests will be added when GitSyncService
    //   gains addSparseCheckoutCone.

    // ─── Independent section states don't interfere ───────────────────────────

    @Test
    fun `setSectionState for one section does not affect other sections`() {
        val vm = makeViewModel()
        vm.setSectionStates(mapOf(
            "acme-work" to SectionState.ACTIVE,
            "personal" to SectionState.ACTIVE,
        ))

        vm.setSectionState("acme-work", SectionState.HIDDEN)

        assertEquals(SectionState.HIDDEN, vm.uiState.value.currentSectionStates["acme-work"])
        assertEquals(SectionState.ACTIVE, vm.uiState.value.currentSectionStates["personal"],
            "personal must remain ACTIVE when only acme-work is toggled")
    }

    @Test
    fun `journal filter with no activeSectionIds returns all journal pages`() = runBlocking {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()

        @OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)
        pageRepo.savePage(journalPage("acme-work"))
        @OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)
        pageRepo.savePage(journalPage("personal", makeDate("2026-01-14")))

        val journalService = JournalService(pageRepo, blockRepo)

        // Empty activeSectionIds → fallback to getJournalPages (no section filter)
        val result = journalService
            .getJournalPagesBySections(activeSectionIds = emptyList(), limit = 10, offset = 0)
            .first()
            .getOrNull() ?: error("Expected Right")

        assertEquals(2, result.size, "All journal pages returned when no section filter applied")
    }
}
