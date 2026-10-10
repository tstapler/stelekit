package dev.stapler.stelekit.ui.transfer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.domain.NoOpUrlFetcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.sensor.CameraFrame
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import dev.stapler.stelekit.ui.fixtures.PopulatedFakePageRepository
import dev.stapler.stelekit.ui.screens.ImportScreen
import dev.stapler.stelekit.ui.screens.ImportViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test

/**
 * Story 3.2.4 / UX Acceptance Test criterion 2: a user can reach the receive surface in **≤ 2
 * taps** from an open import screen — Import ▾ menu (tap 1) → "Import via camera" (tap 2) — landing
 * on either the camera-permission rationale (S6) or the decoder's `Idle`/`PreflightFailed` screen
 * (S8), never a dead end or an extra intermediate step. Mirrors
 * [QrTransferEntryPointsTest.desktopImportMenu_should_ShowImportViaCameraAction_When_EnabledOnJvm]'s
 * real `ImportScreen` composition path, but additionally wires `onImportViaCamera` to actually swap
 * in [QrDecodeScreen] (rather than a no-op), so the 2-tap journey is proven end-to-end.
 */
class QrImportEntryPointUxTest {

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

    /** Never streams a frame — deterministically reaches `PreflightFailed(HardwareUnavailable)` (S8). */
    private class UnavailableCameraFrameSource : CameraFrameSource {
        override val isAvailable = false
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = emptyFlow()
    }

    private fun buildDecodeViewModel(): QrDecodeViewModel {
        val pageRepo = dev.stapler.stelekit.repository.InMemoryPageRepository()
        val blockRepo = dev.stapler.stelekit.repository.InMemoryBlockRepository()
        val actor = dev.stapler.stelekit.db.DatabaseWriteActor(blockRepo, pageRepo)
        val graphLoader = dev.stapler.stelekit.db.GraphLoader(
            fileSystem = object : dev.stapler.stelekit.platform.FileSystem {
                override fun getDefaultGraphPath() = ""
                override fun expandTilde(path: String) = path
                override fun readFile(path: String): String? = null
                override fun writeFile(path: String, content: String) = false
                override fun listFiles(path: String): List<String> = emptyList()
                override fun listDirectories(path: String): List<String> = emptyList()
                override fun fileExists(path: String) = false
                override fun directoryExists(path: String) = false
                override fun createDirectory(path: String) = false
                override fun deleteFile(path: String) = false
                override fun pickDirectory(): String? = null
                override fun getLastModifiedTime(path: String): Long? = null
            },
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            externalWriteActor = actor,
        )
        return QrDecodeViewModel(
            cameraFrameSource = UnavailableCameraFrameSource(),
            qrImportService = QrImportService(graphLoader, pageRepo, actor),
            settings = QrTransferSettings(MapSettings()),
        )
    }

    @Test
    fun importViaCamera_should_ReachPermissionOrDecoderScreen_When_ImportMenuThenImportViaCameraTapped() {
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
        val decodeViewModel = buildDecodeViewModel()

        composeTestRule.setContent {
            var showDecoder by remember { mutableStateOf(false) }
            MaterialTheme {
                if (!showDecoder) {
                    ImportScreen(
                        viewModel = importViewModel,
                        onDismiss = {},
                        qrTransferSettings = qrTransferSettings,
                        onImportViaCamera = { showDecoder = true },
                    )
                } else {
                    QrDecodeScreen(
                        viewModel = decodeViewModel,
                        settings = qrTransferSettings,
                        onDismiss = {},
                    )
                }
            }
        }

        // Tap 1: open the Import ▾ menu.
        composeTestRule.onNodeWithContentDescription("More import options").performClick()
        // Tap 2: "Import via camera" — the ONLY further tap allowed by UX criterion 2.
        composeTestRule.onNodeWithText("Import via camera", substring = true).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            decodeViewModel.state.value is QrDecodeUiState.PreflightFailed
        }
        composeTestRule.waitForIdle()

        // Landed on S8 (PreflightFailed) after exactly 2 taps — never a blank/dead screen, and no
        // further tap was required to reach it.
        composeTestRule.onNodeWithText("📷🚫  Camera unavailable").assertExists()

        decodeViewModel.close()
    }
}
