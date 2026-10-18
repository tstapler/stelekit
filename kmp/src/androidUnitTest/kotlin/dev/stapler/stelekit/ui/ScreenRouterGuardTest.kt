// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemoryPropertyRepository
import dev.stapler.stelekit.repository.InMemoryReferenceRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.ui.screens.AllPagesViewModel
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import dev.stapler.stelekit.ui.screens.LibraryStatsViewModel
import dev.stapler.stelekit.ui.screens.SearchViewModel
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotEquals

/**
 * Covers the two early-return guards at the top of [ScreenRouter]: a fatal error short-circuits
 * to the error screen, and an in-progress load short-circuits to the loading overlay — both
 * before any screen content (or `repos`) is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScreenRouterGuardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class NoopSettings : Settings {
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) {}
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun putString(key: String, value: String) {}
        override fun containsKey(key: String) = false
    }

    private class NoopFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/fake/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private class Harness(
        val viewModel: StelekitViewModel,
        val repos: RepositorySet,
        val blockStateManager: BlockStateManager,
        val journals: JournalsViewModel,
        val allPages: AllPagesViewModel,
        val stats: LibraryStatsViewModel,
        val search: SearchViewModel,
        val writer: GraphWriter,
    )

    private fun harness(): Harness {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()).also { scopes += it }
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val searchRepo = InMemorySearchRepository(pageRepo, blockRepo)
        val fs = NoopFileSystem()
        val loader = GraphLoader(fs, pageRepo, blockRepo)
        val writer = GraphWriter(fs)
        val journalService = JournalService(pageRepo, blockRepo)
        val vm = StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fs,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = searchRepo,
                graphLoader = loader,
                graphWriter = writer,
                platformSettings = NoopSettings(),
                scope = scope,
                journalService = journalService,
            )
        )
        val bsm = BlockStateManager(blockRepository = blockRepo, graphLoader = loader, scope = scope)
        return Harness(
            viewModel = vm,
            repos = RepositorySet(
                blockRepository = blockRepo,
                pageRepository = pageRepo,
                propertyRepository = InMemoryPropertyRepository(),
                referenceRepository = InMemoryReferenceRepository(),
                searchRepository = searchRepo,
                journalService = journalService,
            ),
            blockStateManager = bsm,
            journals = JournalsViewModel(journalService, bsm, scope),
            allPages = AllPagesViewModel(pageRepo, blockRepo, scope),
            stats = LibraryStatsViewModel(scope = scope),
            search = SearchViewModel(searchRepo, scope, pageRepo),
            writer = writer,
        )
    }

    private fun show(h: Harness, appState: AppState) {
        composeTestRule.setContent {
            MaterialTheme {
                ScreenRouter(
                    screen = Screen.Journals,
                    repos = h.repos,
                    blockStateManager = h.blockStateManager,
                    journalsViewModel = h.journals,
                    allPagesViewModel = h.allPages,
                    libraryStatsViewModel = h.stats,
                    viewModel = h.viewModel,
                    searchViewModel = h.search,
                    notificationManager = NotificationManager(),
                    appState = appState,
                    graphWriter = h.writer,
                )
            }
        }
    }

    @Test
    fun `fatalError renders the error screen with its message`() {
        show(harness(), AppState(fatalError = "DB crash"))
        composeTestRule.onNodeWithText("Something went wrong").assertIsDisplayed()
        composeTestRule.onNodeWithText("DB crash").assertIsDisplayed()
    }

    @Test
    fun `fatalError wins over isLoading`() {
        show(harness(), AppState(fatalError = "boom", isLoading = true, statusMessage = "Loading X"))
        composeTestRule.onNodeWithText("Something went wrong").assertIsDisplayed()
    }

    @Test
    fun `Retry on the error screen reloads the graph`() {
        val h = harness()
        show(h, AppState(fatalError = "DB crash", currentGraphPath = "/fake/retry"))
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.waitUntil(5_000) { h.viewModel.uiState.value.statusMessage != "Ready" }
        assertNotEquals("Ready", h.viewModel.uiState.value.statusMessage)
    }

    @Test
    fun `Dismiss on the error screen is wired to the view model`() {
        val h = harness()
        show(h, AppState(fatalError = "DB crash"))
        composeTestRule.onNodeWithText("Dismiss").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `isLoading renders the loading overlay with the status message`() {
        show(harness(), AppState(isLoading = true, statusMessage = "Indexing pages"))
        composeTestRule.onNodeWithText("Indexing pages").assertIsDisplayed()
    }

    @Test
    fun `isLoading with blank status falls back to default message`() {
        show(harness(), AppState(isLoading = true, statusMessage = ""))
        composeTestRule.onNodeWithText("Loading your notes…").assertIsDisplayed()
    }
}
