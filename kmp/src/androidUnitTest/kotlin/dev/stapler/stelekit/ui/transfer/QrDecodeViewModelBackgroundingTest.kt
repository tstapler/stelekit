package dev.stapler.stelekit.ui.transfer

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.sensor.CameraFrame
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.transfer.FrameTransportReceiver
import dev.stapler.stelekit.transfer.TransferId
import dev.stapler.stelekit.transfer.qrcode.ChunkFrameCodec
import dev.stapler.stelekit.transfer.qrcode.FountainCodec
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import dev.stapler.stelekit.transfer.qrcode.QrTransferCoordinator
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import dev.stapler.stelekit.transfer.qrcode.TransferPayloadEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Story 3.3.2 (UQ-3 decision): backgrounding a device mid-scan must NOT tear down
 * [QrDecodeViewModel]'s coordinator/`TransferSession`/`ChunkBuffer` — accumulated fragments
 * persist across a background/foreground cycle within the same VM lifetime (a process kill is a
 * separate, accepted-loss case per UQ-3, not exercised here). [QrDecodeViewModel.pause]/
 * [QrDecodeViewModel.resume] are intentionally no-ops for exactly this reason.
 *
 * Lives in `androidUnitTest` (not `businessTest`) per validation.md, mirroring the "simulates
 * Android lifecycle background/foreground" framing — no Android framework class is actually
 * required since [QrDecodeViewModel] is pure `commonMain`, but this is where the Android lifecycle
 * story is exercised.
 */
class QrDecodeViewModelBackgroundingTest {

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

    private class AvailableCameraFrameSource : CameraFrameSource {
        override val isAvailable = true
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = emptyFlow()
    }

    @Test
    fun transferSession_should_PreserveAccumulatedFragments_When_AppBackgroundedAndForegroundedWithinSameVmLifetime() = runBlocking {
        val markdown = "- backgrounding test page body with enough content to need several fountain chunks\n"
        val envelopeBytes = TransferPayloadEnvelope.wrap(PageName("Backgrounding Test Page"), markdown)
        val encoder = FountainCodec.encoder(TransferId(55), envelopeBytes, maxFragmentBytes = 10).getOrNull()!!
        assertTrue(encoder.seqLen >= 5, "fixture markdown must yield at least 5 pure fragments, was ${encoder.seqLen}")
        // Pure fragments 0 until seqLen, in order — receiving all of them (any order) is
        // sufficient for ChunkBuffer.isComplete(), so no extra redundant parts are needed here.
        val pureParts = encoder.parts().take(encoder.seqLen).toList()

        // Sent one at a time, confirmed via the coordinator's own events before the next send —
        // the coordinator's data path applies .conflate() by design (see QrTransferCoordinator
        // KDoc), so a burst-sent Channel.UNLIMITED can race-drop frames; this avoids that race.
        val frameChannel = Channel<ByteArray>(Channel.RENDEZVOUS)
        val receiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = frameChannel.consumeAsFlow()
        }
        val cameraSource = AvailableCameraFrameSource()

        val vm = QrDecodeViewModel(
            cameraFrameSource = cameraSource,
            qrImportService = buildImportService(),
            settings = QrTransferSettings(MapSettings()),
            coordinatorFactory = {
                QrTransferCoordinator(
                    frameTransportReceiver = receiver,
                    qrImportService = buildImportService(),
                )
            },
        )

        vm.start()

        // Deliver the first 5 pure fragments one at a time, confirming each admission before
        // sending the next — 5 genuinely distinct fragments admitted, deterministically.
        repeat(5) { i ->
            frameChannel.send(ChunkFrameCodec.encode(pureParts[i]))
            withTimeout(5_000) {
                vm.state.first { (it as? QrDecodeUiState.Scanning)?.uniqueFragments == i + 1 }
            }
        }
        assertEquals(5, assertIs<QrDecodeUiState.Scanning>(vm.state.value).uniqueFragments)

        // Simulate an app backgrounding and returning to foreground within the same VM lifetime —
        // must NOT reset the coordinator, TransferSession, or ChunkBuffer.
        vm.pause()
        vm.resume()

        val afterResume = vm.state.value
        val stillFive = assertIs<QrDecodeUiState.Scanning>(afterResume)
        assertEquals(5, stillFive.uniqueFragments, "fragments accumulated before backgrounding must survive the cycle")

        // Scanning must resume where it left off — delivering the rest, one at a time and
        // confirmed (same rationale as above), completes the transfer. The very last pure
        // fragment triggers ChunkBuffer.isComplete() and the coordinator's own reassemble/import
        // pipeline, so its confirmation is a terminal state instead of another Scanning count.
        for (i in 5 until pureParts.size) {
            frameChannel.send(ChunkFrameCodec.encode(pureParts[i]))
            val expectedCount = i + 1
            withTimeout(5_000) {
                vm.state.first { s ->
                    (s as? QrDecodeUiState.Scanning)?.uniqueFragments == expectedCount ||
                        s is QrDecodeUiState.Reassembling || s is QrDecodeUiState.Importing ||
                        s is QrDecodeUiState.Success || s is QrDecodeUiState.Failed
                }
            }
        }
        val terminal = withTimeout(5_000) {
            var s: QrDecodeUiState = vm.state.value
            while (s !is QrDecodeUiState.Success && s !is QrDecodeUiState.Failed) {
                s = vm.state.first { it != s }
            }
            s
        }
        val successState = assertIs<QrDecodeUiState.Success>(terminal)
        assertEquals("Backgrounding Test Page", successState.pageName.value, "the real decoded page name must survive the backgrounding cycle")

        vm.close()
    }
}
