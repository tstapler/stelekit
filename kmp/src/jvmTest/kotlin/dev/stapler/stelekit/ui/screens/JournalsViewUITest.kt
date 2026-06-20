package dev.stapler.stelekit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.ui.components.EditorCapabilities
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.PopulatedFakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.PopulatedFakePageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test

class JournalsViewUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeViewModel(): JournalsViewModel {
        val pageRepo = PopulatedFakePageRepository()
        val blockRepo = PopulatedFakeBlockRepository()
        val fileSystem = FakeFileSystem()
        val journalService = JournalService(pageRepo, blockRepo)
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val blockStateManager = BlockStateManager(blockRepo, graphLoader, scope)
        return JournalsViewModel(journalService, blockStateManager, scope)
    }

    @Test
    fun journalsView_showsJournalDates() {
        val viewModel = makeViewModel()
        val blockRepo = PopulatedFakeBlockRepository()

        composeTestRule.setContent {
            MaterialTheme {
                JournalsView(
                    viewModel = viewModel,
                    isDebugMode = false,
                    onLinkClick = {},
                )
            }
        }

        // PopulatedFakePageRepository has a journal page with name "2026-03-28".
        // formatJournalDate renders it as "Saturday, March 28, 2026" — check for the
        // day+year portion which doesn't depend on day-of-week calculation.
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("March 28, 2026", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("March 28, 2026", substring = true).assertIsDisplayed()
    }

    @Test
    fun journalsView_showsClickToWriteWhenEmpty() {
        val pageRepo = PopulatedFakePageRepository()
        val blockRepo = PopulatedFakeBlockRepository()
        val fileSystem = FakeFileSystem()
        // Create a viewmodel with empty block repo so journals show placeholder
        val emptyBlockRepo = dev.stapler.stelekit.ui.fixtures.FakeBlockRepository()
        val journalService2 = JournalService(pageRepo, emptyBlockRepo)
        val graphLoader2 = GraphLoader(fileSystem, pageRepo, emptyBlockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val blockStateManager2 = BlockStateManager(emptyBlockRepo, graphLoader2, scope)
        val viewModel = JournalsViewModel(journalService2, blockStateManager2, scope)

        composeTestRule.setContent {
            MaterialTheme {
                JournalsView(
                    viewModel = viewModel,
                    isDebugMode = false,
                    onLinkClick = {},
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 3000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("Click to write...", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        }

        // Multiple "Click to write..." nodes exist (one per journal page) — assert at least one is shown
        composeTestRule.onAllNodes(
            androidx.compose.ui.test.hasText("Click to write...", substring = true)
        )[0].assertIsDisplayed()
    }

    // Regression test: JournalsView was missing onAttachImage wiring in MobileBlockToolbar.
    // Previously, even if onAttachImage was provided, the button never appeared because
    // JournalsView didn't accept the parameter and didn't pass it to MobileBlockToolbar.
    @Test
    fun `toolbar shows attach image button when onAttachImage is provided and a block is being edited`() {
        val pageRepo = PopulatedFakePageRepository()
        val now = Clock.System.now()
        val journalBlock = Block(
            uuid = BlockUuid("journal-block-1"),
            pageUuid = PageUuid("journal-1"),
            content = "Today's note",
            position = "a0",
            createdAt = now,
            updatedAt = now,
        )
        val blockRepo = FakeBlockRepository(mapOf("journal-1" to listOf(journalBlock)))
        val fileSystem = FakeFileSystem()
        val journalService = JournalService(pageRepo, blockRepo)
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val blockStateManager = BlockStateManager(blockRepo, graphLoader, scope)
        val viewModel = JournalsViewModel(journalService, blockStateManager, scope)

        composeTestRule.setContent {
            MaterialTheme {
                JournalsView(
                    viewModel = viewModel,
                    isDebugMode = false,
                    onLinkClick = {},
                    capabilities = EditorCapabilities(onAttachImage = { /* present but no-op */ }),
                )
            }
        }

        // Wait for the journal to render, then enter editing state
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("March 28, 2026", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        viewModel.requestEditBlock(BlockUuid("journal-block-1"))

        composeTestRule.waitUntil(timeoutMillis = 3000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Attach image")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Attach image").assertIsDisplayed()
    }

    // Regression test: JournalsView was missing isInSelectionMode / onDeleteSelected wiring.
    // Previously, even in multi-select mode the toolbar showed the wrong row (no delete button).
    @Test
    fun `toolbar shows delete selected button when blocks are selected in journals`() {
        val pageRepo = PopulatedFakePageRepository()
        val now = Clock.System.now()
        val journalBlock = Block(
            uuid = BlockUuid("journal-block-2"),
            pageUuid = PageUuid("journal-1"),
            content = "Selectable note",
            position = "a0",
            createdAt = now,
            updatedAt = now,
        )
        val blockRepo = FakeBlockRepository(mapOf("journal-1" to listOf(journalBlock)))
        val fileSystem = FakeFileSystem()
        val journalService = JournalService(pageRepo, blockRepo)
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val blockStateManager = BlockStateManager(blockRepo, graphLoader, scope)
        val viewModel = JournalsViewModel(journalService, blockStateManager, scope)

        composeTestRule.setContent {
            MaterialTheme {
                JournalsView(
                    viewModel = viewModel,
                    isDebugMode = false,
                    onLinkClick = {},
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 3000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("March 28, 2026", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        viewModel.enterSelectionMode(BlockUuid("journal-block-2"))

        composeTestRule.waitUntil(timeoutMillis = 3000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Delete selected")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Delete selected").assertIsDisplayed()
    }
}
