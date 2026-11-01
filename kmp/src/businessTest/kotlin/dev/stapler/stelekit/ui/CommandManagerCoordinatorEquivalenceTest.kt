// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.ExternalFileChange
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphLoaderPort
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.db.WriteError
import dev.stapler.stelekit.editor.commands.CommandContext
import dev.stapler.stelekit.editor.commands.CommandManager
import dev.stapler.stelekit.editor.commands.CommandResult
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.model.SectionId
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.vault.CryptoLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 7.4a: [CommandManagerCoordinator] must behave exactly like the command path that
 * `StelekitViewModel` carried inline before adopting it (`block.toggle-todo` intercepted, every
 * other id delegated to [CommandManager]). Each case runs the ViewModel and a standalone
 * coordinator side by side; "unknown id" and "available commands" are also compared against a
 * bare [CommandManager], the coordinator's delegate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandManagerCoordinatorEquivalenceTest {

    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class StubGraphLoaderPort : GraphLoaderPort {
        override fun setActivePageUuids(uuids: StateFlow<Set<String>>?) {}
        override fun setUnsavedPageUuids(uuids: StateFlow<Set<String>>?) {}
        override val externalFileChanges: SharedFlow<ExternalFileChange> = MutableSharedFlow()
        override val writeErrors: SharedFlow<WriteError> = MutableSharedFlow()
        override fun setCryptoLayer(layer: CryptoLayer?) {}
        override fun closeAndClearCryptoLayer() {}
        override suspend fun loadGraphProgressive(
            notesPath: EffectiveNotesPath, immediateJournalCount: Int,
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
        override suspend fun renamePage(page: Page, newName: String, graphPath: EffectiveNotesPath) = true
        override suspend fun savePage(page: Page, blocks: List<Block>, graphPath: EffectiveNotesPath): Either<DomainError, Unit> = Unit.right()
        override suspend fun deletePage(page: Page) = true
        override suspend fun movePageToSection(
            page: Page, newSectionId: SectionId, newPathPrefix: String,
        ): Either<DomainError, Page> = page.copy(sectionId = newSectionId).right()
    }

    private class Rig(val scope: CoroutineScope, val viewModel: StelekitViewModel, val coordinator: CommandManagerCoordinator)

    private fun TestScope.rig(withBlockManager: Boolean): Rig {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val manager = if (withBlockManager) {
            BlockStateManager(
                blockRepo, GraphLoader(FakeFileSystem(), pageRepo, blockRepo), scope,
                graphWriter = GraphWriter(FakeFileSystem()), pageRepository = pageRepo,
                graphPathProvider = { EffectiveNotesPath("/fake/graph") },
            )
        } else {
            null
        }
        val viewModel = StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = FakeFileSystem(), pageRepository = pageRepo, blockRepository = blockRepo,
                searchRepository = InMemorySearchRepository(), graphLoader = StubGraphLoaderPort(),
                graphWriter = StubGraphWriterPort(), platformSettings = StubSettings(), scope = scope,
                blockStateManager = manager,
            ),
        )
        return Rig(scope, viewModel, CommandManagerCoordinator(scope, null) { manager })
    }

    @Test
    fun toggleTodo_withoutBlockManager_errorsIdentically() = runTest {
        val r = rig(withBlockManager = false)
        try {
            val expected = CommandResult.Error(message = "No block is currently being edited")
            assertEquals(expected, r.viewModel.executeCommand("block.toggle-todo"))
            assertEquals(expected, r.coordinator.executeCommand("block.toggle-todo"))
        } finally {
            r.scope.cancel()
        }
    }

    @Test
    fun toggleTodo_withBlockManager_requestsToggleAndSucceedsIdentically() = runTest {
        val r = rig(withBlockManager = true)
        try {
            val expected = CommandResult.Success(message = "Todo status toggled")
            assertEquals(expected, r.viewModel.executeCommand("block.toggle-todo"))
            assertEquals(expected, r.coordinator.executeCommand("block.toggle-todo"))
        } finally {
            r.scope.cancel()
        }
    }

    @Test
    fun unknownId_delegatesToCommandManager() = runTest {
        val r = rig(withBlockManager = false)
        try {
            val bare = CommandManager.create(r.scope)
            val expected = bare.executeCommand("no.such.command", CommandContext())
            assertEquals(expected, r.viewModel.executeCommand("no.such.command"))
            assertEquals(expected, r.coordinator.executeCommand("no.such.command"))
        } finally {
            r.scope.cancel()
        }
    }

    @Test
    fun availableCommands_matchBareCommandManager() = runTest {
        val r = rig(withBlockManager = false)
        try {
            val context = CommandContext()
            val expected = CommandManager.create(r.scope).getAvailableCommands(context).map { it.id }.sorted()
            assertTrue(expected.isNotEmpty(), "the bare manager must expose commands for this comparison to mean anything")
            assertEquals(expected, r.coordinator.getAvailableCommands(context).map { it.id }.sorted())
            assertEquals(expected, r.viewModel.getAvailableCommands().map { it.id }.sorted())
        } finally {
            r.scope.cancel()
        }
    }

    @Test
    fun coordinatorToggleTodo_emitsOnTheManagersToggleFlow() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val blockRepo = InMemoryBlockRepository()
            val pageRepo = InMemoryPageRepository()
            val manager = BlockStateManager(
                blockRepo, GraphLoader(FakeFileSystem(), pageRepo, blockRepo), scope,
                graphPathProvider = { EffectiveNotesPath("/fake/graph") },
            )
            val events = mutableListOf<Unit>()
            val collector = scope.launch { manager.todoToggleEvents.collect { events += it } }

            CommandManagerCoordinator(scope, null) { manager }.executeCommand("block.toggle-todo")

            assertEquals(1, events.size)
            collector.cancel()
        } finally {
            scope.cancel()
        }
    }
}
