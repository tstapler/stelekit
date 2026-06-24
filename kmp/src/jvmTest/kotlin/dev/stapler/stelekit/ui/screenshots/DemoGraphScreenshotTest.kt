package dev.stapler.stelekit.ui.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.screens.JournalsView
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

class DemoGraphScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildFixtures(): Triple<Page, List<Block>, String> {
        val now = Clock.System.now()
        val pageUuid = "demo-0000-0000-0000-000000000001"
        val page = Page(
            uuid = PageUuid(pageUuid), name = "2026_01_15",
            createdAt = now, updatedAt = now,
            isJournal = true, journalDate = LocalDate(2026, 1, 15)
        )
        val blocks = listOf(
            Block(uuid = BlockUuid("demo-b001"), pageUuid = PageUuid(pageUuid), content = "date:: 2026-01-15", level = 0, position = "a0", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("demo-b002"), pageUuid = PageUuid(pageUuid), content = "First day exploring SteleKit.", level = 0, position = "a1", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("demo-b003"), pageUuid = PageUuid(pageUuid), parentUuid = "demo-b002", content = "Opened the app and added my first graph", level = 1, position = "a0", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("demo-b004"), pageUuid = PageUuid(pageUuid), parentUuid = "demo-b002", content = "Created a few test pages", level = 1, position = "a1", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("demo-b005"), pageUuid = PageUuid(pageUuid), content = "Things I learned today", level = 0, position = "a2", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("demo-b006"), pageUuid = PageUuid(pageUuid), parentUuid = "demo-b005", content = "Blocks are the primary unit of content", level = 1, position = "a0", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("demo-b007"), pageUuid = PageUuid(pageUuid), parentUuid = "demo-b005", content = "Everything auto-saves after 500ms", level = 1, position = "a1", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("demo-b008"), pageUuid = PageUuid(pageUuid), parentUuid = "demo-b005", content = "[[Welcome]] is the best starting point", level = 1, position = "a2", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("demo-b009"), pageUuid = PageUuid(pageUuid), content = "Tomorrow: explore properties and linking.", level = 0, position = "a3", createdAt = now, updatedAt = now),
        )
        return Triple(page, blocks, pageUuid)
    }

    @Test
    fun demo_journals_light() {
        val (page, blocks, pageUuid) = buildFixtures()

        val pageRepo = FakePageRepository(initialPages = listOf(page))
        val blockRepo = FakeBlockRepository(blocksByPage = mapOf(pageUuid to blocks))
        val journalService = JournalService(pageRepo, blockRepo)
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val blockStateManager = BlockStateManager(blockRepo, graphLoader, scope)
        val viewModel = JournalsViewModel(journalService, blockStateManager, scope)

        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                JournalsView(viewModel = viewModel, isDebugMode = false, onLinkClick = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/demo_journals_light.png")
    }

    @Test
    fun demo_journals_dark() {
        val (page, blocks, pageUuid) = buildFixtures()

        val pageRepo = FakePageRepository(initialPages = listOf(page))
        val blockRepo = FakeBlockRepository(blocksByPage = mapOf(pageUuid to blocks))
        val journalService = JournalService(pageRepo, blockRepo)
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val blockStateManager = BlockStateManager(blockRepo, graphLoader, scope)
        val viewModel = JournalsViewModel(journalService, blockStateManager, scope)

        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.DARK) {
                JournalsView(viewModel = viewModel, isDebugMode = false, onLinkClick = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/demo_journals_dark.png")
    }
}
