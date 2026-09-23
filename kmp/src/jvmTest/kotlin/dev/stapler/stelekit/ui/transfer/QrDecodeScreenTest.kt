package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import arrow.core.Either
import arrow.core.right
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
import dev.stapler.stelekit.transfer.TransferId
import dev.stapler.stelekit.transfer.qrcode.ChunkFrameCodec
import dev.stapler.stelekit.transfer.qrcode.FountainCodec
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import dev.stapler.stelekit.transfer.qrcode.QrScanner
import dev.stapler.stelekit.transfer.qrcode.QrTransferCoordinator
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import dev.stapler.stelekit.transfer.qrcode.ScanResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test

/**
 * Story 3.2.3 / UX Acceptance Test criterion 8: [QrDecodeScreen] renders non-linear
 * "Receiving… (N fragments)" progress (not a percentage), fires an additive haptic tick per new
 * fragment (never load-bearing for the progress count), and shows hint-driven copy that is
 * textually distinct between a wrong/foreign QR and a generic stall.
 */
class QrDecodeScreenTest {

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

    /**
     * A ViewModel whose coordinator admits exactly [fragmentsToEmit] genuinely-new fragments (the
     * first [fragmentsToEmit] fountain parts are always systematic/pure per BC-UR, so each one is
     * a distinct index — see [dev.stapler.stelekit.transfer.qrcode.FountainEncoder]'s KDoc) and
     * then idles forever, holding `Scanning(uniqueFragments = fragmentsToEmit, ...)` indefinitely
     * so the screen state is stable for assertions.
     */
    private fun buildScanningViewModel(
        fragmentsToEmit: Int,
        scan: (CameraFrame) -> ScanResult = { ScanResult.NoCodeDetected },
    ): QrDecodeViewModel {
        val cameraSource = object : CameraFrameSource {
            override val isAvailable = true
            override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
                emit(CameraFrame(luminanceBytes = ByteArray(4) { 200.toByte() }, width = 2, height = 2, rotationDegrees = 0).right())
                awaitCancellation()
            }
        }
        val encoder = FountainCodec.encoder(TransferId(1), "x".repeat(500).encodeToByteArray(), maxFragmentBytes = 12).getOrNull()!!
        val receiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = flow {
                var count = 0
                for (chunk in encoder.parts()) {
                    if (count >= fragmentsToEmit) awaitCancellation()
                    emit(ChunkFrameCodec.encode(chunk))
                    count++
                    // A real (not just cooperative-yield) delay paces emission slower than the
                    // consumer's per-chunk processing, so the coordinator's `.conflate()` never
                    // has cause to drop one of these deliberately-few chunks before it's admitted.
                    kotlinx.coroutines.delay(5)
                }
            }
        }
        val importService = buildImportService()
        // Bug 3 fix: QrTransferCoordinator's diagnostics collaborator is now an actual injected
        // QrScanner instance, not a `scan` function reference — wrap cameraSource + scan into a
        // fake QrScanner bound to it.
        val fakeQrScanner = object : QrScanner {
            override fun decode(frame: CameraFrame): ScanResult = scan(frame)
            override val isAvailable: Boolean get() = cameraSource.isAvailable
            override fun frameStream() = cameraSource.frameStream()
        }
        return QrDecodeViewModel(
            cameraFrameSource = cameraSource,
            qrImportService = importService,
            settings = QrTransferSettings(MapSettings()),
            coordinatorFactory = {
                QrTransferCoordinator(
                    frameTransportReceiver = receiver,
                    qrImportService = importService,
                    qrScanner = fakeQrScanner,
                )
            },
        )
    }

    @Test
    fun qrDecodeScreen_should_ShowFragmentCountCopyAndHapticTick_When_StateIsScanningWithHintNull() {
        val vm = buildScanningViewModel(fragmentsToEmit = 7)
        var tickCount = 0

        composeTestRule.setContent {
            QrDecodeScreen(
                viewModel = vm,
                settings = QrTransferSettings(MapSettings()),
                onDismiss = {},
                onFragmentTick = { tickCount++ },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            (vm.state.value as? QrDecodeUiState.Scanning)?.uniqueFragments == 7
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Receiving… (7 fragments)").assertIsDisplayed()
        // Haptic tick is additive: at least one must have fired on the way to 7 fragments. Compose
        // may coalesce several rapid StateFlow emissions into fewer recompositions than there were
        // distinct fragment counts (LaunchedEffect only re-runs per recomposition, not per
        // upstream emission) — so this asserts "the tick mechanism fired," not an exact 1:1 count,
        // which is what Story 3.2.3's AC actually requires (a tick "on each new fragment" is a
        // best-effort UX enhancement, never load-bearing for the text line above).
        assert(tickCount >= 1) { "expected at least one haptic tick, got $tickCount" }

        vm.close()
    }

    @Test
    fun qrDecodeScreen_should_StillIncrementFragmentCountCorrectly_When_HapticsDisabled() {
        val vm = buildScanningViewModel(fragmentsToEmit = 5)

        composeTestRule.setContent {
            QrDecodeScreen(
                viewModel = vm,
                settings = QrTransferSettings(MapSettings()),
                onDismiss = {},
                onFragmentTick = { /* haptics disabled — purely additive, never load-bearing */ },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            (vm.state.value as? QrDecodeUiState.Scanning)?.uniqueFragments == 5
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Receiving… (5 fragments)").assertIsDisplayed()

        vm.close()
    }

    @Test
    fun qrDecodeScreen_should_ShowWrongCodeCopy_DistinctFromStallCopy_When_HintIsWrongCode() {
        val vm = buildScanningViewModel(fragmentsToEmit = 3, scan = { ScanResult.NotSteleKitCode })

        composeTestRule.setContent {
            QrDecodeScreen(
                viewModel = vm,
                settings = QrTransferSettings(MapSettings()),
                onDismiss = {},
                onFragmentTick = {},
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            (vm.state.value as? QrDecodeUiState.Scanning)?.hint == dev.stapler.stelekit.transfer.qrcode.ScanHint.WrongCode
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("That's not a SteleKit transfer code.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not receiving new data — move closer or adjust the angle.").assertDoesNotExist()

        vm.close()
    }
}
