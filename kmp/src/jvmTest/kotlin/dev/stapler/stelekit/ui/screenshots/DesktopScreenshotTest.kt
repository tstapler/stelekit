package dev.stapler.stelekit.ui.screenshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.ui.MainLayout
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.components.LeftSidebar
import dev.stapler.stelekit.ui.components.TopBar
import dev.stapler.stelekit.ui.AppState
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.PopulatedFakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.PopulatedFakePageRepository
import dev.stapler.stelekit.ui.screens.JournalsView
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.platform.PlatformSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test

class DesktopScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeJournalsViewModel(): JournalsViewModel {
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
    fun desktop_journals_light() {
        val viewModel = makeJournalsViewModel()
        val blockRepo = PopulatedFakeBlockRepository()

        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                MainLayout(
                    topBar = {
                        TopBar(
                            appState = AppState(currentScreen = Screen.Journals),
                            platformSettings = PlatformSettings(),
                            onSettingsClick = {},
                            onNewPageClick = {},
                            onNavigate = {},
                            onThemeChange = {},
                            onLanguageChange = {},
                            onResetOnboarding = {},
                            onToggleDebug = {}
                        )
                    },
                    leftSidebar = {
                        LeftSidebar(
                            expanded = true,
                            isLoading = false,
                            favoritePages = emptyList(),
                            recentPages = emptyList(),
                            currentScreen = Screen.Journals,
                            onPageClick = {},
                            onNavigate = {},
                            onToggleFavorite = {}
                        )
                    },
                    rightSidebar = {},
                    content = {
                        JournalsView(
                            viewModel = viewModel,
                            blockRepository = blockRepo,
                            isDebugMode = false,
                            onLinkClick = {},
                        )
                    },
                    statusBar = {}
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/desktop_journals_light.png")
    }

    @Test
    fun desktop_journals_dark() {
        val viewModel = makeJournalsViewModel()
        val blockRepo = PopulatedFakeBlockRepository()

        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.DARK) {
                MainLayout(
                    topBar = {
                        TopBar(
                            appState = AppState(currentScreen = Screen.Journals),
                            platformSettings = PlatformSettings(),
                            onSettingsClick = {},
                            onNewPageClick = {},
                            onNavigate = {},
                            onThemeChange = {},
                            onLanguageChange = {},
                            onResetOnboarding = {},
                            onToggleDebug = {}
                        )
                    },
                    leftSidebar = {
                        LeftSidebar(
                            expanded = true,
                            isLoading = false,
                            favoritePages = emptyList(),
                            recentPages = emptyList(),
                            currentScreen = Screen.Journals,
                            onPageClick = {},
                            onNavigate = {},
                            onToggleFavorite = {}
                        )
                    },
                    rightSidebar = {},
                    content = {
                        JournalsView(
                            viewModel = viewModel,
                            blockRepository = blockRepo,
                            isDebugMode = false,
                            onLinkClick = {},
                        )
                    },
                    statusBar = {}
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/desktop_journals_dark.png")
    }
}
