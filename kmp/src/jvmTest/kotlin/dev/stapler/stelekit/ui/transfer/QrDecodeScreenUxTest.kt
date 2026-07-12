package dev.stapler.stelekit.ui.transfer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import dev.stapler.stelekit.transfer.qrcode.ScanHint
import dev.stapler.stelekit.transfer.qrcode.ScanResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Story 3.2.3 / UX Acceptance Test criteria 8, 13 (decoder half), 14: [QrDecodeScreen]'s scanning
 * surface (S9) — stalled-scan copy + frozen progress bar, live-updating viewfinder
 * `contentDescription`, and color-independent "locked on" vs "searching" signaling on the reticle.
 */
class QrDecodeScreenUxTest {

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

    private fun litFrame() =
        CameraFrame(luminanceBytes = ByteArray(4) { 200.toByte() }, width = 2, height = 2, rotationDegrees = 0)

    /**
     * Mirrors [QrDecodeScreenTest.buildScanningViewModel] (same fixture idiom, same file this
     * suite's assertions extend) — a viewmodel whose coordinator admits exactly [fragmentsToEmit]
     * genuinely-new fragments then idles forever, holding `Scanning(uniqueFragments =
     * fragmentsToEmit, ...)` indefinitely so state is stable for assertions. [delayMs] paces
     * emission between fragments — slower than the default 5ms so a test can reliably observe an
     * intermediate fragment count before the final one arrives.
     */
    private fun buildScanningViewModel(
        fragmentsToEmit: Int,
        delayMs: Long = 5,
        scan: (CameraFrame) -> ScanResult = { ScanResult.NoCodeDetected },
    ): QrDecodeViewModel {
        val cameraSource = object : CameraFrameSource {
            override val isAvailable = true
            override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
                emit(litFrame().right())
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
                    delay(delayMs)
                }
            }
        }
        val importService = buildImportService()
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

    /**
     * Like [buildScanningViewModel] but ALSO drives a periodic diagnostics stream of well-lit
     * "no code" frames forever, so [dev.stapler.stelekit.transfer.qrcode.TransferSession]'s
     * real-wall-clock stall timer (`STALL_THRESHOLD_SECONDS` = 8s — [QrTransferCoordinator] has no
     * injectable clock seam, see this suite's final report) genuinely crosses the threshold. This
     * necessarily takes real wall-clock time to observe (no way to fast-forward it through the
     * public API), unlike [buildScanningViewModel]'s other, instant-reacting scenarios.
     */
    private fun buildStalledViewModel(fragmentsToEmit: Int): QrDecodeViewModel {
        val cameraSource = object : CameraFrameSource {
            override val isAvailable = true
            override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
                emit(litFrame().right())
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
                    delay(5)
                }
            }
        }
        val importService = buildImportService()
        val fakeQrScanner = object : QrScanner {
            override fun decode(frame: CameraFrame): ScanResult = ScanResult.NoCodeDetected
            override val isAvailable: Boolean = true
            override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
                while (true) {
                    emit(litFrame().right())
                    delay(300)
                }
            }
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
    fun stalledScan_should_ShowMoveCloserCopy_AndFreezeProgressBar_When_StalledSecondsAtLeast8() {
        val vm = buildStalledViewModel(fragmentsToEmit = 7)

        composeTestRule.setContent {
            QrDecodeScreen(viewModel = vm, settings = QrTransferSettings(MapSettings()), onDismiss = {})
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            (vm.state.value as? QrDecodeUiState.Scanning)?.uniqueFragments == 7
        }
        // Real wall-clock wait — TransferSession's stall timer has no fast-forward seam.
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            val s = vm.state.value as? QrDecodeUiState.Scanning
            s != null && s.stalledSeconds >= 8 && s.hint == ScanHint.Stalled
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Not receiving new data — move closer or adjust the angle.").assertIsDisplayed()

        val progressMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        val progress1 = composeTestRule.onNode(progressMatcher).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current

        // uniqueFragments is pinned at 7 for the rest of this session (the receiver stopped
        // emitting) — the bar must not move on its own regardless of further recomposition/time
        // passing (UX criterion 8: never animate without genuine new-fragment progress).
        Thread.sleep(1_500)
        composeTestRule.waitForIdle()
        val progress2 = composeTestRule.onNode(progressMatcher).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current

        assertEquals(progress1, progress2, "progress bar must not advance while uniqueFragments is unchanged")

        vm.close()
    }

    @Test
    fun cameraViewfinder_should_UpdateContentDescriptionWithFragmentCount_When_ScanningStateAdvances() {
        val vm = buildScanningViewModel(fragmentsToEmit = 3, delayMs = 150)

        composeTestRule.setContent {
            QrDecodeScreen(viewModel = vm, settings = QrTransferSettings(MapSettings()), onDismiss = {})
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            (vm.state.value as? QrDecodeUiState.Scanning)?.uniqueFragments == 1
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Point camera at the SteleKit transfer code, 1 fragments received")
            .assertIsDisplayed()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            (vm.state.value as? QrDecodeUiState.Scanning)?.uniqueFragments == 3
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Point camera at the SteleKit transfer code, 3 fragments received")
            .assertIsDisplayed()

        vm.close()
    }

    /**
     * UX criterion 14 (ux.md §12.14 / validation.md row 14): "Color is never the sole signal for
     * scanning state ('locked on' vs. 'searching' in S9 reticle) — shape/icon + text must also
     * convey it."
     *
     * [dev.stapler.stelekit.ui.transfer.QrDecodeUiState.Scanning.isLockedOn] derives the state
     * from already-tracked `uniqueFragments`/`hint` (no new coordinator machinery), and
     * `CameraPreviewReticle` (`ui/transfer/QrDecodeScreen.kt`) renders a distinct filled
     * checkmark-box + "Locked on" label vs an outlined magnifying-glass box + "Searching…" label.
     */
    @Test
    fun reticleState_should_PairIconShapeAndTextWithColor_When_LockedOnVersusSearching() {
        val lockedOnVm = buildScanningViewModel(fragmentsToEmit = 2, delayMs = 5)
        composeTestRule.setContent {
            QrDecodeScreen(viewModel = lockedOnVm, settings = QrTransferSettings(MapSettings()), onDismiss = {})
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            (lockedOnVm.state.value as? QrDecodeUiState.Scanning)?.uniqueFragments == 2
        }
        composeTestRule.waitForIdle()
        // "Locked on" must be conveyed via text AND a distinct icon/shape resource — color alone
        // is never sufficient (protanopia/deuteranopia safety).
        composeTestRule.onNodeWithText("Locked on", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Locked on indicator", substring = true).assertExists()
        lockedOnVm.close()

        val searchingVm = buildScanningViewModel(fragmentsToEmit = 2, delayMs = 5, scan = { ScanResult.NotSteleKitCode })
        composeTestRule.setContent {
            QrDecodeScreen(viewModel = searchingVm, settings = QrTransferSettings(MapSettings()), onDismiss = {})
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            (searchingVm.state.value as? QrDecodeUiState.Scanning)?.hint == ScanHint.WrongCode
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Searching", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Searching indicator", substring = true).assertExists()
        searchingVm.close()
    }
}
