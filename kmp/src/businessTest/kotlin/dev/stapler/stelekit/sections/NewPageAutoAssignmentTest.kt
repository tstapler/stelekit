// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

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
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Story 6.8 — New-page auto-assignment tests (FR-13).
 *
 * Verifies that [StelekitViewModel.navigateToPageByName] creates a new page with:
 * - [Page.sectionId] = the current [AppState.defaultSection] when it is non-empty and the
 *   page is not a journal (plain wiki-link creation flow).
 * - [Page.sectionId] = "" when [AppState.defaultSection] is empty (global default).
 *
 * Note: the backing file path (`pages/acme-work/Meeting Notes.md`) is NOT tested here because
 * [GraphWriter.getPageFilePath] does not yet use sectionId — that gap is tracked separately.
 */
class NewPageAutoAssignmentTest {

    // ─── Stubs ────────────────────────────────────────────────────────────────

    private class StubSettings(defaultSection: String = "") : Settings {
        private val store = mutableMapOf("defaultSection" to defaultSection)
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
    }

    private open class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = ""
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
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
            graphPath: String, immediateJournalCount: Int,
            onProgress: (String) -> Unit, onPhase1Complete: () -> Unit, onFullyLoaded: () -> Unit,
        ) {}
        override suspend fun indexRemainingPages(onProgress: (String) -> Unit) {}
        override suspend fun loadPageByName(pageName: PageName): Page? = null
        override suspend fun loadFullPage(pageUuid: String, force: Boolean) {}
        override fun cancelBackgroundWork() {}
        override suspend fun parseAndSavePage(
            filePath: FilePath, content: String, mode: ParseMode,
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
        override suspend fun savePage(page: Page, blocks: List<Block>, graphPath: String) {}
        override suspend fun deletePage(page: Page) = true
        override suspend fun movePageToSection(
            page: Page, newSectionId: String, newPathPrefix: String,
        ): Either<DomainError, Page> = page.copy(sectionId = newSectionId).right()
    }

    /**
     * Creates a [StelekitViewModel] with the given [defaultSection] pre-populated in settings.
     * The VM scope uses [UnconfinedTestDispatcher] so that coroutines launched via
     * [StelekitViewModel.navigateToPageByName] execute eagerly — the page is saved before
     * the call returns.
     *
     * Returns the VM, the page repository (for assertions), and the scope (for cleanup).
     */
    private fun makeVm(
        defaultSection: String,
        dispatcher: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): Triple<StelekitViewModel, InMemoryPageRepository, CoroutineScope> {
        val pageRepo = InMemoryPageRepository()
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(dispatcher))
        val vm = StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = StubFileSystem(),
                pageRepository = pageRepo,
                blockRepository = InMemoryBlockRepository(),
                searchRepository = InMemorySearchRepository(),
                graphLoader = StubGraphLoaderPort(),
                graphWriter = StubGraphWriterPort(),
                platformSettings = StubSettings(defaultSection = defaultSection),
                scope = vmScope,
            )
        )
        return Triple(vm, pageRepo, vmScope)
    }

    // ── TC-6.8-A: non-empty defaultSection → page.sectionId = defaultSection ──

    @Test
    fun `navigateToPageByName creates page with sectionId matching defaultSection`() = runTest {
        val (vm, pageRepo, vmScope) = makeVm("acme-work", testScheduler)
        try {
            vm.navigateToPageByName("Meeting Notes")
            // UnconfinedTestDispatcher runs the launched coroutine eagerly before returning
            val page = pageRepo.getPageByName("Meeting Notes").first().getOrNull()
            assertNotNull(page, "Page must be created when it does not exist")
            assertEquals(
                "acme-work", page.sectionId,
                "New page must inherit defaultSection as sectionId",
            )
        } finally {
            vmScope.cancel()
        }
    }

    // ── TC-6.8-B: empty defaultSection → page.sectionId = "" ─────────────────

    @Test
    fun `navigateToPageByName creates page with empty sectionId when defaultSection is empty`() = runTest {
        val (vm, pageRepo, vmScope) = makeVm("", testScheduler)
        try {
            vm.navigateToPageByName("Meeting Notes")
            val page = pageRepo.getPageByName("Meeting Notes").first().getOrNull()
            assertNotNull(page, "Page must be created when it does not exist")
            assertEquals(
                "", page.sectionId,
                "New page must have empty sectionId when defaultSection is not set",
            )
        } finally {
            vmScope.cancel()
        }
    }
}
