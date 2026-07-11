package dev.stapler.stelekit.ui.transfer

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.sensor.CameraFrame
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.transfer.FrameTransportReceiver
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import dev.stapler.stelekit.transfer.qrcode.QrTransferCoordinator
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Story 3.2.2 acceptance criteria: [QrDecodeViewModel] rejects immediately at pre-flight when the
 * camera is unavailable (never entering [QrDecodeUiState.Scanning]), and CLAUDE.md's uncaught-
 * Throwable rule — an [OutOfMemoryError] thrown during frame collection must surface as
 * [QrDecodeUiState.Failed], never crash the process.
 */
class QrDecodeViewModelTest {

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

    private class UnavailableCameraFrameSource : CameraFrameSource {
        override val isAvailable = false
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = emptyFlow()
    }

    private class AvailableNoFramesCameraSource : CameraFrameSource {
        override val isAvailable = true
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = emptyFlow()
    }

    @Test
    fun start_should_TransitionDirectlyToPreflightFailed_When_CameraFrameSourceIsUnavailable() = runBlocking {
        val vm = QrDecodeViewModel(
            cameraFrameSource = UnavailableCameraFrameSource(),
            qrImportService = buildImportService(),
            settings = QrTransferSettings(MapSettings()),
        )

        vm.start()

        val state = assertIs<QrDecodeUiState.PreflightFailed>(vm.state.value)
        assertIs<DomainError.SensorError.HardwareUnavailable>(state.reason)

        vm.close()
    }

    @Test
    fun frameLoop_should_SurfaceFailedState_NotCrashProcess_When_OutOfMemoryErrorThrownDuringCollection() = runBlocking {
        val cameraSource = AvailableNoFramesCameraSource()
        val oomReceiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = flow<ByteArray> {
                throw OutOfMemoryError("simulated OOM during frame collection")
            }
        }

        val vm = QrDecodeViewModel(
            cameraFrameSource = cameraSource,
            qrImportService = buildImportService(),
            settings = QrTransferSettings(MapSettings()),
            coordinatorFactory = { name ->
                QrTransferCoordinator(
                    frameTransportReceiver = oomReceiver,
                    cameraFrameSource = cameraSource,
                    qrImportService = buildImportService(),
                    targetName = name,
                )
            },
        )

        vm.start()

        val failed = withTimeout(5_000) {
            var s = vm.state.value
            while (s !is QrDecodeUiState.Failed) {
                s = vm.state.first { it != s }
            }
            s
        }
        assertIs<QrDecodeUiState.Failed>(failed)

        // The process is still alive and the VM is still usable — the OOM never escaped
        // uncaught (CLAUDE.md Throwable rule).
        vm.close()
    }
}
