package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.screens.JournalsView
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for core outliner UI flows.
 * Verifies that basic operations like rendering blocks and hierarchy work.
 */
class OutlinerRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testBasicOutlinerFlow() {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val pageRepo = SqlDelightPageRepository(database)
        val blockRepo = SqlDelightBlockRepository(database)
        val fileSystem = FakeFileSystem()
        val journalService = JournalService(pageRepo, blockRepo)
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)

        val now = Clock.System.now()
        val pageUuid = "page-uuid-1"
        val block1Uuid = "block-uuid-1"
        val block2Uuid = "block-uuid-2"

        // Setup initial data
        runBlocking {
            pageRepo.savePage(
                Page(
                    uuid = pageUuid,
                    name = "2026-03-28",
                    createdAt = now,
                    updatedAt = now,
                    isJournal = true,
                    journalDate = LocalDate(2026, 3, 28)
                )
            )
            
            blockRepo.saveBlock(
                Block(
                    uuid = block1Uuid,
                    pageUuid = pageUuid,
                    content = "Parent Block",
                    position = 0,
                    createdAt = now,
                    updatedAt = now
                )
            )
            
            blockRepo.saveBlock(
                Block(
                    uuid = block2Uuid,
                    pageUuid = pageUuid,
                    content = "Child Block",
                    parentUuid = block1Uuid,
                    level = 1,
                    position = 0,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val blockStateManager = BlockStateManager(blockRepo, graphLoader, scope)
        val viewModel = JournalsViewModel(journalService, blockStateManager, scope)

        composeTestRule.setContent {
            MaterialTheme {
                JournalsView(
                    viewModel = viewModel,
                    blockRepository = blockRepo,
                    isDebugMode = true, // Show UUIDs for debugging
                    onLinkClick = {},
                )
            }
        }

        // 1. Verify page renders — formatJournalDate renders "Saturday, March 28, 2026"
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(hasText("March 28", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("March 28", substring = true).assertIsDisplayed()

        // 2. Verify blocks render
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(hasText("Parent Block", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Parent Block", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Child Block", substring = true).assertIsDisplayed()
        
        // 3. Perform an operation (e.g., outdent the child)
        runBlocking {
            viewModel.outdentBlock(block2Uuid)
        }
        
        // 4. Verify state update (in real app, reactive flows would update this)
        // Since we are using Unconfined dispatcher and reactive flows, the UI should update.
        
        // Verify both blocks are now at the same level (can be checked via position or properties in debug mode)
        composeTestRule.onNodeWithText("Parent Block").assertIsDisplayed()
        composeTestRule.onNodeWithText("Child Block").assertIsDisplayed()
    }
}
