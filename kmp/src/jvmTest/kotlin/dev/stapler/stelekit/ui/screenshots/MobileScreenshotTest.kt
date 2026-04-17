package dev.stapler.stelekit.ui.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.PopulatedFakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.PopulatedFakePageRepository
import dev.stapler.stelekit.ui.screens.JournalsView
import dev.stapler.stelekit.ui.screens.JournalsViewModel
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test

class MobileScreenshotTest {

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
    fun mobile_journals_light() {
        val viewModel = makeJournalsViewModel()
        val blockRepo = PopulatedFakeBlockRepository()

        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                Box(
                    modifier = Modifier
                        .clipToBounds()
                ) {
                    JournalsView(
                        viewModel = viewModel,
                        blockRepository = blockRepo,
                        isDebugMode = false,
                        onLinkClick = {},
                    )
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/mobile_journals_light.png")
    }

    @Test
    fun mobile_journals_dark() {
        val viewModel = makeJournalsViewModel()
        val blockRepo = PopulatedFakeBlockRepository()

        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.DARK) {
                Box(
                    modifier = Modifier
                        .clipToBounds()
                ) {
                    JournalsView(
                        viewModel = viewModel,
                        blockRepository = blockRepo,
                        isDebugMode = false,
                        onLinkClick = {},
                    )
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/mobile_journals_dark.png")
    }
}
