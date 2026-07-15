package dev.stapler.stelekit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    // Regression test for stelekit#238: dragging a block's gutter handle on the Journals view
    // visually behaved (ghost, drop-zone divider) but never actually reordered anything.
    // JournalsView's private JournalEntry composable never forwarded onMoveSelectedBlocks /
    // onAutoSelectForDrag to PageContent, so both silently defaulted to no-ops — the drag
    // gesture ran to completion but had nothing to call. Must fail against the pre-fix code
    // (revert the onMoveSelectedBlocks/onAutoSelectForDrag wiring in JournalsView.kt and
    // JournalEntry's PageContent call to confirm — verified).
    //
    // Note: stelekit#238 also involved a second bug in BlockGutter (entering selection mode
    // mid-drag swapped the drag handle for a Checkbox, tearing down the live pointerInput
    // gesture) — fixed alongside this one, but NOT covered by this test. That failure is a
    // recomposition-timing race that only manifests with real inter-frame delays; it does not
    // reproduce through this harness's synchronous/atomic touch-input dispatch (confirmed by
    // reverting the BlockGutter fix and re-running — still green). Verified manually instead:
    // on a real Android emulator, reproduced the exact failure with the wiring fix alone
    // applied, then confirmed the reorder completes and persists to the backing markdown file
    // once the BlockGutter fix is added too.
    @Test
    fun `dragging a block on the journals view actually reorders it`() {
        val pageRepo = PopulatedFakePageRepository()
        val now = Clock.System.now()
        val firstBlock = Block(
            uuid = BlockUuid("drag-block-first"),
            pageUuid = PageUuid("journal-1"),
            content = "First Block",
            position = "a0",
            createdAt = now,
            updatedAt = now,
        )
        val secondBlock = Block(
            uuid = BlockUuid("drag-block-second"),
            pageUuid = PageUuid("journal-1"),
            content = "Second Block",
            position = "a1",
            createdAt = now,
            updatedAt = now,
        )
        val blockRepo = FakeBlockRepository(mapOf("journal-1" to listOf(firstBlock, secondBlock)))
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
            composeTestRule.onAllNodesWithContentDescription("Drag to move")
                .fetchSemanticsNodes().size >= 2
        }

        // JVM/desktop (useLongPressForDrag() == false) drags immediately on down+move, no
        // long-press wait needed. Drag the first block's handle down past the second block —
        // computeDropTarget picks the nearest non-dragged block by center distance, so a large
        // overshoot still resolves unambiguously to "second block, BELOW zone".
        composeTestRule.onAllNodesWithContentDescription("Drag to move")[0].performTouchInput {
            down(center)
            moveBy(Offset(0f, 500f))
            up()
        }

        composeTestRule.waitForIdle()

        val finalOrder = runBlocking { blockRepo.getBlocksForPage(PageUuid("journal-1")).first() }
            .getOrNull()
            ?.map { it.uuid.value }
        org.junit.Assert.assertEquals(
            listOf("drag-block-second", "drag-block-first"),
            finalOrder,
        )
    }
}
