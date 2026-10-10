package dev.stapler.stelekit.ui.transfer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.ui.screens.PageView
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * UX Acceptance Test criterion 1 (validation.md): "Send via QR" must reach the encoder screen
 * (S1 -> S2/S3) in at most 2 taps — the page share menu (⋮) then the "Send via QR" item — running
 * against the real [PageView] composition path (mirrors
 * [QrTransferEntryPointsTest.desktopMenu_should_ShowSendViaQrAction_When_EnabledOnJvm]), but going
 * one step further: this test proves the encoder screen ([QrEncodeScreen]) actually mounts and
 * starts, not just that the menu item is present.
 */
class QrSendEntryPointUxTest {

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
    fun sendViaQr_should_ReachEncoderScreen_When_PageMenuThenSendViaQrTapped() {
        val now = Clock.System.now()
        val pageUuid = PageUuid("00000000-0000-0000-0000-000000000999")
        val page = Page(uuid = pageUuid, name = "Meeting Notes", createdAt = now, updatedAt = now)
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        runBlocking {
            pageRepo.savePage(page)
            blockRepo.saveBlock(
                Block(
                    uuid = BlockUuid("00000000-0000-0000-0000-000000000998"),
                    pageUuid = pageUuid,
                    content = "Some page content",
                    level = 0,
                    position = "a0",
                    parentUuid = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

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
            ),
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

        // Tap 1: open the page share/export menu.
        composeTestRule.onNodeWithContentDescription("Export page").performClick()
        // Tap 2: "Send via QR" — must be present and reachable in exactly this second tap.
        composeTestRule.onNodeWithText("Send via QR", substring = true).performClick()

        // QrEncodeScreen must actually mount and start (Serializing "Preparing…" or, once
        // serialization completes, the Displaying inset card's live contentDescription) — proves
        // more than "the menu item exists," proves the encoder screen was reached.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodes(hasText("Preparing…", substring = true) or hasContentDescription("Sending, frame", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.waitForIdle()
    }
}
