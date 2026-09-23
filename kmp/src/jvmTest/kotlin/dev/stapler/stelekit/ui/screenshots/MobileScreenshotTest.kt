package dev.stapler.stelekit.ui.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.takahirom.roborazzi.captureRoboImage
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.ui.components.BlockGutter
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
                        isDebugMode = false,
                        onLinkClick = {},
                    )
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/mobile_journals_dark.png")
    }

    // ux.md criterion 19 / validation.md REQ (row 19): the drag handle's *hit area* — the
    // pointerInput/detectDragGestures-wired region, not just the rendered DragHandle glyph —
    // must independently meet this app's 48dp IconButton touch-target minimum. A cosmetic-only
    // padding increase around a small hit box would not satisfy this: the contentDescription
    // (and therefore the semantics node these assertions measure) must live on the same
    // modifier chain that carries the gesture detectors.
    @Test
    fun dragHandleHitArea_should_meetFortyEightDpMinimum_When_measuredIndependentlyOfVisualGlyphSize() {
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                Row {
                    BlockGutter(
                        level = 0,
                        isDebugMode = false,
                        hasChildren = false,
                        isCollapsed = false,
                        onToggleCollapse = {},
                        onMoveUp = {},
                        onMoveDown = {},
                        blockUuid = "block-1",
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Drag to move")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }
}
