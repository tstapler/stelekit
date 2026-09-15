package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test

/**
 * Bug 1 fix / validation.md UX criterion 11: [QrDecodeUiState.PreflightFailed] renders textually
 * (and iconically, via the leading emoji) distinct copy for
 * [DomainError.SensorError.HardwareUnavailable] versus [DomainError.SensorError.PermissionDenied]
 * — never the old shared generic "Camera unavailable" text regardless of cause.
 */
class QrDecodePreflightUxTest {

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

    private fun buildViewModel(cameraSource: CameraFrameSource): QrDecodeViewModel = QrDecodeViewModel(
        cameraFrameSource = cameraSource,
        qrImportService = buildImportService(),
        settings = QrTransferSettings(MapSettings()),
    )

    private class UnavailableCameraFrameSource : CameraFrameSource {
        override val isAvailable = false
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = emptyFlow()
    }

    private class PermissionDeniedCameraFrameSource : CameraFrameSource {
        override val isAvailable = true
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
            emit(DomainError.SensorError.PermissionDenied("camera").left())
        }
    }

    @Test
    fun preflightFailed_should_ShowDistinctCopyAndIcon_When_HardwareUnavailable_VersusPermissionDenied() {
        val hardwareUnavailableVm = buildViewModel(UnavailableCameraFrameSource())
        composeTestRule.setContent {
            QrDecodeScreen(
                viewModel = hardwareUnavailableVm,
                settings = QrTransferSettings(MapSettings()),
                onDismiss = {},
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            hardwareUnavailableVm.state.value is QrDecodeUiState.PreflightFailed
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("📷🚫  Camera unavailable").assertExists()
        composeTestRule.onNodeWithText(
            "This device doesn't have a usable camera for scanning transfer codes. Try importing from a file instead.",
        ).assertExists()
        hardwareUnavailableVm.close()

        val permissionDeniedVm = buildViewModel(PermissionDeniedCameraFrameSource())
        composeTestRule.setContent {
            QrDecodeScreen(
                viewModel = permissionDeniedVm,
                settings = QrTransferSettings(MapSettings()),
                onDismiss = {},
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            permissionDeniedVm.state.value is QrDecodeUiState.PreflightFailed
        }
        composeTestRule.waitForIdle()

        // Distinct icon (leading emoji) and distinct body copy — no text shared with the
        // HardwareUnavailable branch above.
        composeTestRule.onNodeWithText("📷🔒  Camera permission needed").assertExists()
        composeTestRule.onNodeWithText("📷🚫  Camera unavailable").assertDoesNotExist()
        composeTestRule.onNodeWithText(
            "This device doesn't have a usable camera for scanning transfer codes. Try importing from a file instead.",
        ).assertDoesNotExist()

        permissionDeniedVm.close()
    }
}
