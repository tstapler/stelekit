// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.performance.FrameMetric
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.ui.screens.SearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders [GraphDialogLayer] under each AppState that opens a host, so the per-host gating
 * (the `shouldShow*` predicates) is exercised through real composition, not only in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GraphDialogLayerHostsTest {

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

    private val graph = GraphInfo(id = GraphId("g1"), path = "/g1", displayName = "G1", addedAt = 0L)
    private val manifest = SectionManifest()
    private val conflict = requireNotNull(
        DiskConflict.ifReal(
            pageUuid = "p", pageName = "P", filePath = "/g1/pages/p.md", editingBlockUuid = null,
            localContent = "local edit", diskContent = "disk edit", diskBlockContent = "disk edit",
        )
    )

    private fun show(appState: AppState) {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()).also { scopes += it }
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val searchRepo = InMemorySearchRepository(pageRepo, blockRepo)
        val fs = NoopFileSystem()
        val vm = StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fs,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = searchRepo,
                graphLoader = GraphLoader(fs, pageRepo, blockRepo),
                graphWriter = GraphWriter(fs),
                platformSettings = NoopSettings(),
                scope = scope,
                journalService = JournalService(pageRepo, blockRepo),
            )
        )
        val search = SearchViewModel(searchRepo, scope, pageRepo)
        composeTestRule.setContent {
            MaterialTheme {
                GraphDialogLayer(
                    appState = appState,
                    searchViewModel = search,
                    viewModel = vm,
                    notificationManager = NotificationManager(),
                    fileSystem = fs,
                    frameMetric = MutableStateFlow(FrameMetric()),
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `compact disk conflict dialog renders for a real conflict`() {
        show(AppState(diskConflict = conflict))
        composeTestRule.onNodeWithText("Page modified on disk").assertIsDisplayed()
    }

    @Test
    fun `full-screen disk conflict replaces the compact dialog`() {
        show(AppState(diskConflict = conflict, diskConflictViewFullVisible = true))
        composeTestRule.onNodeWithText("Page modified on disk").assertDoesNotExist()
    }

    @Test
    fun `settings host composes for either settings flag`() {
        show(AppState(settingsVisible = true))
    }

    @Test
    fun `sync review hosts compose without a pending proposal`() {
        show(
            AppState(
                conflictResolutionVisible = true,
                journalMergeReviewVisible = true,
                llmSuggestionReviewVisible = true,
            )
        )
    }

    @Test
    fun `wiki subdir fix host composes for the active graph`() {
        show(
            AppState(
                wikiSubdirFixDialogVisible = true,
                availableGraphs = listOf(graph),
                currentGraphId = "g1",
            )
        )
    }

    @Test
    fun `manifest-gated hosts compose when a manifest is present`() {
        show(
            AppState(
                currentManifest = manifest,
                deviceSetupWizardVisible = true,
                sectionPickerVisible = true,
                sectionQuickToggleVisible = true,
            )
        )
    }
}
