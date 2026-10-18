// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.export.ShareProvider
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.ui.components.ShareContent
import dev.stapler.stelekit.ui.components.ShareDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.time.Clock
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Tall screen: the share column is not scrollable and clips destination buttons on 320x470.
@Config(sdk = [29], qualifiers = "w480dp-h1600dp")
@RunWith(RobolectricTestRunner::class)
class ShareContentTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private class FakeSettings : Settings {
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) {}
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun putString(key: String, value: String) {}
        override fun containsKey(key: String) = false
    }

    private class FakeFileSystem : FileSystem {
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

    private class RecordingShareProvider : ShareProvider {
        val saved = mutableListOf<Triple<String, String, String>>()
        override suspend fun shareText(content: String, mimeType: String) {}
        override suspend fun shareHtml(html: String, plainFallback: String) {}
        override suspend fun saveToFile(
            content: String,
            suggestedName: String,
            extension: String,
        ): Either<DomainError, Boolean> {
            saved += Triple(content, suggestedName, extension)
            return true.right()
        }
    }

    private fun makeViewModel(): StelekitViewModel {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val fileSystem = FakeFileSystem()
        val vmScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        scopes += vmScope
        return StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = InMemorySearchRepository(pageRepo, blockRepo),
                graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo),
                graphWriter = GraphWriter(fileSystem),
                platformSettings = FakeSettings(),
                scope = vmScope,
                journalService = JournalService(pageRepo, blockRepo),
            )
        )
    }

    private val now = Clock.System.now()
    private val samplePage = Page(uuid = PageUuid("00000000-0000-0000-0000-000000000001"), name = "Sample", createdAt = now, updatedAt = now)

    private fun showContent(
        viewModel: StelekitViewModel = makeViewModel(),
        page: Page? = samplePage,
        selected: Set<String> = emptySet(),
        provider: ShareProvider = RecordingShareProvider(),
        isMobile: Boolean = false,
        onDismiss: () -> Unit = {},
    ): StelekitViewModel {
        composeTestRule.setContent {
            MaterialTheme {
                ShareContent(
                    appState = viewModel.uiState.collectAsState().value,
                    viewModel = viewModel,
                    page = page,
                    blocks = emptyList(),
                    selectedBlockUuids = selected,
                    shareProvider = provider,
                    googleAuthManager = null,
                    driveClient = null,
                    isMobile = isMobile,
                    onDismiss = onDismiss,
                )
            }
        }
        return viewModel
    }

    @Test
    fun scopeSelector_should_RenderAllFourOptions() {
        showContent()

        composeTestRule.onNodeWithText("Current Page").assertIsDisplayed()
        composeTestRule.onNodeWithText("Page + Linked Pages").assertIsDisplayed()
        composeTestRule.onNodeWithText("Selected Blocks").assertIsDisplayed()
        composeTestRule.onNodeWithText("Journal Date Range").assertIsDisplayed()
    }

    @Test
    fun scopeSelection_should_UpdateViewModelScope() {
        val vm = showContent()

        composeTestRule.onNodeWithText("Page + Linked Pages").performClick()

        assertEquals(ShareScope.PageAndLinks, vm.uiState.value.shareScope)
    }

    @Test
    fun selectedBlocksScope_should_WarnAndDisableExport_WhenNothingSelected() {
        val vm = makeViewModel()
        vm.setShareScope(ShareScope.SelectedBlocks)
        showContent(viewModel = vm, selected = emptySet())

        composeTestRule.onNodeWithText("No blocks selected. Select blocks in the editor first.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy to Clipboard").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save to File").assertIsNotEnabled()
    }

    @Test
    fun exportButtons_should_BeEnabled_ForCurrentPage_AndDisabledWithoutPage() {
        showContent()
        composeTestRule.onNodeWithText("Copy to Clipboard").assertIsEnabled()
    }

    @Test
    fun exportButtons_should_BeDisabled_WhenNoPage() {
        showContent(page = null)
        composeTestRule.onNodeWithText("Copy to Clipboard").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save to File").assertIsNotEnabled()
    }

    @Test
    fun formatToggle_should_UpdateViewModelFormat() {
        val vm = showContent()
        assertEquals("markdown", vm.uiState.value.shareFormat)

        composeTestRule.onNodeWithText("HTML").performClick()
        assertEquals("html", vm.uiState.value.shareFormat)

        composeTestRule.onNodeWithText("JSON").performClick()
        assertEquals("json", vm.uiState.value.shareFormat)

        composeTestRule.onNodeWithText("Plain Text").performClick()
        assertEquals("plain-text", vm.uiState.value.shareFormat)
    }

    @Test
    fun journalRange_should_ShowDateFields_OnlyForThatScope() {
        val vm = showContent()
        composeTestRule.onAllNodesWithText("From (YYYY-MM-DD)").assertCountEquals(0)

        vm.setShareScope(ShareScope.JournalRange)

        composeTestRule.onNodeWithText("From (YYYY-MM-DD)").assertIsDisplayed()
        composeTestRule.onNodeWithText("To (YYYY-MM-DD)").assertIsDisplayed()
    }

    @Test
    fun journalRange_should_ShowFormatError_WhenDatesInvalid() {
        val vm = makeViewModel()
        vm.setShareScope(ShareScope.JournalRange)
        showContent(viewModel = vm)

        composeTestRule.onNodeWithText("From (YYYY-MM-DD)").performTextInput("not-a-date")
        composeTestRule.onNodeWithText("To (YYYY-MM-DD)").performTextInput("2026-01-02")
        composeTestRule.onNodeWithText("Copy to Clipboard").performClick()

        composeTestRule.onNodeWithText("Enter dates as YYYY-MM-DD").assertIsDisplayed()
    }

    @Test
    fun journalRange_should_ShowOrderError_WhenStartAfterEnd() {
        val vm = makeViewModel()
        vm.setShareScope(ShareScope.JournalRange)
        showContent(viewModel = vm)

        composeTestRule.onNodeWithText("From (YYYY-MM-DD)").performTextInput("2026-02-01")
        composeTestRule.onNodeWithText("To (YYYY-MM-DD)").performTextInput("2026-01-01")
        composeTestRule.onNodeWithText("Save to File").performClick()

        composeTestRule.onNodeWithText("Start date must be on or before end date").assertIsDisplayed()
    }

    @Test
    fun journalRange_error_should_ClearWhenFieldEdited() {
        val vm = makeViewModel()
        vm.setShareScope(ShareScope.JournalRange)
        showContent(viewModel = vm)
        composeTestRule.onNodeWithText("Copy to Clipboard").performClick()
        composeTestRule.onNodeWithText("Enter dates as YYYY-MM-DD").assertIsDisplayed()

        composeTestRule.onNodeWithText("From (YYYY-MM-DD)").performTextInput("2")

        composeTestRule.onAllNodesWithText("Enter dates as YYYY-MM-DD").assertCountEquals(0)
    }

    @Test
    fun journalRange_should_AcceptValidRange_WithoutDateError() {
        val vm = makeViewModel()
        vm.setShareScope(ShareScope.JournalRange)
        showContent(viewModel = vm)

        composeTestRule.onNodeWithText("From (YYYY-MM-DD)").performTextInput("2026-01-01")
        composeTestRule.onNodeWithText("To (YYYY-MM-DD)").performTextInput("2026-01-31")
        composeTestRule.onNodeWithText("Save to File").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Enter dates as YYYY-MM-DD").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Start date must be on or before end date").assertCountEquals(0)
    }

    @Test
    fun closeButton_should_InvokeDismiss() {
        var dismissed = false
        showContent(onDismiss = { dismissed = true })

        composeTestRule.onNodeWithText("Close").performClick()

        assertTrue(dismissed)
    }

    @Test
    fun shareVia_should_BeShownOnlyOnMobile() {
        showContent(isMobile = false)
        composeTestRule.onAllNodesWithText("Share via…").assertCountEquals(0)
    }

    @Test
    fun shareVia_should_BeShown_WhenMobile() {
        showContent(isMobile = true)
        composeTestRule.onNodeWithText("Share via…").assertIsDisplayed()
    }

    @Test
    fun googleTile_should_BeAbsent_WithoutDriveClient() {
        showContent()
        composeTestRule.onAllNodesWithText("Connect Google Account").assertCountEquals(0)
    }

    @Test
    fun shareDialog_should_RenderNothing_WhenNotVisible() {
        val vm = makeViewModel()
        composeTestRule.setContent {
            MaterialTheme {
                ShareDialog(
                    appState = vm.uiState.collectAsState().value,
                    viewModel = vm,
                    page = samplePage,
                    blocks = emptyList(),
                    selectedBlockUuids = emptySet(),
                    shareProvider = RecordingShareProvider(),
                    driveClient = null,
                    googleAuthManager = null,
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onAllNodesWithText("Share / Export").assertCountEquals(0)
    }
}
