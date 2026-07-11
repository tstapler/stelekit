package dev.stapler.stelekit.ui.transfer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.domain.NoOpUrlFetcher
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.ui.fixtures.PopulatedFakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.PopulatedFakePageRepository
import dev.stapler.stelekit.ui.fixtures.TestFixtures
import dev.stapler.stelekit.ui.screens.ImportScreen
import dev.stapler.stelekit.ui.screens.ImportViewModel
import dev.stapler.stelekit.ui.screens.PageView
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * Story 3.1.4: the "Send via QR" page-menu action is flag-gated on [QrTransferSettings.enabled] —
 * present (and wired to launch the encoder) when true, **absent** (not disabled/greyed) when
 * false, per the story's AC.
 */
class QrTransferEntryPointsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    @Test
    fun pageMenu_should_ShowSendViaQrAction_When_QrTransferSettingsEnabled() {
        val settings = QrTransferSettings(MapSettings()).apply { enabled = true }
        var launched = false

        composeTestRule.setContent {
            SendViaQrMenuItem(settings = settings, onClick = { launched = true })
        }

        composeTestRule.onNodeWithText("Send via QR", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Send via QR", substring = true).performClick()
        assertTrue(launched, "tapping the menu item must invoke onClick (which launches QrEncodeScreen)")
    }

    @Test
    fun pageMenu_should_OmitSendViaQrAction_When_QrTransferSettingsDisabled() {
        val settings = QrTransferSettings(MapSettings()) // enabled defaults to false

        composeTestRule.setContent {
            SendViaQrMenuItem(settings = settings, onClick = {})
        }

        composeTestRule.onNodeWithText("Send via QR", substring = true).assertDoesNotExist()
    }

    @Test
    fun importMenu_should_ShowImportViaCameraAction_When_QrTransferSettingsEnabled() {
        val settings = QrTransferSettings(MapSettings()).apply { enabled = true }
        var launched = false

        composeTestRule.setContent {
            ImportViaCameraMenuItem(settings = settings, onClick = { launched = true })
        }

        composeTestRule.onNodeWithText("Import via camera", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Import via camera", substring = true).performClick()
        assertTrue(launched, "tapping the menu item must invoke onClick (which launches QrDecodeScreen)")
    }

    @Test
    fun importMenu_should_OmitImportViaCameraAction_When_QrTransferSettingsDisabled() {
        val settings = QrTransferSettings(MapSettings()) // enabled defaults to false

        composeTestRule.setContent {
            ImportViaCameraMenuItem(settings = settings, onClick = {})
        }

        composeTestRule.onNodeWithText("Import via camera", substring = true).assertDoesNotExist()
    }

    /**
     * Story 4.1.1 (Task 4.1.1a): proves "Send via QR" reaches the real desktop composition path —
     * `PageView`'s share menu, running on the JVM target with no platform branching — rather than
     * only the isolated [SendViaQrMenuItem] composable exercised by the tests above. Runs on the
     * same jvmTest source set the desktop app is built from.
     */
    @Test
    fun desktopMenu_should_ShowSendViaQrAction_When_EnabledOnJvm() {
        val page = TestFixtures.samplePage()
        val pageRepo = PopulatedFakePageRepository()
        val blockRepo = PopulatedFakeBlockRepository()
        val platformFileSystem = PlatformFileSystem()
        val searchRepo = InMemorySearchRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val graphWriter = GraphWriter(platformFileSystem)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var viewModelRef: StelekitViewModel? = null
        val blockStateManager = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = scope,
            pageRepository = pageRepo,
            graphPathProvider = { viewModelRef?.uiState?.value?.currentGraphPath ?: "" },
        )
        val viewModel = StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = platformFileSystem,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = searchRepo,
                graphLoader = graphLoader,
                graphWriter = graphWriter,
                platformSettings = InMemorySettings(),
                scope = scope,
                blockStateManager = blockStateManager,
            )
        ).also { viewModelRef = it }
        val qrTransferSettings = QrTransferSettings(MapSettings()).apply { enabled = true }

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
                    isDebugMode = false,
                    qrTransferSettings = qrTransferSettings,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Export page").performClick()
        composeTestRule.onNodeWithText("Send via QR", substring = true).assertIsDisplayed()
    }

    /**
     * Decode-side equivalent of [desktopMenu_should_ShowSendViaQrAction_When_EnabledOnJvm]: proves
     * "Import via camera" reaches the real desktop composition path — `ImportScreen`'s own menu —
     * rather than only the isolated [ImportViaCameraMenuItem] composable exercised above. Guards
     * the `ScreenRouter` -> `ImportScreen` wiring fix (decode-side counterpart of commit 9b537e8cdc)
     * that threads a live [QrTransferSettings] instance down instead of leaving it `null`.
     */
    @Test
    fun desktopImportMenu_should_ShowImportViaCameraAction_When_EnabledOnJvm() {
        val pageRepo = PopulatedFakePageRepository()
        val platformFileSystem = PlatformFileSystem()
        val graphWriter = GraphWriter(platformFileSystem)
        val matcherFlow = MutableStateFlow<AhoCorasickMatcher?>(null)
        val importViewModel = ImportViewModel(
            pageRepository = pageRepo,
            graphWriter = graphWriter,
            graphPath = "/tmp/test",
            urlFetcher = NoOpUrlFetcher(),
            matcherFlow = matcherFlow,
        )
        val qrTransferSettings = QrTransferSettings(MapSettings()).apply { enabled = true }

        composeTestRule.setContent {
            MaterialTheme {
                ImportScreen(
                    viewModel = importViewModel,
                    onDismiss = {},
                    qrTransferSettings = qrTransferSettings,
                    onImportViaCamera = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("More import options").performClick()
        composeTestRule.onNodeWithText("Import via camera", substring = true).assertIsDisplayed()
    }
}
