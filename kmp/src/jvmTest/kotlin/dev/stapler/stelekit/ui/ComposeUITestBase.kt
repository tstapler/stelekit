package dev.stapler.stelekit.ui

import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.PopulatedFakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.PopulatedFakePageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.testing.BlockHoundTestBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule

/**
 * Base class for Compose UI interaction tests.
 *
 * Wires the standard fake dependency graph so subclasses only need to call
 * [composeTestRule.setContent {}] and interact with the UI.
 *
 * Usage:
 * ```kotlin
 * class MyTest : ComposeUITestBase() {
 *     @Test
 *     fun someTest() {
 *         composeTestRule.setContent { MyScreen(viewModel = viewModel) }
 *         composeTestRule.onNodeWithText("Save").performClick()
 *         composeTestRule.onNodeWithText("Saved!").assertIsDisplayed()
 *     }
 * }
 * ```
 */
open class ComposeUITestBase : BlockHoundTestBase() {

    @get:Rule
    val composeTestRule = createComposeRule()

    val pageRepo = PopulatedFakePageRepository()
    val blockRepo = PopulatedFakeBlockRepository()
    val fakeFileSystem = FakeFileSystem()
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val graphLoader = GraphLoader(fakeFileSystem, pageRepo, blockRepo)
    val graphWriter = GraphWriter(PlatformFileSystem())
    val searchRepo = InMemorySearchRepository()
    val platformSettings = PlatformSettings()

    val blockStateManager = BlockStateManager(
        blockRepository = blockRepo,
        graphLoader = graphLoader,
        scope = scope,
        graphWriter = graphWriter,
        pageRepository = pageRepo,
        graphPathProvider = { viewModel.uiState.value.currentGraphPath }
    )

    val viewModel: StelekitViewModel by lazy {
        StelekitViewModel(
            fileSystem = PlatformFileSystem(),
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            searchRepository = searchRepo,
            graphLoader = graphLoader,
            graphWriter = graphWriter,
            platformSettings = platformSettings,
            scope = scope,
            blockStateManager = blockStateManager,
        )
    }
}
