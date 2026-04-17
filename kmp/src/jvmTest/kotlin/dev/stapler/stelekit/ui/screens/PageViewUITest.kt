package dev.stapler.stelekit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.PopulatedFakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.PopulatedFakePageRepository
import dev.stapler.stelekit.ui.fixtures.TestFixtures
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test

private fun makeBlockStateManager(
    pageRepo: PopulatedFakePageRepository,
    blockRepo: dev.stapler.stelekit.repository.BlockRepository,
    graphLoader: GraphLoader,
    scope: CoroutineScope,
    viewModelRef: () -> StelekitViewModel?
) = BlockStateManager(
    blockRepository = blockRepo,
    graphLoader = graphLoader,
    scope = scope,
    pageRepository = pageRepo,
    graphPathProvider = { viewModelRef()?.uiState?.value?.currentGraphPath ?: "" }
)

class PageViewUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeViewModel(
        pageRepo: PopulatedFakePageRepository,
        blockRepo: PopulatedFakeBlockRepository
    ): StelekitViewModel {
        val platformFileSystem = PlatformFileSystem()
        val searchRepo = InMemorySearchRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val graphWriter = GraphWriter(platformFileSystem)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var viewModelRef: StelekitViewModel? = null
        val bsm = makeBlockStateManager(pageRepo, blockRepo, graphLoader, scope) { viewModelRef }
        return StelekitViewModel(
            fileSystem = platformFileSystem,
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            searchRepository = searchRepo,
            graphLoader = graphLoader,
            graphWriter = graphWriter,
            platformSettings = PlatformSettings(),
            scope = scope,
            blockStateManager = bsm
        ).also { viewModelRef = it }
    }

    @Test
    fun pageView_showsPageTitle() {
        val page = TestFixtures.samplePage()
        val pageRepo = PopulatedFakePageRepository()
        val blockRepo = PopulatedFakeBlockRepository()
        val viewModel = makeViewModel(pageRepo, blockRepo)
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val blockStateManager = BlockStateManager(blockRepo, graphLoader, scope)

        composeTestRule.setContent {
            MaterialTheme {
                PageView(
                    page = page,
                    blockRepository = blockRepo,
                    pageRepository = pageRepo,
                    blockStateManager = blockStateManager,
                    currentGraphPath = "/tmp/test",
                    onToggleFavorite = {},
                    onRefresh = {},
                    onLinkClick = {},
                    viewModel = viewModel,
                    isDebugMode = false
                )
            }
        }

        composeTestRule.onNodeWithText("Test Page").assertIsDisplayed()
    }

    @Test
    fun pageView_showsEmptyStatePlaceholder() {
        val page = TestFixtures.samplePage()
        val pageRepo = PopulatedFakePageRepository()
        // Use empty block repo so page shows placeholder
        val emptyBlockRepo = dev.stapler.stelekit.ui.fixtures.FakeBlockRepository()
        val searchRepo = InMemorySearchRepository()
        val platformFileSystem = PlatformFileSystem()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, emptyBlockRepo)
        val graphWriter = GraphWriter(platformFileSystem)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var viewModelRef2: StelekitViewModel? = null
        val bsm2 = makeBlockStateManager(pageRepo, emptyBlockRepo, graphLoader, scope) { viewModelRef2 }
        val viewModel = StelekitViewModel(
            fileSystem = platformFileSystem,
            pageRepository = pageRepo,
            blockRepository = emptyBlockRepo,
            searchRepository = searchRepo,
            graphLoader = graphLoader,
            graphWriter = graphWriter,
            platformSettings = PlatformSettings(),
            scope = scope,
            blockStateManager = bsm2
        ).also { viewModelRef2 = it }

        val blockStateManager = BlockStateManager(emptyBlockRepo, graphLoader, scope)

        composeTestRule.setContent {
            MaterialTheme {
                PageView(
                    page = page,
                    blockRepository = emptyBlockRepo,
                    pageRepository = pageRepo,
                    blockStateManager = blockStateManager,
                    currentGraphPath = "/tmp/test",
                    onToggleFavorite = {},
                    onRefresh = {},
                    onLinkClick = {},
                    viewModel = viewModel,
                    isDebugMode = false
                )
            }
        }

        composeTestRule.onNodeWithText("Click to write...").assertIsDisplayed()
    }
}
