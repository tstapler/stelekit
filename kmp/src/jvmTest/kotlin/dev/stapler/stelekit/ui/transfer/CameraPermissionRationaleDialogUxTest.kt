package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.sensor.CameraFrame
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Bug 1 fix / validation.md UX criterion 6 (`notNow_should_DismissToWorkingAlternative_When_PermissionRationaleDeclined`):
 * permission denial must never fully block the feature. [QrDecodeScreen] surfaces
 * [dev.stapler.stelekit.ui.annotate.CameraPermissionRationaleDialog] when the pre-flight reason is
 * [DomainError.SensorError.PermissionDenied]; tapping "Not now" must dismiss to a working
 * alternative (here: [onDismiss], mirroring the real call site where the QR decode screen is an
 * overlay `Dialog` on top of [dev.stapler.stelekit.ui.screens.ImportScreen], which itself offers a
 * file-import alternative) — never leaves the user stuck staring at the rationale dialog.
 */
class CameraPermissionRationaleDialogUxTest {

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

    private class NoOpFileSystem : FileSystem {
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
    }

    private fun buildImportService(): QrImportService {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val actor = DatabaseWriteActor(blockRepo, pageRepo)
        val graphLoader = GraphLoader(
            fileSystem = NoOpFileSystem(),
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            externalWriteActor = actor,
        )
        return QrImportService(graphLoader, pageRepo, actor)
    }

    private class PermissionDeniedCameraFrameSource : CameraFrameSource {
        override val isAvailable = true
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
            emit(DomainError.SensorError.PermissionDenied("camera").left())
        }
    }

    @Test
    fun notNow_should_DismissToWorkingAlternative_When_PermissionRationaleDeclined() {
        val vm = QrDecodeViewModel(
            cameraFrameSource = PermissionDeniedCameraFrameSource(),
            qrImportService = buildImportService(),
            settings = QrTransferSettings(MapSettings()),
        )
        var dismissedToAlternative = false

        composeTestRule.setContent {
            QrDecodeScreen(
                viewModel = vm,
                settings = QrTransferSettings(MapSettings()),
                onDismiss = { dismissedToAlternative = true },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            vm.state.value is QrDecodeUiState.PreflightFailed
        }
        composeTestRule.waitForIdle()

        // The rationale dialog is showing — never left the user with no exit.
        composeTestRule.onNodeWithText("Allow camera access").assertExists()

        composeTestRule.onNodeWithText("Not now").performClick()
        composeTestRule.waitForIdle()

        assertTrue(dismissedToAlternative, "\"Not now\" must dismiss to the working alternative (onDismiss), never leave the user stuck")

        vm.close()
    }
}
