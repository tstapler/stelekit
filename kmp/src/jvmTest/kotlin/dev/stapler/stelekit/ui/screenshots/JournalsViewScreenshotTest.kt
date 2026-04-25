package dev.stapler.stelekit.ui.screenshots

import androidx.compose.ui.test.onRoot
import io.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.screens.JournalsView
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

class JournalsViewScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------------------------------------------------------------------------
    // Fast test: fake repos + seeded data, verifies inline markdown rendering
    // ---------------------------------------------------------------------------
    @Test
    fun journals_view_markdown_rendering() {
        val now = Clock.System.now()
        val pageUuid = "00000000-0000-0000-0000-000000000001"
        val parentBlockUuid = "00000000-0000-0000-0000-000000000002"
        val childBlockUuid = "00000000-0000-0000-0000-000000000003"
        val grandchildBlockUuid = "00000000-0000-0000-0000-000000000004"

        val page = Page(
            uuid = pageUuid,
            name = "2026_03_21",
            createdAt = now, updatedAt = now,
            isJournal = true, journalDate = LocalDate(2026, 3, 21)
        )
        val parentBlock = Block(
            uuid = parentBlockUuid,
            pageUuid = pageUuid,
            content = "**Bold text** with [[Wiki Link]] and plain text",
            level = 0, position = 0, createdAt = now, updatedAt = now
        )
        val childBlock = Block(
            uuid = childBlockUuid,
            pageUuid = pageUuid, parentUuid = parentBlockUuid, leftUuid = parentBlockUuid,
            content = "Child block with #tag and ~~strikethrough~~ and `code`",
            level = 1, position = 0, createdAt = now, updatedAt = now
        )
        val grandchildBlock = Block(
            uuid = grandchildBlockUuid,
            pageUuid = pageUuid, parentUuid = childBlockUuid, leftUuid = childBlockUuid,
            content = "Grandchild with unresolved ref ((00000000-0000-0000-0000-000000000099))",
            level = 2, position = 0, createdAt = now, updatedAt = now
        )

        val pageRepo = FakePageRepository(initialPages = listOf(page))
        val blockRepo = FakeBlockRepository(
            blocksByPage = mapOf(pageUuid to listOf(parentBlock, childBlock, grandchildBlock))
        )
        val journalService = JournalService(pageRepo, blockRepo)
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val blockStateManager = BlockStateManager(blockRepo, graphLoader, scope)
        val viewModel = JournalsViewModel(
            journalService, blockStateManager, scope
        )

        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                JournalsView(
                    viewModel = viewModel,
                    blockRepository = blockRepo,
                    isDebugMode = true,
                    onLinkClick = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/journals_reproduction.png")
    }

    // ---------------------------------------------------------------------------
    // Live test: real PlatformFileSystem + DriverFactory + GraphLoader
    // Mirrors exactly what the JVM app does. Loads recent journals from the wiki.
    // ---------------------------------------------------------------------------
    @Test
    fun journals_view_live_graph() {
        // Only run when explicitly requested — loads the real personal wiki and is memory-intensive.
        // Run with: ./gradlew jvmTest -Drun.live.graph.tests=true
        org.junit.Assume.assumeTrue(
            "Skipping live-graph test (set -Drun.live.graph.tests=true to enable)",
            System.getProperty("run.live.graph.tests") == "true"
        )

        val graphPath = System.getProperty("user.home") + "/Documents/personal-wiki/logseq"

        // Same setup as the app: real filesystem, in-memory SQLite for isolation
        val fileSystem = PlatformFileSystem()
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val pageRepo = SqlDelightPageRepository(database)
        val blockRepo = SqlDelightBlockRepository(database)
        val journalService = JournalService(pageRepo, blockRepo)
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)

        // Mirror what the app does: loadGraphProgressive on a background IO scope,
        // wait only for phase 1 (recent journals with full content) before touching the UI.
        val loadScope = CoroutineScope(Dispatchers.IO)
        val phase1Done = CompletableDeferred<Unit>()
        loadScope.launch {
            graphLoader.loadGraphProgressive(
                graphPath = graphPath,
                immediateJournalCount = 5,
                onProgress = { },
                onPhase1Complete = { phase1Done.complete(Unit) },
                onFullyLoaded = { }
            )
        }
        runBlocking { phase1Done.await() }
        // Cancel background loading: prevents phase 2 from causing reactive re-emissions
        // during the screenshot and prevents runaway processes after the test exits
        loadScope.cancel()

        val ioScope = CoroutineScope(Dispatchers.IO)
        val blockStateManager = BlockStateManager(blockRepo, graphLoader, ioScope)
        val viewModel = JournalsViewModel(
            journalService, blockStateManager, ioScope
        )

        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                JournalsView(
                    viewModel = viewModel,
                    blockRepository = blockRepo,
                    isDebugMode = true,
                    onLinkClick = {},
                )
            }
        }

        // SQLDelight flows emit on IO — poll until all pages have blocks collected
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            val state = viewModel.uiState.value
            state.pages.isNotEmpty() && viewModel.blocks.value.size >= state.pages.size
        }
        // Diagnostic: log StateFlow state before screenshot
        val diagnosticState = viewModel.uiState.value
        val diagnosticBlocks = viewModel.blocks.value
        println("=== DIAGNOSTIC === pages=${diagnosticState.pages.map { it.name }}")
        println("=== DIAGNOSTIC === blocks=${diagnosticBlocks.map { (k, v) -> "$k->${v.size}" }}")
        // Allow the IO→Main dispatch from collectAsState() to be queued and processed
        Thread.sleep(1000)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/journals_live.png")
    }
}
